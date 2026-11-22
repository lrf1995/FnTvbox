package app.fntv.tv.data

import kotlinx.coroutines.CancellationException

/**
 * 一条演职员：Bangumi 给的是「人物 + 关系（主角/导演）」，TMDB 给的是「演员 + 角色名」，
 * 两者都塞进 role，详情页只负责展示，不区分来源。
 */
data class MetaCast(val name: String, val role: String)

/** 详情页用的外部元数据：把 Bangumi / TMDB 归一化成同一份结构。 */
data class MetaInfo(
    val source: String,      // "bangumi" 或 "tmdb"
    val title: String,
    val overview: String,
    val rating: Double,      // 0 表示没有
    val year: String,
    val poster: String?,     // 完整 URL
    val airDate: String,     // 空串表示没有
    val cast: List<MetaCast>,
)

/**
 * 元数据仓库：先 Bangumi（番剧库更全、中文简介质量好），没命中再用 TMDB（要用户填了 key）。
 *
 * 匹配策略：
 *  1. 剧集/季/文件夹条目优先用 tvTitle（剧名），因为它们的 title 常常是「第 3 集」这种没用的名字；
 *     电影/单集没有 tvTitle 时用 title；
 *  2. 每个候选标题先清洗：去文件扩展名、去 {tmdb-xxx} 这类花括号标签、去「(2023)」年份、
 *     去 S01E02 / 第 3 集 / 「- 04」这类集数尾巴，最后压掉空白；
 *  3. 若清洗后仍带发布组标签（如「[Lilith-Raws] 进击的巨人 [Baha][WEB-DL]」），
 *     再抠出其中最长的一段中文/日文当追加候选，提高番剧命中率；
 *  4. 候选最多 3 个，每轮按「Bangumi → TMDB」的顺序试，任一命中立即返回；
 *  5. 成功结果按清洗后的标题缓存（最多 64 条，满了整体清空），失败不缓存，下次进详情页还能重试；
 *  6. 任何环节出错（超时、403、没 key、没匹配）都返回 null，详情页照常用飞牛自带的信息。
 */
object MetaRepo {

    private const val CACHE_MAX = 64
    private const val CAST_LIMIT = 6
    private const val KEYWORD_MAX = 3

    private val cache = HashMap<String, MetaInfo>()

    /** 按标题找元数据：先 Bangumi，没命中再 TMDB。失败返回 null。 */
    suspend fun lookup(item: MediaItem): MetaInfo? {
        for (keyword in keywordsOf(item)) {
            fromCache(keyword)?.let { return it }

            val bangumi = guard { lookupBangumi(keyword) }
            if (bangumi != null) {
                saveCache(keyword, bangumi)
                return bangumi
            }

            val tmdb = guard { lookupTmdb(keyword) }
            if (tmdb != null) {
                saveCache(keyword, tmdb)
                return tmdb
            }
        }
        return null
    }

    // ───────────────────────── 两个数据源 ─────────────────────────

    private suspend fun lookupBangumi(keyword: String): MetaInfo? {
        val id = BangumiApi.searchId(keyword)
        if (id <= 0) return null
        val subject = BangumiApi.subject(id) ?: return null
        return MetaInfo(
            source = "bangumi",
            title = subject.title,
            overview = subject.summary,
            rating = subject.score,
            year = subject.year,
            poster = subject.poster?.takeIf { it.isNotBlank() },
            airDate = subject.date,
            // 人物接口是可选装饰，内部已静默失败，拿不到就是空表
            cast = BangumiApi.persons(id, CAST_LIMIT),
        )
    }

    private suspend fun lookupTmdb(keyword: String): MetaInfo? {
        // 没填 key 就完全不发请求，省一次必然失败的往返
        if (TmdbApi.apiKey().isEmpty()) return null
        val hit = TmdbApi.searchId(keyword) ?: return null
        return TmdbApi.detail(hit)
    }

    // ───────────────────────── 标题候选 ─────────────────────────

    private fun keywordsOf(item: MediaItem): List<String> {
        val raws = mutableListOf<String>()
        // 剧名优先：剧集条目的 title 往往只是集名
        if (item.tvTitle.isNotBlank()) raws += item.tvTitle
        if (item.title.isNotBlank()) raws += item.title

        val out = LinkedHashSet<String>()
        for (raw in raws) {
            val cleaned = cleanTitle(raw)
            if (cleaned.isNotEmpty()) out += cleaned
            // 清洗后仍带方括号/发布组标签时，再抠一段中文/日文当兜底候选
            val cjk = longestCjk(raw)
            if (cjk.isNotEmpty()) out += cjk
            if (out.size >= KEYWORD_MAX) break
        }
        return out.take(KEYWORD_MAX)
    }

    // 常见的几个正则，编译好复用（lookup 每次进详情页都会走到这里）
    private val EXT = Regex("\\.(mkv|mp4|avi|ts|m2ts|mov|wmv|flv|rmvb|webm|iso)$", RegexOption.IGNORE_CASE)
    private val CURLY_TAG = Regex("\\{[^}]*}")
    private val YEAR_IN_PARENS = Regex("[（(]\\s*(?:19|20)\\d{2}\\s*[)）]")
    private val EPISODE = Regex(
        "(?i)\\bS\\d{1,2}(?:E\\d{1,3})?\\b" +   // S01E02 / S2
            "|\\bEP?\\s?\\d{1,4}\\b" +          // E01 / EP12
            "|第\\s*\\d{1,4}\\s*[季集话話期]" +  // 第 3 集 / 第2季
            "|\\s[-–—]\\s*\\d{1,4}\\s*$"        // 「- 04」这种番剧尾巴
    )
    private val CJK_RUN = Regex("[\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff\\uff66-\\uff9f]{2,}")
    private val SPACES = Regex("\\s+")

    /** 把文件名/文件夹名收拾成像「进击的巨人」这样的搜索词。 */
    private fun cleanTitle(raw: String): String {
        var t = raw.trim()
        if (t.isEmpty()) return ""
        t = t.replace(EXT, "")
        t = t.replace(CURLY_TAG, " ")
        t = t.replace(YEAR_IN_PARENS, " ")
        t = t.replace(EPISODE, " ")
        t = t.replace('\u3000', ' ')
        t = t.replace(SPACES, " ")
        t = t.trim().trim('-', '_', '.', '·', '|', '/', ':', '：', '「', '」', '『', '』', ' ')
        return t.trim()
    }

    /** 名字里最长的一段中日文字符（>=2 字），没有则空串。 */
    private fun longestCjk(raw: String): String =
        CJK_RUN.findAll(raw).maxByOrNull { it.value.length }?.value?.trim().orEmpty()

    // ───────────────────────── 缓存 ─────────────────────────

    private fun cacheKey(keyword: String): String = keyword.trim().lowercase()

    private fun fromCache(keyword: String): MetaInfo? = synchronized(cache) {
        cache[cacheKey(keyword)]
    }

    private fun saveCache(keyword: String, info: MetaInfo) {
        synchronized(cache) {
            // 极简策略：满了直接清空重来，元数据丢了最多重新请求一次
            if (cache.size >= CACHE_MAX) cache.clear()
            cache[cacheKey(keyword)] = info
        }
    }

    /** 兜住数据层漏出来的异常，但绝不吞协程取消（详情页切走时要能立刻停）。 */
    private suspend fun guard(block: suspend () -> MetaInfo?): MetaInfo? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
