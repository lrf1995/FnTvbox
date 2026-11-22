package app.fntv.tv.ui

import androidx.compose.animation.animateColorAsState
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
    val bg by animateColorAsState(
        when {
            primary && focused -> Accent
            primary -> AccentSoft
            focused -> Color(0xFF2B3A58)
            else -> Color(0xFF1A2336)
        },
        label = "btnBg",
    )
    val fg = if (primary && focused) Color(0xFF04101F) else TextPrimary
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, label = "btnScale")

    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .height(46.dp)
            .clip(RoundedCornerShape(23.dp))
            .background(bg)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.10f),
                shape = RoundedCornerShape(23.dp),
            )
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
    width: Dp = 116.dp,
    height: Dp = 166.dp,
    showTitle: Boolean = true,
    focusRequester: FocusRequester? = null,
    onFocused: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.07f else 1f, label = "cardScale")
    val borderColor by animateColorAsState(
        if (focused) Accent else Color.White.copy(alpha = 0.08f),
        label = "cardBorder",
    )

    Column(
        modifier = modifier.width(width + 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .width(width)
                .height(height)
                .clip(RoundedCornerShape(9.dp))
                .background(Surface2)
                .border(if (focused) 3.dp else 1.dp, borderColor, RoundedCornerShape(9.dp))
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused?.invoke()
                }
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .clickable(onClick = onClick),
        ) {
            val url = Repo.imageUrl(item.poster, 400)
            if (url.isNotEmpty()) {
                AsyncImage(
                    model = url,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.Movie,
                        contentDescription = null,
                        tint = TextDim,
                        modifier = Modifier.size(34.dp),
                    )
                }
            }

            // 底部渐变 + 进度条
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(46.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))
                        )
                    )
            )
            if (item.progress > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(item.progress)
                        .height(4.dp)
                        .background(Accent)
                )
            }
            if (item.watched) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.62f))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text("已看", color = TextSecondary, fontSize = 10.sp)
                }
            }
        }

        if (showTitle) {
            Spacer(Modifier.height(7.dp))
            Text(
                text = item.title,
                color = if (focused) TextPrimary else TextSecondary,
                fontSize = 12.sp,
                fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
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
                    .height(18.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Accent)
            )
            Spacer(Modifier.width(10.dp))
            Text(title, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            if (!subtitle.isNullOrEmpty()) {
                Spacer(Modifier.width(10.dp))
                Text(subtitle, color = TextDim, fontSize = 12.sp)
            }
        }

        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
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

/* ───────────────────────── 状态占位 ───────────────────────── */

@Composable
fun LoadingBox(text: String = "正在连接飞牛影视…") {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Accent, strokeWidth = 3.dp)
            Spacer(Modifier.height(16.dp))
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
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("出错了", color = Danger, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(
                message,
                color = TextSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(520.dp),
            )
            if (onRetry != null) {
                Spacer(Modifier.height(20.dp))
                TvButton("重试", onRetry, primary = true, focusRequester = retryFocus)
            }
        }
    }
}

@Composable
fun EmptyBox(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = TextDim, fontSize = 15.sp)
    }
}