package app.fntv.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.fntv.tv.data.Lib
import app.fntv.tv.data.MediaItem
import app.fntv.tv.data.PlayRequest
import app.fntv.tv.data.Repo
import app.fntv.tv.player.PlayerActivity
import app.fntv.tv.ui.Bg
import app.fntv.tv.ui.FnTvTheme
import app.fntv.tv.ui.TopBar
import app.fntv.tv.ui.screens.DetailScreen
import app.fntv.tv.ui.screens.HomeScreen
import app.fntv.tv.ui.screens.LibraryScreen
import app.fntv.tv.ui.screens.LoginScreen
import app.fntv.tv.ui.screens.SearchScreen
import app.fntv.tv.ui.screens.SettingsScreen
import org.json.JSONArray
import org.json.JSONObject

/**
 * 路由：根页面（首页/搜索/设置）在顶栏切换，媒体库/详情通过返回栈压入。
 */
sealed interface Route {
    object Home : Route

    /** 媒体库或库内文件夹；[parentGuid] 为空表示库根目录。 */
    data class Library(
        val lib: Lib,
        val parentGuid: String?,
        val title: String,
    ) : Route

    data class Detail(val item: MediaItem) : Route

    object Search : Route

    object Settings : Route
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FnTvTheme {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot() {
    var loggedIn by remember { mutableStateOf(Repo.isLoggedIn) }

    // 未登录：只有登录页，没有顶栏与路由
    if (!loggedIn) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Bg)
        ) {
            LoginScreen(onLogged = { loggedIn = true })
        }
        return
    }

    val stack = remember { mutableStateListOf<Route>(Route.Home) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var detailTick by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    fun push(route: Route) {
        stack.add(route)
    }

    fun resetTo(route: Route) {
        stack.clear()
        stack.add(route)
    }

    /** 会话过期 / 主动退出：清空路由并回登录页。 */
    fun backToLogin() {
        stack.clear()
        stack.add(Route.Home)
        Repo.logout()
        loggedIn = false
    }

    /**
     * 海报点击：文件夹进媒体库，普通条目进详情。
     * 首页「继续观看」拿不到所属媒体库，此时用条目自身 guid 兜底（只可能来自库内条目）。
     */
    fun openItem(item: MediaItem, lib: Lib?) {
        if (item.isFolder) {
            val owner = lib ?: Lib(
                guid = item.guid,
                title = item.title,
                category = "",
                poster = item.poster,
            )
            push(Route.Library(owner, item.guid, item.title))
        } else {
            push(Route.Detail(item))
        }
    }

    // 播放页返回：刷新首页「继续观看」与详情页进度；会话过期则回登录页
    val playerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == PlayerActivity.RESULT_SESSION_EXPIRED) {
            backToLogin()
        } else if (result.resultCode != android.app.Activity.RESULT_CANCELED) {
            Repo.invalidate()
            refreshTick++
            detailTick++
        }
    }

    val current = stack.lastOrNull() ?: Route.Home

    // 返回键：先退栈；搜索/设置回到首页；首页交给系统（退出应用）
    BackHandler(enabled = true) {
        when {
            stack.size > 1 -> pop()
            current != Route.Home -> resetTo(Route.Home)
            else -> (context as? android.app.Activity)?.finish()
        }
    }
    val isRoot = current == Route.Home || current == Route.Search || current == Route.Settings
    val tab = when (current) {
        Route.Home -> 0
        Route.Search -> 1
        Route.Settings -> 2
        else -> 0
    }
    val title: String? = when (current) {
        is Route.Library -> current.title
        is Route.Detail -> current.item.title
        else -> null
    }
    val popOnce: () -> Unit = { pop() }
    val onBack: (() -> Unit)? = if (isRoot) null else popOnce

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
    ) {
        TopBar(
            tab = tab,
            onTab = { index ->
                resetTo(
                    when (index) {
                        1 -> Route.Search
                        2 -> Route.Settings
                        else -> Route.Home
                    }
                )
            },
            showTabs = isRoot,
            title = title,
            onBack = onBack,
        )

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (val route = current) {
                Route.Home -> HomeScreen(
                    refreshTick = refreshTick,
                    onOpenItem = { item -> openItem(item, null) },
                    onOpenLib = { lib -> push(Route.Library(lib, null, lib.title)) },
                    onSessionExpired = { backToLogin() },
                )

                is Route.Library -> LibraryScreen(
                    lib = route.lib,
                    parentGuid = route.parentGuid,
                    title = route.title,
                    onOpenItem = { item -> openItem(item, route.lib) },
                    onOpenFolder = { folder ->
                        push(Route.Library(route.lib, folder.guid, folder.title))
                    },
                    onSessionExpired = { backToLogin() },
                )

                is Route.Detail -> DetailScreen(
                    item = route.item,
                    onPlay = { request -> playerLauncher.launch(buildPlayerIntent(context, request)) },
                    onSessionExpired = { backToLogin() },
                    refreshTick = detailTick,
                )

                Route.Search -> SearchScreen(
                    onOpenItem = { item -> openItem(item, null) },
                    onSessionExpired = { backToLogin() },
                )

                Route.Settings -> SettingsScreen(
                    onLoggedOut = { backToLogin() },
                    onRefreshHome = {
                        // 设置里改了会影响首页的选项（如自动跳片头），让首页下次进入重新拉数据
                        Repo.invalidate()
                        refreshTick++
                    },
                )
            }
        }
    }
}

/** 把剧集列表序列化成 JSON 交给 PlayerActivity（extras: episodes / index / series_title）。 */
private fun buildPlayerIntent(context: Context, request: PlayRequest): Intent {
    val episodes = JSONArray()
    request.episodes.forEach { ep ->
        episodes.put(
            JSONObject()
                .put("guid", ep.guid)
                .put("title", ep.title)
                .put("number", ep.number)
        )
    }

    return Intent(context, PlayerActivity::class.java).apply {
        putExtra("episodes", episodes.toString())
        putExtra("index", request.startIndex)
        putExtra("series_title", request.seriesTitle)
    }
}