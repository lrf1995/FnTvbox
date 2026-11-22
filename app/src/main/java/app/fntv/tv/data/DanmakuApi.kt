package app.fntv.tv.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

/**
 * 一条弹幕（已归一化）。
 *
 * @param timeMs 出现时间（毫秒）
 * @param mode   原始模式：1/2 滚动、4 底部、5 顶部；0 也按滚动处理（实测源里会出现）
 * @param color  ARGB 颜色（已补成不透明）
 * @param text   弹幕文本
 */
data class DanmakuComment(
    val timeMs: Long,
    val mode: Int,
    val color: Int,
    val text: String,
)

/**
 * 自建 danmu_api（NAS 上的弹幕服务）客户端。
 *
 * 只做两件事：
 *  1. `GET {base}/api/v2/search/episodes?anime=<清洗后的剧名>` 找到 episodeId；
 *  2. `GET {base}/api/v2/comment/{episodeId}?format=json&duration=true` 取弹幕列表。
 *
 * 设计约束（弹幕是锦上添花，绝不能影响播放）：
 *  - base 为空 / 设置里关了弹幕 → 直接空列表，一次请求都不发；
 *  - 8 秒超时，任何异常（DNS、超时、非 2xx、JSON 坏掉）都返回 emptyList，不抛给 UI；
 *  - 只透传协程取消（切走 / Activity 销毁时要能立刻停）；
 *  - 内存缓存 title|season|episode → 弹幕列表，上限 20 集，LinkedHashMap 访问序当 LRU。
 */
object DanmakuApi {

    /** 缓存容量：一季 20 集左右，切集/重播不用重复拉。 */
    private const val CACHE_MAX = 20

    /** 弹幕接口整体超时：够 NAS 本地返回，又不会把「拉弹幕」拖成卡顿。 */
    private const val TIMEOUT_SEC = 8L

    /** 客串 UA：danmu_api 不做校验，但写清楚来源方便在服务端日志里认出来。 */
    private const val UA = "FnTV/1.2 (Android TV)"

    /** 弹幕搜索能接受的条目类型（都是「有集数」的形态，电影命中的概率极低）。 */
    private val PREFERRED_TYPES = listOf("电视剧", "动漫", "TV", "番剧")

