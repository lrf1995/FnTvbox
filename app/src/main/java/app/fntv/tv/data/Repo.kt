package app.fntv.tv.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

data class HomeRow(val lib: Lib, val items: List<MediaItem>)

data class HomeData(
    val libs: List<Lib>,
    val continueWatching: List<MediaItem>,
    val rows: List<HomeRow>,
)

/**
 * 业务层：会话状态 + 首页数据缓存。
 * 所有屏幕都从这里取数据，避免每块 UI 自己拼请求。
 */
object Repo {
    @Volatile
    var server: String = ""
        private set

    @Volatile
    var token: String = ""
        private set

    @Volatile
    var username: String = ""
        private set

    val isLoggedIn: Boolean get() = server.isNotEmpty() && token.isNotEmpty()

    private var homeCache: HomeData? = null
    private val itemCache = HashMap<String, MutableList<MediaItem>>()
    private val pageCache = HashMap<String, Int>()

    fun initFromPrefs() {
        server = Prefs.server
        token = Prefs.token
        username = Prefs.username
    }

    suspend fun login(serverInput: String, user: String, pass: String) {
        val base = FnApi.normalizeServer(serverInput)
        val newToken = FnApi.login(base, user.trim(), pass)
        // 登录成功后请求一次 user/info，让 fnOS 的 302（80 → 实际端口）落到真实基址
        val info = runCatching { FnApi.userInfo(base, newToken) }.getOrNull()
        val finalBase = if (info != null && FnApi.lastBase.isNotEmpty()) FnApi.lastBase else base
        server = finalBase
        token = newToken
        username = user.trim()
        Prefs.server = finalBase
        Prefs.token = newToken
        Prefs.username = username
        invalidate()
    }

    fun logout() {
        token = ""
        Prefs.clearLogin()
        invalidate()
    }

    fun invalidate() {
        homeCache = null
        itemCache.clear()
        pageCache.clear()
    }

    suspend fun loadHome(force: Boolean = false): HomeData {
        homeCache?.let { if (!force) return it }
        val libsJson = FnApi.mediaLibs(server, token)
        val libs = (0 until libsJson.length()).mapNotNull { i ->
            libsJson.optJSONObject(i)?.let(Lib::from)?.takeIf { it.guid.isNotEmpty() }
        }

        val continueJson = runCatching { FnApi.playList(server, token) }.getOrDefault(org.json.JSONArray())
        val continueItems = (0 until continueJson.length()).mapNotNull { i ->
            val o = continueJson.optJSONObject(i) ?: return@mapNotNull null
            MediaItem.from(o).takeIf { it.guid.isNotEmpty() }
        }.filter { it.type != "LiveChannel" }.take(20)

        val rows = coroutineScope {
            libs.map { lib ->
                async {
                    val items = runCatching { loadItems(lib.guid) }.getOrDefault(emptyList())
                    HomeRow(lib, items)
                }
            }.map { it.await() }
        }.filter { it.items.isNotEmpty() }

        val data = HomeData(libs, continueItems, rows)
        homeCache = data
        return data
    }

    /** 媒体库/文件夹分页加载；页大小 60，与桌面端一致。
     *
     * 注意：第 1 页**总是回源**。早期版本在这里命中本地缓存直接返回整桶，
     * 会让「返回再进库」时页码与缓存错位，重复追加同一页海报（重复 key/重复卡片）。
     */
    suspend fun loadItems(ancestorGuid: String, page: Int = 1, parentGuid: String? = null): List<MediaItem> {
        val key = parentGuid ?: ancestorGuid
        val json = FnApi.itemList(
            server, token,
            ancestorGuid = if (parentGuid == null) ancestorGuid else null,
            parentGuid = parentGuid,
            page = page,
            pageSize = 60,
        )
        val arr = json.optJSONArray("list") ?: org.json.JSONArray()
        val items = (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let(MediaItem::from)?.takeIf { it.guid.isNotEmpty() && it.title.isNotEmpty() }
        }
        val total = json.optInt("total", 0)
        val bucket = itemCache.getOrPut(key) { mutableListOf() }
        if (page == 1) bucket.clear()
        bucket.addAll(items)
        pageCache[key] = page
        if (total > 0 && bucket.size >= total) pageCache[key] = -1
        return items
    }

    fun cachedItems(key: String): List<MediaItem> = itemCache[key].orEmpty()
    fun hasMore(key: String): Boolean = pageCache[key] != -1
    fun currentPage(key: String): Int = pageCache[key] ?: 0

    suspend fun seasons(seriesGuid: String): List<Season> {
        val arr = FnApi.seasons(server, token, seriesGuid)
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let(Season::from)?.takeIf { it.guid.isNotEmpty() }
        }.sortedBy { it.number }
    }

    suspend fun episodes(seasonGuid: String): List<Episode> {
        val arr = FnApi.episodes(server, token, seasonGuid)
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let(Episode::from)?.takeIf { it.guid.isNotEmpty() }
        }.sortedBy { it.number }
    }

    suspend fun search(query: String): List<MediaItem> {
        if (query.isBlank()) return emptyList()
        val arr = FnApi.search(server, token, query.trim())
        val list = (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { raw ->
                // 搜索结果可能把条目包在 item 里
                val o = raw.optJSONObject("item") ?: raw
                MediaItem.from(o).takeIf { it.guid.isNotEmpty() }
            }
        }
        return list.distinctBy { it.guid }
    }

    suspend fun playInfo(itemGuid: String, fallbackTitle: String): PlayInfo {
        val json = FnApi.playInfo(server, token, itemGuid)
        return PlayInfo.from(json, fallbackTitle)
    }

    fun streamUrl(mediaGuid: String): String = FnApi.streamUrl(server, mediaGuid)
    fun imageUrl(path: String?, width: Int = 500): String = FnApi.imageUrl(server, path, width)
}