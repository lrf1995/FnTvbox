package app.fntv.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fntv.tv.data.FnException
import app.fntv.tv.data.HomeData
import app.fntv.tv.data.Lib
import app.fntv.tv.data.MediaItem
import app.fntv.tv.data.Repo
import app.fntv.tv.ui.Bg
import app.fntv.tv.ui.ErrorBox
import app.fntv.tv.ui.LoadingBox
import app.fntv.tv.ui.SectionRow
import app.fntv.tv.ui.TextDim
import app.fntv.tv.ui.TvButton
import app.fntv.tv.ui.requestFocusWhenReady

private fun categoryLabel(category: String): String = when (category.lowercase()) {
    "movie" -> "电影"
    "tv" -> "电视剧"
    "video" -> "其他视频"
    "livechannel" -> "电视直播"
    "anime" -> "动漫"
    else -> category.ifEmpty { "媒体库" }
}

/** 首屏空数据：给一个能聚焦的「重新加载」，否则遥控器会完全没有落点。 */
@Composable
private fun HomeEmptyState(onRetry: () -> Unit, focusRequester: FocusRequester) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("媒体库里还没有可展示的内容", color = TextDim, fontSize = 15.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "先在飞牛影视里添加媒体库或完成影片扫描，再回来重新加载",
                color = TextDim,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(18.dp))
            TvButton(
                text = "重新加载",
                onClick = onRetry,
                primary = true,
                focusRequester = focusRequester,
            )
        }
    }
}

/** 有数据时的首页：顶部 Hero 轮播 + 继续观看 + 每个媒体库一行。 */
@Composable
private fun HomeContent(
    home: HomeData,
    playFocus: FocusRequester,
    onOpenItem: (MediaItem) -> Unit,
    onOpenLib: (Lib) -> Unit,
) {
    val heroItems = remember(home) { buildHeroItems(home) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 内容区已扣掉 58dp 顶栏：这里取内容区的 55%，换算到整屏约 46%~52%
        val heroHeight = maxHeight * 0.55f

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 44.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            if (heroItems.isNotEmpty()) {
                item(key = "hero") {
                    HomeHero(
                        items = heroItems,
                        height = heroHeight,
                        onOpenItem = onOpenItem,
                        playFocus = playFocus,
                    )
                }
            }

            if (home.continueWatching.isNotEmpty()) {
                item(key = "continue") {
                    // 继续观看：行内卡片由 PosterCard 自动画进度条
                    SectionRow(
                        title = "继续观看",
                        subtitle = "接着上次的进度",
                        items = home.continueWatching,
                        onItemClick = onOpenItem,
                    )
                }
            }

            // 媒体库行：直接铺 Repo 给的完整 items，右侧「查看全部 ›」进库
            items(home.rows, key = { "lib-${it.lib.guid}" }) { row ->
                SectionRow(
                    title = row.lib.title,
                    subtitle = categoryLabel(row.lib.category),
                    items = row.items,
                    onItemClick = onOpenItem,
                    onMore = { onOpenLib(row.lib) },
                )
            }
        }
    }
}

@Composable
fun HomeScreen(
    refreshTick: Int,
    onOpenItem: (MediaItem) -> Unit,
    onOpenLib: (Lib) -> Unit,
    onSessionExpired: () -> Unit,
) {
    var data by remember { mutableStateOf<HomeData?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val playFocus = remember { FocusRequester() }
    val emptyFocus = remember { FocusRequester() }
    // 上一次首屏形态：null=还没放过焦点，false=空数据，true=有数据
    var lastHadContent by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(refreshTick, tick) {
        loading = true
        error = null
        try {
            data = Repo.loadHome(force = tick > 0)
        } catch (e: Exception) {
            if ((e as? FnException)?.code == -2) onSessionExpired()
            error = e.message ?: "加载失败"
        } finally {
            loading = false
        }
    }

    // 首屏焦点：有内容落在 Hero 的「播放」上；空数据落在「重新加载」上。
    // 只有形态变化（空 ↔ 有内容）时才重新抓焦点，播放返回/刷新数据不抢。
    LaunchedEffect(data) {
        val home = data ?: return@LaunchedEffect
        val hasContent = home.continueWatching.isNotEmpty() || home.rows.isNotEmpty()
        if (hasContent == lastHadContent) return@LaunchedEffect
        lastHadContent = hasContent
        requestFocusWhenReady(if (hasContent) playFocus else emptyFocus)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Bg, Color(0xFF05070C))))
    ) {
        val home = data
        if (home == null) {
            if (loading) LoadingBox()
            else ErrorBox(error ?: "加载失败，请重试") { tick++ }
        } else if (home.continueWatching.isEmpty() && home.rows.isEmpty()) {
            HomeEmptyState(
                onRetry = { tick++ },
                focusRequester = emptyFocus,
            )
        } else {
            HomeContent(
                home = home,
                playFocus = playFocus,
                onOpenItem = onOpenItem,
                onOpenLib = onOpenLib,
            )
        }
    }
}