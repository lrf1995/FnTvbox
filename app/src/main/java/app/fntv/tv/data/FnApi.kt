package app.fntv.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class FnException(message: String, val code: Int = 0) : Exception(message)

/**
 * 飞牛影视（fnOS / trimemedia）API 客户端。
 *
 * 协议细节来自两个开源客户端（GPL-3.0 fntv-electron、AGPL-3.0 AniBox）：
 *  - POST/PUT/PATCH/DELETE：body 末尾追加 nonce，签名与实际发送字节一致
 *  - Authx = md5(apiKey_path_nonce_timestamp_md5(body)_apiSecret)
 *  - GET 带 query 时按 key 升序的原始 k=v 串参与签名
 *  - 鉴权双通道：Authorization 头 + Cookie: mode=relay; Trim-MC-token=<token>
 *  - code==5000 为签名偶发失败，重试一次；code==-2 为会话过期
 */
object FnApi {

    // 飞牛官方 Web 影视客户端内置的公开客户端凭据（见 fntv-electron src/modules/fn_api/request.ts）
    private const val API_KEY = "NDzZTVxnRKP8Z0jXg1VAMonaG8akvh"
    private const val API_SECRET = "16CCEB3D-AB42-077D-36A1-F355324E4237"

    const val UA = "Mozilla/5.0 (Linux; Android 12; Android TV) FnTV/1.0"

    private const val ERR_AUTH_EXPIRED = "登录已过期，请重新登录"

