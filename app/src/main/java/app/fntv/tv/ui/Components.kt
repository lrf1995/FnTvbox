package app.fntv.tv.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fntv.tv.data.MediaItem
import app.fntv.tv.data.Repo
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/**
 * 请求焦点并重试：Lazy 列表子项要等 measure 才挂载，单次 requestFocus 经常落空，
 * 而一旦落空遥控器就像失灵（Google TV 不会自动把焦点塞进第一个可聚焦节点）。
 */
suspend fun requestFocusWhenReady(focusRequester: FocusRequester) {
    // Compose 1.7 的 requestFocus() 没有返回值，无法判断是否成功：
    // 连续请求几次（已聚焦时是幂等 no-op），覆盖 Lazy 子项尚未挂载的情况。
    repeat(3) { attempt ->
        runCatching { focusRequester.requestFocus() }
        delay(70L + attempt * 90L)
    }
}

/* ───────────────────────── 焦点态通用按钮 ───────────────────────── */

@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(23.dp)

    // 焦点态要一眼可见：primary 用 Accent 反色，普通按钮用 #232F45 底 + 白色 2dp 描边
    val bg by animateColorAsState(
        when {
            primary && focused -> Accent
            primary -> AccentSoft
            focused -> Color(0xFF232F45)
            else -> Color(0xFF1A2336)
        },
        label = "btnBg",
    )
    val fg = if (primary && focused) Color(0xFF04101F) else TextPrimary
    val borderColor by animateColorAsState(
        if (focused) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.10f),
        label = "btnBorderColor",
    )
    val borderWidth by animateDpAsState(if (focused) 2.dp else 1.dp, label = "btnBorderWidth")
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, label = "btnScale")

    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .height(46.dp)
            .clip(shape)
            .background(bg)
            .border(borderWidth, borderColor, shape)
            .onFocusChanged { focused = it.isFocused }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 26.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/* ───────────────────────── 海报卡 ───────────────────────── */

@Composable
fun PosterCard(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 150.dp,
    height: Dp = 214.dp,
    showTitle: Boolean = true,
    focusRequester: FocusRequester? = null,
    onFocused: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)

    val scale by animateFloatAsState(if (focused) 1.08f else 1f, label = "cardScale")
    val lift by animateDpAsState(if (focused) (-2).dp else 0.dp, label = "cardLift")
    val borderWidth by animateDpAsState(if (focused) 3.dp else 1.dp, label = "cardBorderWidth")
    val borderColor by animateColorAsState(
        if (focused) Accent else Color.White.copy(alpha = 0.06f),
        label = "cardBorderColor",
    )
    // 焦点时的一圈淡淡外发光；用 shadow 画在图层里，不参与测量，不会把卡片撑大
    val glowColor = if (focused) Accent.copy(alpha = 0.35f) else Color.Transparent

    Column(
        modifier = modifier.width(width + 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .width(width)
                .height(height)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationY = lift.toPx()
                }
                .shadow(
                    elevation = if (focused) 16.dp else 0.dp,
                    shape = shape,
                    ambientColor = glowColor,
                    spotColor = glowColor,
                )
                .clip(shape)
                .background(Surface2)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused?.invoke()
                }
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .clickable(onClick = onClick),
        ) {
            val url = Repo.imageUrl(item.poster, 400)
            // 加载态/失败态：Surface2 底 + 小转圈；失败或无图时退化成占位图标
            var loaded by remember(url) { mutableStateOf(url.isEmpty()) }
            var failed by remember(url) { mutableStateOf(false) }

            if (url.isNotEmpty()) {
                AsyncImage(
                    model = url,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    onLoading = {
                        loaded = false
                        failed = false
                    },
                    onSuccess = {
                        loaded = true
                        failed = false
                    },
                    onError = {
                        loaded = true
                        failed = true
                    },
                )
            }

            if (!loaded || failed) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Surface2),
                    contentAlignment = Alignment.Center,
                ) {
                    if (failed || url.isEmpty()) {
                        Icon(
                            Icons.Rounded.Movie,
                            contentDescription = null,
                            tint = TextDim,
                            modifier = Modifier.size(32.dp),
                        )
                    } else {
                        CircularProgressIndicator(
                            color = Accent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }

            // 底部渐变：透明 → 黑 75%
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(if (showTitle) height * 0.55f else 44.dp)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.45f to Color.Black.copy(alpha = 0.18f),
                            1f to Color.Black.copy(alpha = 0.75f),
                        )
                    )
            )

            // 渐变之上的标题（最多 2 行）+ 次要信息
            if (showTitle) {
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
                ) {
                    Text(
                        text = item.title,
                        color = if (focused) Color.White else TextPrimary.copy(alpha = 0.90f),
                        fontSize = 13.sp,
                        lineHeight = 16.sp,
                        fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.subtitle.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = item.subtitle,
                            color = if (focused) TextPrimary.copy(alpha = 0.85f) else TextSecondary,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // 左下进度条：稍微内缩，避免和焦点描边、标题叠在一起
            if (item.progress > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                        .fillMaxWidth(item.progress)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Accent)
                )
            }

            // 右上「已看」角标
            if (item.watched) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.62f))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text("已看", color = TextPrimary.copy(alpha = 0.92f), fontSize = 9.sp)
                }
            }

            // 描边做成覆盖层，保证压在图片上仍然可见：常态 1dp 白色 6%，焦点 3dp Accent
            Box(
                Modifier
                    .matchParentSize()
                    .border(borderWidth, borderColor, shape)
            )
        }
    }
}

