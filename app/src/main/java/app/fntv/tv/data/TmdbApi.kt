package app.fntv.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * TMDB v3 精简客户端：只抄「中文标题 / 简介 / 评分 / 海报 / 前 6 位演员」。
 *
 * - key 来自设置页的 [Prefs.tmdbKey]，没填就完全不发请求（直接 null）；
 * - 搜索走 /search/multi（会混进人物结果，只认 movie / tv）；
 * - 详情按 movie / tv 两种路径取，演员走 /credits，演员失败不影响主信息；
 * - 全部调用包 try/catch，8 秒超时，失败返回 null。
 */
object TmdbApi {

    private const val BASE = "https://api.themoviedb.org/3"
    private const val IMAGE_BASE = "https://image.tmdb.org/t/p/w500"
    private const val LANG = "zh-CN"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /** 设置页里填的 key（已 trim）；Prefs 还没 init 时按「未设置」处理，别把 App 崩掉。 */
    fun apiKey(): String = runCatching { Prefs.tmdbKey.trim() }.getOrDefault("")

    /** 搜索并返回第一个 movie / tv 结果，找不到或没 key 返回 null。 */
    suspend fun searchId(keyword: String): TmdbHit? = withContext(Dispatchers.IO) {
        val key = apiKey()
        if (key.isEmpty() || keyword.isBlank()) return@withContext null
        val url = "$BASE/search/multi?api_key=${enc(key)}&language=$LANG&query=${enc(keyword.trim())}"
        val json = fetchJson(url) ?: return@withContext null
        val results = json.optJSONArray("results") ?: return@withContext null
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            val type = o.str("media_type")
            if (type != "movie" && type != "tv") continue
            val id = o.optInt("id", 0)
            if (id > 0) return@withContext TmdbHit(type, id)
        }
        null
    }

    /** 详情 + 演员表，归一化成 [MetaInfo]；任何一步失败返回 null。 */
    suspend fun detail(hit: TmdbHit): MetaInfo? = withContext(Dispatchers.IO) {
        val key = apiKey()
        if (key.isEmpty() || hit.id <= 0) return@withContext null
        val query = "api_key=${enc(key)}&language=$LANG"
        val d = fetchJson("$BASE/${hit.mediaType}/${hit.id}?$query") ?: return@withContext null
        // 电影是 title / release_date，剧集是 name / first_air_date
        val title = d.str("title", "name")
        if (title.isBlank()) return@withContext null
        val date = d.str("release_date", "first_air_date")
        val rating = d.optDouble("vote_average", 0.0)
        // poster_path 是相对路径（形如 /abc.jpg），为空或 "null" 时给 null
        val posterPath = d.str("poster_path")
        MetaInfo(
            source = "tmdb",
            title = title,
            overview = d.str("overview"),
            rating = if (rating.isNaN()) 0.0 else rating,
            year = yearOf(date),
            poster = if (posterPath.isEmpty()) null else IMAGE_BASE + posterPath,
            airDate = date,
            cast = credits(hit, key),
        )
    }

    // ───────────────────────── 内部工具 ─────────────────────────

    /** 演员表：cast 前 6 个 name + character。这里是阻塞调用，调用方已在 IO 线程。 */
    private fun credits(hit: TmdbHit, key: String): List<MetaCast> {
        val json = fetchJson("$BASE/${hit.mediaType}/${hit.id}/credits?api_key=${enc(key)}&language=$LANG")
            ?: return emptyList()
        val cast = json.optJSONArray("cast") ?: return emptyList()
        val out = ArrayList<MetaCast>(6)
        for (i in 0 until cast.length()) {
            if (out.size >= 6) break
            val o = cast.optJSONObject(i) ?: continue
            val name = o.str("name")
            if (name.isBlank()) continue
            out += MetaCast(name, o.str("character"))
        }
        return out
    }

    private fun enc(value: String): String = try {
        URLEncoder.encode(value, "UTF-8")
    } catch (e: Exception) {
        value
    }

    private fun fetchJson(url: String): JSONObject? = try {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                null
            } else {
                val text = resp.body?.string().orEmpty()
                if (text.isBlank()) null else runCatching { JSONObject(text) }.getOrNull()
            }
        }
    } catch (e: Exception) {
        null
    }

    /** 「2014-11-07」→「2014」，格式不对返回空串。 */
    private fun yearOf(date: String): String {
        val y = date.take(4)
        return if (y.length == 4 && y.all { it.isDigit() }) y else ""
    }
}

/** TMDB 搜索命中：mediaType 是 "movie" 或 "tv"。 */
data class TmdbHit(val mediaType: String, val id: Int)