    private val JSON_TYPE = "application/json".toMediaType()

    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val trustAllFactory: SSLSocketFactory by lazy {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        ctx.socketFactory
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .sslSocketFactory(trustAllFactory, trustAllManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    private val cookieStore = HashMap<String, MutableMap<String, String>>()

    /** 最近一次成功请求实际使用的基址（fnOS 80 端口会 302 到实际端口，登录后据此固化）。 */
    @Volatile
    var lastBase: String = ""
        private set

    // ───────────────────────── 基础工具 ─────────────────────────

    fun md5(text: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    fun sha256(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    private val random = SecureRandom()

    fun rand6(): String = (100000 + random.nextInt(900000)).toString()

    /** 宽容处理用户输入的服务器地址：全角冒号、引号、缺协议头、多余路径。 */
    fun normalizeServer(input: String): String {
        var s = input.trim().replace('：', ':')
        s = s.trim('"', '\'', ' ')
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) {
            s = "http://$s"
        }
        val idx = s.indexOf("://")
        if (idx >= 0) {
            val rest = s.substring(idx + 3)
            val slash = rest.indexOf('/')
            if (slash >= 0) s = s.substring(0, idx + 3) + rest.substring(0, slash)
        }
        return s.trimEnd('/')
    }

    fun hostOf(server: String): String {
        val s = normalizeServer(server)
        val rest = s.substringAfter("://", s)
        return rest.substringBefore('/').substringBefore(':')
    }

    private fun hostKey(url: String): String =
        url.substringAfter("://", url).substringBefore('/').lowercase()

    private fun captureCookies(url: String, headers: List<String>) = synchronized(cookieStore) {
        val host = hostKey(url)
        val store = cookieStore.getOrPut(host) { HashMap() }
        for (raw in headers) {
            val first = raw.substringBefore(';').trim()
            val eq = first.indexOf('=')
            if (eq <= 0) continue
            val name = first.substring(0, eq).trim()
            val value = first.substring(eq + 1).trim()
            val lower = raw.lowercase()
            if (value.isEmpty() || lower.contains("max-age=0")) store.remove(name) else store[name] = value
        }
        Unit
    }

    private fun gatewayCookies(url: String): String = synchronized(cookieStore) {
        val store = cookieStore[hostKey(url)] ?: return@synchronized ""
        store.entries
            .filter { !it.key.equals("Trim-MC-token", true) && !it.key.equals("mode", true) }
            .joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun cookieHeader(url: String, token: String): String {
        val sb = StringBuilder("mode=relay")
        if (token.isNotEmpty()) sb.append("; Trim-MC-token=").append(token)
        val gw = gatewayCookies(url)
        if (gw.isNotEmpty()) sb.append("; ").append(gw)
        return sb.toString()
    }

    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

    /** Authx 头：nonce/timestamp/sign，sign 覆盖 path 与最终 body 字节。 */
    private fun authx(path: String, body: String?): String {
        val nonce = rand6()
        val ts = System.currentTimeMillis()
        val dataMd5 = md5(body ?: "")
        val sign = md5("${API_KEY}_${path}_${nonce}_${ts}_${dataMd5}_${API_SECRET}")
        return "nonce=$nonce&timestamp=$ts&sign=$sign"
    }

    // ───────────────────────── 请求入口 ─────────────────────────

    suspend fun call(
        server: String,
        path: String,
        method: String = "GET",
        token: String = "",
        body: JSONObject? = null,
        query: List<Pair<String, String>>? = null,
    ): JSONObject = withContext(Dispatchers.IO) {
        var base = normalizeServer(server)
        var methodPath = path
        var attempt = 0
        while (true) {
            attempt++
            if (attempt > 4) throw FnException("请求重定向次数过多")

            val upper = method.uppercase()
            val hasBody = upper == "POST" || upper == "PUT" || upper == "PATCH" ||
                (upper == "DELETE" && body != null)

            var bodyStr = ""
            if (hasBody) {
                val copy = body?.let { JSONObject(it.toString()) } ?: JSONObject()
                copy.put("nonce", rand6())
                bodyStr = copy.toString()
            }

            val signData = when {
                query != null && query.isNotEmpty() ->
                    query.sortedBy { it.first }.joinToString("&") { "${it.first}=${it.second}" }
                else -> bodyStr
            }

            val url = if (query != null && query.isNotEmpty()) {
                base + methodPath + "?" + query.sortedBy { it.first }
                    .joinToString("&") { "${enc(it.first)}=${enc(it.second)}" }
            } else {
                base + methodPath
            }

            val builder = Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .header("Authorization", token)
                .header("Authx", authx(methodPath, if (signData.isEmpty()) null else signData))
                .header("Cookie", cookieHeader(url, token))
                .header("x-trim-client", "web")
                .header("x-trim-client-version", "616")
                .header("User-Agent", UA)

            if (hasBody) {
                builder.method(upper, bodyStr.toRequestBody(JSON_TYPE))
            } else {
                builder.method(upper, null)
            }

            val response = try {
                client.newCall(builder.build()).execute()
            } catch (e: Exception) {
                throw FnException(
                    when {
                        e.message?.contains("timeout", true) == true -> "连接超时：NAS 未响应"
                        else -> "无法连接服务器：${e.message ?: "网络错误"}"
                    }
                )
            }

            val resp = response
            try {
                captureCookies(url, resp.headers.values("Set-Cookie"))
                val code = resp.code

                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    val location = resp.header("Location") ?: throw FnException("服务器重定向异常")
                    val schemeIdx = location.indexOf("://")
                    if (schemeIdx > 0) {
                        val afterScheme = location.substring(schemeIdx + 3)
                        val hostEnd = afterScheme.indexOf('/')
                        if (hostEnd >= 0) {
                            base = location.substring(0, schemeIdx + 3) + afterScheme.substring(0, hostEnd)
                            methodPath = afterScheme.substring(hostEnd)
                        } else {
                            base = location
                            methodPath = "/"
                        }
                    } else {
                        methodPath = location
                    }
                    continue
                }

                if (code == 401) throw FnException(ERR_AUTH_EXPIRED, -2)

                val text = resp.body?.string().orEmpty()
                val contentType = resp.header("Content-Type").orEmpty()
                if (contentType.contains("text/html", true)) {
                    throw FnException("HTTP $code：地址/端口可能填错了（返回的是网页而不是接口数据）")
                }
                val json = try {
                    JSONObject(text)
                } catch (e: Exception) {
                    throw FnException("HTTP $code：返回非 JSON 数据")
                }

                val apiCode = json.optInt("code", -1)
                if (apiCode == 5000) {
                    if (attempt >= 3) throw FnException("签名校验失败（invalid sign）")
                    continue
                }
                if (apiCode != 0) {
                    val msg = json.optString("msg").ifEmpty { json.optString("message", "未知错误") }
                    if (apiCode == -2 || msg.contains("Auth Expired", true) || msg.contains("会话过期")) {
                        throw FnException(ERR_AUTH_EXPIRED, -2)
                    }
                    throw FnException(msg, apiCode)
                }
                lastBase = base
                return@withContext json.opt("data") as? JSONObject ?: JSONObject()
            } finally {
                resp.close()
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw FnException("请求失败")
    }

    /** 有些接口 data 直接是数组，这里单独取原始 JSON。 */
    suspend fun callRaw(
        server: String,
        path: String,
        method: String = "GET",
        token: String = "",
        body: JSONObject? = null,
        query: List<Pair<String, String>>? = null,
    ): Any? = withContext(Dispatchers.IO) {
        val base = normalizeServer(server)
        val upper = method.uppercase()
        val hasBody = upper == "POST" || upper == "PUT" || upper == "PATCH" ||
            (upper == "DELETE" && body != null)
        var bodyStr = ""
        if (hasBody) {
            val copy = body?.let { JSONObject(it.toString()) } ?: JSONObject()
            copy.put("nonce", rand6())
            bodyStr = copy.toString()
        }
        val signData = when {
            query != null && query.isNotEmpty() ->
                query.sortedBy { it.first }.joinToString("&") { "${it.first}=${it.second}" }
            else -> bodyStr
        }
        val url = if (query != null && query.isNotEmpty()) {
            base + path + "?" + query.sortedBy { it.first }
                .joinToString("&") { "${enc(it.first)}=${enc(it.second)}" }
        } else base + path

        val builder = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("Authorization", token)
            .header("Authx", authx(path, if (signData.isEmpty()) null else signData))
            .header("Cookie", cookieHeader(url, token))
            .header("x-trim-client", "web")
            .header("x-trim-client-version", "616")
            .header("User-Agent", UA)

        if (hasBody) builder.method(upper, bodyStr.toRequestBody(JSON_TYPE)) else builder.method(upper, null)

        client.newCall(builder.build()).execute().use { resp ->
            captureCookies(url, resp.headers.values("Set-Cookie"))
            if (resp.code == 401) throw FnException(ERR_AUTH_EXPIRED, -2)
            val text = resp.body?.string().orEmpty()
            if (resp.header("Content-Type").orEmpty().contains("text/html", true)) {
                throw FnException("HTTP ${resp.code}：返回了网页而不是接口数据")
            }
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw FnException("HTTP ${resp.code}：返回非 JSON 数据")
            }
            val apiCode = json.optInt("code", -1)
            if (apiCode != 0) {
                val msg = json.optString("msg").ifEmpty { "未知错误" }
                if (apiCode == -2 || msg.contains("Auth Expired", true)) throw FnException(ERR_AUTH_EXPIRED, -2)
                throw FnException(msg, apiCode)
            }
            json.opt("data")
        }
    }

    fun dataArray(data: Any?): JSONArray = when (data) {
        is JSONArray -> data
        is JSONObject -> data.optJSONArray("list") ?: JSONArray()
        else -> JSONArray()
    }

    // ───────────────────────── 业务接口 ─────────────────────────

    suspend fun login(server: String, username: String, password: String): String {
        // 新版 fnOS（影视 Web 端也是这套）登录：POST /v/api/v2/user/loginByPassword，
        // 密码字段是 **SHA-256 十六进制**（不是明文）。旧版 v1 /login 收明文。
        // 先走 v2；只有「接口不存在/参数不认」(-1 / Not Implemented / HTML) 才回退 v1，
        // 避免同一份错误密码把账号失败次数打两次。
        val shaBody = JSONObject()
            .put("app_name", "trimemedia-web")
            .put("username", username)
            .put("password", sha256(password))

        val v2 = runCatching {
            call(server, "/v/api/v2/user/loginByPassword", "POST", "", shaBody)
        }
        val v2Data = v2.getOrNull()
        if (v2Data != null) {
            val token = v2Data.optString("token")
            if (token.isNotEmpty()) return token
        }

        val v2Err = v2.exceptionOrNull()
        val shouldFallback = v2Data == null && (
            v2Err is FnException && v2Err.code == -1 ||
                v2Err?.message?.contains("Not Implemented", true) == true ||
                v2Err?.message?.contains("网页", true) == true ||
                v2Err?.message?.contains("非 JSON", true) == true
            )
        if (!shouldFallback) throw (v2Err ?: FnException("登录响应缺少 token"))

        val body = JSONObject()
            .put("app_name", "trimemedia-web")
            .put("username", username)
            .put("password", password)
        val data = call(server, "/v/api/v1/login", "POST", "", body)
        val token = data.optString("token")
        if (token.isEmpty()) throw FnException("登录成功但未返回 token")
        return token
    }

    suspend fun userInfo(server: String, token: String): JSONObject =
        call(server, "/v/api/v1/user/info", "GET", token)

    suspend fun mediaLibs(server: String, token: String): JSONArray =
        dataArray(callRaw(server, "/v/api/v1/mediadb/list", "GET", token))

    suspend fun itemList(
        server: String,
        token: String,
        ancestorGuid: String? = null,
        parentGuid: String? = null,
        page: Int = 1,
        pageSize: Int = 60,
        sortColumn: String = "create_time",
        sortType: String = "DESC",
    ): JSONObject {
        val body = JSONObject()
        if (parentGuid != null) {
            body.put("parent_guid", parentGuid)
            body.put("exclude_grouped_video", 0)
            body.put("sort_column", "sort_title")
            body.put("sort_type", "ASC")
        } else {
            if (ancestorGuid != null) body.put("ancestor_guid", ancestorGuid)
            body.put("exclude_grouped_video", 1)
            body.put("sort_column", sortColumn)
            body.put("sort_type", sortType)
        }
        body.put("page", page)
        body.put("page_size", pageSize)
        body.put(
            "tags",
            JSONObject().put(
                "type",
                JSONArray().put("Movie").put("TV").put("Directory").put("Video").put("LiveChannel")
            )
        )
        return call(server, "/v/api/v1/item/list", "POST", token, body)
    }

    suspend fun playList(server: String, token: String): JSONArray =
        dataArray(callRaw(server, "/v/api/v1/play/list", "GET", token))

    suspend fun seasons(server: String, token: String, seriesGuid: String): JSONArray =
        dataArray(callRaw(server, "/v/api/v1/season/list/$seriesGuid", "GET", token))

    suspend fun episodes(server: String, token: String, seasonGuid: String): JSONArray =
        dataArray(callRaw(server, "/v/api/v1/episode/list/$seasonGuid", "GET", token))

    suspend fun search(server: String, token: String, q: String): JSONArray =
        dataArray(
            callRaw(
                server, "/v/api/v1/search/list", "GET", token,
                query = listOf("q" to q)
            )
        )

    suspend fun playInfo(server: String, token: String, itemGuid: String, mediaGuid: String? = null): JSONObject {
        val body = JSONObject().put("item_guid", itemGuid)
        if (!mediaGuid.isNullOrEmpty()) body.put("media_guid", mediaGuid)
        return call(server, "/v/api/v1/play/info", "POST", token, body)
    }

    suspend fun recordProgress(
        server: String,
        token: String,
        itemGuid: String,
        mediaGuid: String,
        videoGuid: String,
        audioGuid: String,
        subtitleGuid: String,
        positionSec: Int,
        durationSec: Int,
    ) {
        if (token.isEmpty() || positionSec <= 0 || durationSec <= 0) return
        val body = JSONObject()
            .put("item_guid", itemGuid)
            .put("media_guid", mediaGuid)
            .put("video_guid", videoGuid)
            .put("audio_guid", audioGuid)
            .put("subtitle_guid", subtitleGuid)
            .put("play_link", hostOf(server))
            .put("ts", positionSec)
            .put("duration", durationSec)
        runCatching { call(server, "/v/api/v1/play/record", "POST", token, body) }
    }

    suspend fun markWatched(server: String, token: String, itemGuid: String) {
        call(server, "/v/api/v1/item/watched", "POST", token, JSONObject().put("item_guid", itemGuid))
    }

    fun streamUrl(server: String, mediaGuid: String): String =
        "${normalizeServer(server)}/v/api/v1/media/range/$mediaGuid"

    fun imageUrl(server: String, path: String?, width: Int = 500): String {
        if (path.isNullOrBlank()) return ""
        if (path.startsWith("http")) return path
        val p = if (path.startsWith("/")) path else "/$path"
        return "${normalizeServer(server)}/v/api/v1/sys/img$p?w=$width"
    }
}