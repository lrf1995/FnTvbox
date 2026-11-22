package app.fntv.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Bangumi（bgm.tv）公开 API 的最小封装：只做「按标题找一部番剧」这一件事。
 *
 * 几个必须遵守的点：
 *  - bgm.tv 强制校验 User-Agent，UA 缺失或为空会直接 403，所以每个请求都带上 UA；
 *  - 搜索是 POST /v0/search/subjects（JSON body），filter.type=[2,1] 表示「动画 + 书籍」，
 *    实测番剧（type=2）排在最前，正合这个 App 的场景；只取第一条 id；
 *  - 所有网络异常都在内部吃掉，失败返回 null / 空列表，绝不抛给 UI（详情页要能静默降级）。
 */
object BangumiApi {

    private const val BASE = "https://api.bgm.tv"

    /** bgm.tv 的 UA 白名单策略：写清客户端名 + 项目地址最稳妥。 */
    private const val UA = "FnTV/1.2 (https://github.com/lrf1995/FnTvbox)"

    private val JSON_TYPE = "application/json".toMediaType()

    /** 8 秒全超时：元数据只是锦上添花，不能把详情页卡住。 */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /** 搜索命中的条目 id，0 表示没找到（或请求失败）。 */
    suspend fun searchId(keyword: String): Int = withContext(Dispatchers.IO) {
        if (keyword.isBlank()) return@withContext 0
        val body = JSONObject()
            .put("keyword", keyword.trim())
            .put("filter", JSONObject().put("type", JSONArray().put(2).put(1)))
        val req = baseRequest("$BASE/v0/search/subjects")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        val json = fetchJson(req) ?: return@withContext 0
        val data = json.optJSONArray("data") ?: return@withContext 0
        for (i in 0 until data.length()) {
            val id = data.optJSONObject(i)?.optInt("id", 0) ?: 0
            if (id > 0) return@withContext id
        }
        0
    }

    /** 条目详情（中文标题 / 简介 / 评分 / 放送日期 / 大图海报），失败返回 null。 */
    suspend fun subject(id: Int): BangumiSubject? = withContext(Dispatchers.IO) {
        if (id <= 0) return@withContext null
        val req = baseRequest("$BASE/v0/subjects/$id").get().build()
        val json = fetchJson(req) ?: return@withContext null
        // name_cn 是中文名，可能为空串；为空时退回原名（日文/英文）
        val title = json.str("name_cn").ifEmpty { json.str("name") }
        if (title.isBlank()) return@withContext null
        val score = json.optJSONObject("rating")?.optDouble("score", 0.0) ?: 0.0
        val date = json.str("date")
        BangumiSubject(
            title = title,
            summary = json.str("summary"),
            score = if (score.isNaN()) 0.0 else score,
            date = date,
            year = yearOf(date),
            // images.large 本身就是完整 URL（https://lain.bgm.tv/...），不用拼基址
            poster = json.optJSONObject("images")?.str("large", "common", "medium", "small"),
        )
    }

    /**
     * 参与制作的人员（前 [limit] 个），relation 形如「主角」「导演」「脚本」。
     * 这个接口是可选装饰，失败了就当没有演职员表，不影响详情。
     */
    suspend fun persons(id: Int, limit: Int = 6): List<MetaCast> = withContext(Dispatchers.IO) {
        if (id <= 0 || limit <= 0) return@withContext emptyList()
        val req = baseRequest("$BASE/v0/subjects/$id/persons").get().build()
        val arr = fetchArray(req) ?: return@withContext emptyList()
        val out = ArrayList<MetaCast>(limit)
        for (i in 0 until arr.length()) {
            if (out.size >= limit) break
            val p = arr.optJSONObject(i) ?: continue
            val name = p.str("name")
            if (name.isBlank()) continue
            out += MetaCast(name, p.str("relation"))
        }
        out
    }

    // ───────────────────────── 内部工具 ─────────────────────────

    private fun baseRequest(url: String): Request.Builder = Request.Builder()
        .url(url)
        .header("User-Agent", UA)
        .header("Accept", "application/json")

    /** 统一取响应体：非 2xx、空 body、超时、DNS 失败一律 null。 */
    private fun fetchText(req: Request): String? = try {
        client.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (e: Exception) {
        null
    }

    private fun fetchJson(req: Request): JSONObject? = fetchText(req)
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun fetchArray(req: Request): JSONArray? = fetchText(req)
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { JSONArray(it) }.getOrNull() }

    /** 「2013-04-06」→「2013」，格式不对返回空串。 */
    private fun yearOf(date: String): String {
        val y = date.take(4)
        return if (y.length == 4 && y.all { it.isDigit() }) y else ""
    }
}

/** Bangumi 条目里这个 App 用得上的字段（已归一化）。 */
data class BangumiSubject(
    val title: String,
    val summary: String,
    val score: Double,
    val date: String,
    val year: String,
    val poster: String?,
)