    /** p 字段里超长/异常的行直接跳过，防止一条脏数据让整部剧的弹幕失效。 */
    private const val MAX_TEXT_LEN = 200

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * title|season|episode → 弹幕列表。
     * 用访问序 LinkedHashMap（get 也会挪到队尾）就是最省事的 LRU，满了淘汰最久没用的那条。
     */
    private val cache = object : LinkedHashMap<String, List<DanmakuComment>>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, List<DanmakuComment>>,
        ): Boolean = size > CACHE_MAX
    }

    /**
     * 按剧名 + 季 + 集找 episodeId 并取弹幕。
     *
     * @return 按时间升序排好的弹幕；base 为空 / 开关关闭 / 没匹配到 / 任何失败都返回 emptyList。
     */
    suspend fun fetch(title: String, season: Int, episode: Int): List<DanmakuComment> {
        // Prefs 理论上在 Application.onCreate 里已初始化；这里仍然用 runCatching 兜一层，
        // 避免「配置没就绪」把播放页拖崩。
        val base = runCatching { Prefs.danmuApiBase.trim().trimEnd('/') }.getOrDefault("")
        val enabled = runCatching { Prefs.danmakuEnabled }.getOrDefault(true)
        if (base.isEmpty() || !enabled) return emptyList()

        // 清洗剧名：文件名里的 S01E02 / 第 3 集 / [字幕组] 都不是搜索词的一部分
        val keyword = cleanTitle(title).ifEmpty { title.trim() }
        if (keyword.isEmpty()) return emptyList()

        val seasonNo = season.coerceAtLeast(0)
        val episodeNo = episode.coerceAtLeast(0)
        val key = "$keyword|$seasonNo|$episodeNo"
        fromCache(key)?.let { return it }

        return withContext(Dispatchers.IO) {
            // 两步都各自兜异常：搜索失败没必要再去请求弹幕
            val episodeId = guard { searchEpisodeId(base, keyword, episodeNo) } ?: 0
            if (episodeId <= 0) return@withContext emptyList<DanmakuComment>()

            val list = guard { fetchComments(base, episodeId) }.orEmpty()
            // 只缓存成功且非空的结果：网络抖动导致的空结果下次还能重试
            if (list.isNotEmpty()) saveCache(key, list)
            list
        }
    }

    // ───────────────────────── 第一步：搜 episodeId ─────────────────────────

    /**
     * 搜索并挑出这一集的 episodeId，0 表示没找到。
     *
     * 匹配顺序：
     *  1. 优先 type 像「电视剧/动漫/TV」且集数 >= 目标集的 anime；
     *  2. 在其中按 episodeTitle 里的「第 N 集 / E0N」精确定位；
     *  3. 定位不到就按集序兜底 episodes[episode-1]，越界取第一条。
     */
    private fun searchEpisodeId(base: String, keyword: String, episode: Int): Int {
        // 实测只认 anime 参数；季不参与搜索（只进缓存键），多传反而有兼容风险
        val url = "$base/api/v2/search/episodes?anime=${encode(keyword)}"
        val json = fetchJson(url) ?: return 0
        val animes = json.optJSONArray("animes") ?: return 0

        val all = ArrayList<JSONObject>(animes.length())
        for (i in 0 until animes.length()) {
            animes.optJSONObject(i)?.let { all += it }
        }
        if (all.isEmpty()) return 0

        val want = episode.coerceAtLeast(1)
        val preferred = all.filter { a ->
            val type = a.str("type")
            val enough = (a.optJSONArray("episodes")?.length() ?: 0) >= want
            enough && PREFERRED_TYPES.any { type.contains(it, ignoreCase = true) }
        }
        val pool = preferred
            .ifEmpty { all.filter { (it.optJSONArray("episodes")?.length() ?: 0) >= want } }
            .ifEmpty { all }

        // 2) 按集标题精确匹配（「第01集」「E01」「S01E01」都能认）
        for (anime in pool) {
            val episodes = anime.optJSONArray("episodes") ?: continue
            findEpisodeId(episodes, want)?.let { return it }
        }

        // 3) 兜底：按集序取，越界取第一条（有些源标题里根本不写集号）
        for (anime in pool) {
            val episodes = anime.optJSONArray("episodes") ?: continue
            if (episodes.length() <= 0) continue
            val index = if (want - 1 in 0 until episodes.length()) want - 1 else 0
            val item = episodes.optJSONObject(index) ?: continue
            val id = item.optInt("episodeId", 0)
            if (id > 0) return id
        }
        return 0
    }

    /** 在某一部剧的 episodes 里找第 [number] 集。 */
    private fun findEpisodeId(episodes: JSONArray, number: Int): Int? {
        for (i in 0 until episodes.length()) {
            val e = episodes.optJSONObject(i) ?: continue
            val title = e.str("episodeTitle")
            if (title.isEmpty()) continue
            val cn = EPISODE_CN.find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val en = EPISODE_EN.find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (cn == number || en == number) {
                val id = e.optInt("episodeId", 0)
                if (id > 0) return id
            }
        }
        return null
    }

    // ───────────────────────── 第二步：取弹幕 ─────────────────────────

    /**
     * 拉某一集的弹幕并解析。
     *
     * 返回体形如：
     * `{"count":1634,"comments":[{"cid":4,"p":"0.00,1,16777215,[renren]","m":"666","t":0}]}`
     * p = 「时间秒,模式,颜色十进制,用户ID…」，m 是文本，t 忽略。
     */
    private fun fetchComments(base: String, episodeId: Int): List<DanmakuComment> {
        val url = "$base/api/v2/comment/$episodeId?format=json&duration=true"
        val json = fetchJson(url) ?: return emptyList()
        val arr = json.optJSONArray("comments") ?: return emptyList()

        val out = ArrayList<DanmakuComment>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val text = o.optString("m").trim()
            if (text.isEmpty() || text.length > MAX_TEXT_LEN) continue

            val p = o.optString("p")
            if (p.isEmpty()) continue
            val parts = p.split(',')
            val seconds = parts.getOrNull(0)?.trim()?.toDoubleOrNull() ?: continue
            if (seconds.isNaN() || seconds < 0.0) continue

            // 模式缺省按滚动；0 也归到滚动（实测源里会出现）
            val mode = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 1
            // 颜色是十进制 RGB，补上不透明 alpha 变成 ARGB
            val rgb = parts.getOrNull(2)?.trim()?.toIntOrNull() ?: 0xFFFFFF

            out += DanmakuComment(
                timeMs = (seconds * 1000.0).roundToLong(),
                mode = mode,
                color = (0xFF shl 24) or (rgb and 0xFFFFFF),
                text = text,
            )
        }
        // 排序后上层可以「按时间扫到没有就 break」，渲染时也天然按出现顺序
        out.sortBy { it.timeMs }
        return out
    }

    // ───────────────────────── 剧名清洗 ─────────────────────────

    // 这些正则只服务弹幕搜索，不复用 MetaRepo.cleanTitle（那边是元数据匹配，规则不能互相牵制）
    private val EXT = Regex(
        "\\.(mkv|mp4|avi|ts|m2ts|mov|wmv|flv|rmvb|webm|iso)$",
        RegexOption.IGNORE_CASE,
    )
    private val BRACKET_TAG = Regex("[【\\[][^】\\]]*[】\\]]")
    private val CURLY_TAG = Regex("\\{[^}]*}")
    private val YEAR_IN_PARENS = Regex("[（(]\\s*(?:19|20)\\d{2}\\s*[)）]")
    private val EPISODE_NOISE = Regex(
        "(?i)\\bS\\d{1,2}(?:E\\d{1,3})?\\b" +   // S01E02 / S2
            "|\\bEP?\\s?\\d{1,4}\\b" +          // E01 / EP12
            "|第\\s*\\d{1,4}\\s*[季集话話期]" +  // 第 3 集 / 第2季
            "|\\s[-–—]\\s*\\d{1,4}\\s*$",       // 「- 04」这种番剧尾巴
    )
    private val EPISODE_CN = Regex("第\\s*0*(\\d{1,4})\\s*[集话話期]")
    private val EPISODE_EN = Regex("(?i)\\bE0*(\\d{1,4})\\b")
    private val SPACES = Regex("\\s+")

    /** 把文件名/剧集名收拾成能拿去搜索的剧名，例：「[Lilith-Raws] 进击的巨人 S01E02」→「进击的巨人」。 */
    private fun cleanTitle(raw: String): String {
        var t = raw.trim()
        if (t.isEmpty()) return ""
        t = t.replace(EXT, " ")
        t = t.replace(BRACKET_TAG, " ")
        t = t.replace(CURLY_TAG, " ")
        t = t.replace(YEAR_IN_PARENS, " ")
        t = t.replace(EPISODE_NOISE, " ")
        t = t.replace('\u3000', ' ')
        t = t.replace(SPACES, " ")
        t = t.trim().trim('-', '_', '.', '·', '|', '/', ':', '：', '「', '」', '『', '』', ' ')
        return t.trim()
    }

    // ───────────────────────── 网络 / 缓存 / 容错 ─────────────────────────

    private fun encode(text: String): String = try {
        URLEncoder.encode(text, "UTF-8")
    } catch (e: Exception) {
        text
    }

    private fun fetchText(url: String): String? = try {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (e: Exception) {
        // 超时 / 解析失败 / 地址写错：静默降级成「没有弹幕」
        null
    }

    private fun fetchJson(url: String): JSONObject? = fetchText(url)
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun fromCache(key: String): List<DanmakuComment>? = synchronized(cache) { cache[key] }

    private fun saveCache(key: String, list: List<DanmakuComment>) {
        synchronized(cache) { cache[key] = list }
    }

    /** 吞掉数据层异常，但一定把协程取消透传出去。 */
    private inline fun <T> guard(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}