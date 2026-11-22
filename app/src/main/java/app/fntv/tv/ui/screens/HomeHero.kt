package app.fntv.tv.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fntv.tv.data.HomeData
import app.fntv.tv.data.MediaItem
import app.fntv.tv.data.Repo
import app.fntv.tv.ui.Accent
import app.fntv.tv.ui.Bg
import app.fntv.tv.ui.Gold
import app.fntv.tv.ui.TextDim
import app.fntv.tv.ui.TextPrimary
import app.fntv.tv.ui.TextSecondary
import app.fntv.tv.ui.TvButton
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/** Hero 轮播最多展示的条数。 */
private const val HERO_MAX = 5

/**
 * Hero 数据来源（按优先级）：
 * 1. 「继续观看」优先——回到首页最想接着看的就是它们；
 * 2. 不够 [HERO_MAX] 个时，按媒体库顺序（第一个库在前）用库内条目补足；
 * 3. 跳过文件夹（封面/播放入口都不合适），海报为空也保留：
 *    背景退化成人造深色渐变，文字仍有遮罩保证可读。
 */
fun buildHeroItems(home: HomeData): List<MediaItem> {
    val picked = LinkedHashMap<String, MediaItem>()
    fun add(item: MediaItem) {
        if (picked.size >= HERO_MAX) return
        if (item.guid.isEmpty() || picked.containsKey(item.guid)) return
        picked[item.guid] = item
    }

    home.continueWatching.forEach { add(it) }
    if (picked.size < HERO_MAX) {
        for (row in home.rows) {
            for (item in row.items) {
                if (item.isFolder) continue
                add(item)
                if (picked.size >= HERO_MAX) break
            }
            if (picked.size >= HERO_MAX) break
        }
    }

    // 极端情况：库里全是文件夹（都被跳过），仍留一个 Hero，否则首屏焦点会没有落点
    if (picked.isEmpty()) {
        val fallback = home.rows.asSequence()
            .flatMap { it.items.asSequence() }
            .firstOrNull { it.guid.isNotEmpty() }
        if (fallback != null) picked[fallback.guid] = fallback
    }
    return picked.values.toList()
}

/** Hero 背景：海报按 [MediaItem.poster] 取 900 宽版本，铺满裁切；无海报时用深色渐变兜底。 */
@Composable
private fun HeroBackdrop(item: MediaItem) {
    val url = Repo.imageUrl(item.poster, 900)
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(listOf(Color(0xFF101A2C), Color(0xFF070B14)))
            ),
    ) {
        if (url.isNotEmpty()) {
            AsyncImage(
                model = url,
                // 标题已在画面上，背景图对读屏是装饰
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 评分 + 年份/季集数/进度，一行元信息。 */
@Composable
private fun HeroMeta(item: MediaItem) {
    val extra = buildList {
        item.subtitle.takeIf { it.isNotEmpty() }?.let { add(it) }
        when {
            item.watched -> add("已看完")
            item.progress > 0f -> add("已看 ${(item.progress * 100).toInt()}%")
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        if (item.rating > 0.0) {
            Text("★", color = Gold, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(4.dp))
            Text(
                String.format("%.1f", item.rating),
                color = Gold,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            if (extra.isNotEmpty()) {
                Spacer(Modifier.width(12.dp))
                Text("·", color = TextDim, fontSize = 13.sp)
                Spacer(Modifier.width(12.dp))
            }
        }
        if (extra.isNotEmpty()) {
            Text(
                extra.joinToString(" · "),
                color = TextSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 首页顶部沉浸式 Hero 轮播。
 *
 * - 高度由调用方按屏高比例传入，写在内容区顶部（不碰 MainActivity 的 TopBar）；
 * - 每 8 秒自动切换，底部小圆点指示当前位；
 * - [playFocus] 交给「播放」按钮，首屏焦点由 HomeScreen 请求。
 */
@Composable
fun HomeHero(
    items: List<MediaItem>,
    height: Dp,
    onOpenItem: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    playFocus: FocusRequester? = null,
) {
    if (items.isEmpty()) return

    var index by remember(items) { mutableIntStateOf(0) }
    val safeIndex = index.coerceIn(0, items.lastIndex)
    val current = items[safeIndex]

    // 8 秒自动切换；列表只有一条时保持不动
    LaunchedEffect(items.size) {
        if (items.size <= 1) return@LaunchedEffect
        while (true) {
            delay(8_000L)
            index = (index + 1) % items.size
        }
    }

    Box(modifier.fillMaxWidth().height(height)) {
        /* ── 背景：海报铺满，切换时淡入淡出 ── */
        Crossfade(
            targetState = current.guid,
            animationSpec = tween(700),
            label = "heroBackdrop",
        ) { guid ->
            // 渐变期间旧内容也要有对应条目，找不到就退回当前条目
            val shown = items.firstOrNull { it.guid == guid } ?: current
            HeroBackdrop(shown)
        }

        /* ── 遮罩：左黑→透明，保证标题与简介的可读性 ── */
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Bg.copy(alpha = 0.97f),
                        0.34f to Bg.copy(alpha = 0.72f),
                        0.66f to Color.Transparent,
                        1f to Bg.copy(alpha = 0.35f),
                    )
                )
        )
        /* ── 遮罩：底部黑→透明，让 Hero 与下方海报行无缝衔接 ── */
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.45f to Color.Transparent,
                        0.78f to Bg.copy(alpha = 0.62f),
                        1f to Bg,
                    )
                )
        )
        /* ── 遮罩：顶部轻微压暗，避免和顶栏之间出现硬边 ── */
        Box(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Bg.copy(alpha = 0.72f), Color.Transparent)
                    )
                )
        )

        /* ── 文字与按钮 ── */
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(0.62f)
                .padding(start = 48.dp, end = 24.dp, bottom = 24.dp),
        ) {
            Text(
                text = current.title,
                color = TextPrimary,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 38.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            HeroMeta(current)
            Spacer(Modifier.height(8.dp))
            Text(
                text = current.overview.ifBlank { "暂无简介" },
                color = TextSecondary,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(14.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 首页对外只暴露 onOpenItem：MainActivity 会按类型进详情/文件夹，
                // 详情页首焦点就是「播放」，所以这里两个按钮都落在同一条路由上。
                TvButton(
                    text = "▶ 播放",
                    onClick = { onOpenItem(current) },
                    primary = true,
                    focusRequester = playFocus,
                )
                TvButton("详情", onClick = { onOpenItem(current) })
            }
        }

        /* ── 底部小圆点：当前位变成加长胶囊 ── */
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 48.dp, bottom = 34.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { i, _ ->
                val active = i == safeIndex
                val dotWidth by animateDpAsState(
                    targetValue = if (active) 20.dp else 6.dp,
                    label = "heroDot",
                )
                Box(
                    Modifier
                        .width(dotWidth)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(
                            if (active) Accent else Color.White.copy(alpha = 0.32f)
                        )
                )
            }
        }
    }
}