/* ───────────────────────── 横排 ───────────────────────── */

@Composable
fun SectionRow(
    title: String,
    items: List<MediaItem>,
    onItemClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    firstCardFocus: FocusRequester? = null,
    onMore: (() -> Unit)? = null,
    moreLabel: String = "查看全部",
) {
    if (items.isEmpty()) return
    val state = rememberLazyListState()

    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 48.dp, end = 48.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Accent)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                title,
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrEmpty()) {
                Spacer(Modifier.width(10.dp))
                Text(subtitle, color = TextDim, fontSize = 12.sp, maxLines = 1)
            }
            if (onMore != null) {
                Spacer(Modifier.weight(1f))
                MoreButton(label = moreLabel, onClick = onMore)
            }
        }

        LazyRow(
            state = state,
            contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items, key = { it.guid.ifEmpty { "${it.title}-${it.hashCode()}" } }) { item ->
                PosterCard(
                    item = item,
                    onClick = { onItemClick(item) },
                    focusRequester = if (items.firstOrNull()?.guid == item.guid) firstCardFocus else null,
                )
            }
        }
    }
}

/** 标题行右侧的「查看全部 ›」文字按钮，只在传了 onMore 时出现。 */
@Composable
private fun MoreButton(label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    val bg by animateColorAsState(
        if (focused) Color(0xFF232F45) else Color.Transparent,
        label = "moreBg",
    )
    val fg by animateColorAsState(
        if (focused) Color.White else TextSecondary,
        label = "moreFg",
    )
    val borderColor by animateColorAsState(
        if (focused) Color.White.copy(alpha = 0.92f) else Color.Transparent,
        label = "moreBorderColor",
    )

    Row(
        modifier = Modifier
            .height(34.dp)
            .clip(shape)
            .background(bg)
            .border(if (focused) 2.dp else 1.dp, borderColor, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        Spacer(Modifier.width(4.dp))
        Text("›", color = if (focused) Accent else fg, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

/* ───────────────────────── 状态占位 ───────────────────────── */

@Composable
fun LoadingBox(text: String = "正在连接飞牛影视…") {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 48.dp, vertical = 40.dp),
        ) {
            Text(
                "FnTV",
                color = Accent,
                fontSize = 30.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(22.dp))
            CircularProgressIndicator(
                color = Accent,
                strokeWidth = 2.5.dp,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.height(22.dp))
            Text(text, color = TextSecondary, fontSize = 14.sp)
        }
    }
}

@Composable
fun ErrorBox(message: String, onRetry: (() -> Unit)? = null) {
    val retryFocus = remember { FocusRequester() }
    // 错误页常常是首屏，必须自己把焦点抓到「重试」，否则遥控器按不到任何东西
    LaunchedEffect(message, onRetry) {
        if (onRetry != null) requestFocusWhenReady(retryFocus)
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 48.dp),
        ) {
            Text("出错了", color = Danger, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(
                message,
                color = TextSecondary,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 560.dp),
            )
            if (onRetry != null) {
                Spacer(Modifier.height(22.dp))
                TvButton("重试", onRetry, primary = true, focusRequester = retryFocus)
            }
        }
    }
}

@Composable
fun EmptyBox(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Rounded.Movie,
                contentDescription = null,
                tint = TextDim.copy(alpha = 0.75f),
                modifier = Modifier.size(42.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text(text, color = TextDim, fontSize = 15.sp, textAlign = TextAlign.Center)
        }
    }
}