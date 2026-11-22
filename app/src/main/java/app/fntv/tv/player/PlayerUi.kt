package app.fntv.tv.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.TrackGroup
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.fntv.tv.data.DanmakuComment
import app.fntv.tv.data.Prefs
import app.fntv.tv.ui.Accent
import app.fntv.tv.ui.Bg
import app.fntv.tv.ui.Danger
import app.fntv.tv.ui.Surface1
import app.fntv.tv.ui.Surface2
import app.fntv.tv.ui.TextDim
import app.fntv.tv.ui.TextPrimary
import app.fntv.tv.ui.TextSecondary
import app.fntv.tv.ui.TvButton
import app.fntv.tv.ui.player.DanmakuOverlay
import kotlinx.coroutines.delay
import java.util.Locale

/* ───────────────────────── 页面状态 ───────────────────────── */

/** 覆盖层：没有弹层 / 音轨列表 / 字幕列表。 */
internal enum class PlayerOverlay { NONE, AUDIO, SUBTITLE }

/** 底部控制条上的可选中动作。 */
internal enum class PlayerAction {
    BACK, PLAY_PAUSE, REWIND, FORWARD, NEXT, AUDIO, SUBTITLE, DANMAKU, SPEED, EXIT
}

/**
 * 遥控器上下移动焦点的顺序：返回到最上面，其余按底部一排从左到右。
 * 左右键始终是快退/快进 10 秒，所以焦点移动只挂在上/下键上。
 */
internal val PlayerActionOrder = listOf(
    PlayerAction.BACK,
    PlayerAction.PLAY_PAUSE,
    PlayerAction.REWIND,
    PlayerAction.FORWARD,
    PlayerAction.NEXT,
    PlayerAction.AUDIO,
    PlayerAction.SUBTITLE,
    PlayerAction.DANMAKU,
    PlayerAction.SPEED,
    PlayerAction.EXIT,
)

/** 一条可选的音轨/字幕。group 为 null 表示「关闭字幕」这种伪选项。 */
internal data class TrackOption(
    val label: String,
    val detail: String,
    val selected: Boolean,
    val group: TrackGroup?,
    val trackIndex: Int,
    val disableType: Boolean = false,
)

/** 播放页全部可观察状态，由 PlayerActivity 持有并改写。 */
internal class PlayerUiState {
    var seriesTitle by mutableStateOf("")
    var episodeTitle by mutableStateOf("")
    var episodeIndex by mutableIntStateOf(0)
    var episodeCount by mutableIntStateOf(0)
    var hasNext by mutableStateOf(false)

    var controlsVisible by mutableStateOf(true)
    var selection by mutableStateOf(PlayerAction.PLAY_PAUSE)
    /** 每次用户操作自增，用来重置 5 秒自动隐藏计时。 */
    var tick by mutableIntStateOf(0)

    var isPlaying by mutableStateOf(false)
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var speed by mutableFloatStateOf(1.0f)
    var subtitleOn by mutableStateOf(false)

    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    /** 错误页选中项：0 重试，1 返回。 */
    var errorChoice by mutableIntStateOf(0)

    var overlay by mutableStateOf(PlayerOverlay.NONE)
    var trackOptions by mutableStateOf<List<TrackOption>>(emptyList())
    var trackSelected by mutableIntStateOf(0)

    /** 弹幕总开关：初始值取设置页里的偏好，控制条上可以直接切。 */
    var danmakuOn by mutableStateOf(Prefs.danmakuEnabled)
    /** 本集弹幕（已按时间升序）；空列表就是没弹幕，画层会自动留空。 */
    var danmaku by mutableStateOf<List<DanmakuComment>>(emptyList())
    /** 正在拉弹幕：控制条上给一行「弹幕加载中」。 */
    var danmakuLoading by mutableStateOf(false)
    /** 一行提示：例如「未找到弹幕」，只在控制条上短暂显示，不弹错误框。 */
    var danmakuHint by mutableStateOf("")
}

/* ───────────────────────── 页面骨架 ───────────────────────── */

@Composable
internal fun PlayerScreen(
    player: ExoPlayer,
    ui: PlayerUiState,
    onKeyEvent: (KeyEvent) -> Boolean,
    onAction: (PlayerAction) -> Unit,
    onSelectTrack: (Int) -> Unit,
    onCloseOverlay: () -> Unit,
    onErrorChoice: (Int) -> Unit,
) {
    val rootFocus = remember { FocusRequester() }

    // 根节点常驻焦点：所有遥控器按键都先经 onKeyEvent 统一分发，避免和按钮焦点抢占。
    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }
    LaunchedEffect(ui.overlay, ui.error) { runCatching { rootFocus.requestFocus() } }

    // 控制条 5 秒无操作自动隐藏；弹层打开时不隐藏。
    LaunchedEffect(ui.controlsVisible, ui.tick, ui.overlay) {
        if (ui.controlsVisible && ui.overlay == PlayerOverlay.NONE) {
            delay(5_000L)
            if (ui.overlay == PlayerOverlay.NONE) {
                ui.controlsVisible = false
                ui.selection = PlayerAction.PLAY_PAUSE
            }
        }
    }

    // 「未找到弹幕」这类提示只短暂停在控制条上，避免整集都挂着
    LaunchedEffect(ui.danmakuHint) {
        if (ui.danmakuHint.isNotBlank()) {
            delay(4_000L)
            ui.danmakuHint = ""
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent(onKeyEvent),
    ) {
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    setUseController(false)
                    setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    isFocusable = false
                    setPlayer(player)
                }
            },
            update = { view -> if (view.player !== player) view.setPlayer(player) },
            modifier = Modifier.fillMaxSize(),
        )

        // 弹幕层：在 PlayerView 之上、控制条之下。关掉时画层自己留空，不改变布局。
        DanmakuLayer(ui = ui)

        if (ui.loading && ui.error == null) LoadingPane()

        AnimatedVisibility(
            visible = ui.controlsVisible && ui.error == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            PlayerTopBar(ui = ui, onAction = onAction)
        }

        AnimatedVisibility(
            visible = ui.controlsVisible && ui.error == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            PlayerBottomBar(ui = ui, onAction = onAction)
        }

        if (ui.overlay != PlayerOverlay.NONE) {
            TrackPane(ui = ui, onSelectTrack = onSelectTrack, onClose = onCloseOverlay)
        }

        val error = ui.error
        if (error != null) {
            ErrorPane(ui = ui, message = error, onChoose = onErrorChoice)
        }
    }
}

/**
 * 弹幕层单独包一个 composable：进度每 500ms 变一次，让这层自己重组就够，
 * 不要带着整个播放页（顶栏/底栏/PlayerView）一起重组。
 */
@Composable
private fun DanmakuLayer(ui: PlayerUiState) {
    DanmakuOverlay(
        comments = ui.danmaku,
        positionMs = ui.positionMs,
        enabled = ui.danmakuOn,
        modifier = Modifier.fillMaxSize(),
    )
}

/* ───────────────────────── 顶部 ───────────────────────── */

@Composable
private fun PlayerTopBar(ui: PlayerUiState, onAction: (PlayerAction) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.Black.copy(alpha = 0.82f),
                        Color.Black.copy(alpha = 0.38f),
                        Color.Transparent,
                    )
                )
            )
            .padding(start = 32.dp, end = 32.dp, top = 22.dp, bottom = 30.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SelectableSlot(selected = ui.selection == PlayerAction.BACK) {
                TvButton("‹ 返回", onClick = { onAction(PlayerAction.BACK) })
            }
            Spacer(Modifier.width(22.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = ui.seriesTitle.ifBlank { ui.episodeTitle },
                    color = TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 电影/单集时两行标题会重复，只有剧集或标题不同才显示第二行
                val showEpisodeLine = ui.episodeCount > 1 ||
                    (ui.episodeTitle.isNotBlank() && ui.episodeTitle != ui.seriesTitle)
                if (showEpisodeLine) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = buildString {
                            append(ui.episodeTitle)
                            if (ui.episodeCount > 1) {
                                append("   ·   第 ${ui.episodeIndex + 1}/${ui.episodeCount} 集")
                            }
                        },
                        color = TextSecondary,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!ui.isPlaying && !ui.loading) {
                Spacer(Modifier.width(16.dp))
                Text("已暂停", color = TextDim, fontSize = 15.sp)
            }
        }
    }
}

/* ───────────────────────── 底部 ───────────────────────── */

@Composable
private fun PlayerBottomBar(ui: PlayerUiState, onAction: (PlayerAction) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.58f),
                        Color.Black.copy(alpha = 0.93f),
                    )
                )
            )
            .padding(start = 32.dp, end = 32.dp, top = 46.dp, bottom = 26.dp),
    ) {
        // 进度条：自己画，不依赖 DefaultTimeBar，方便和主题色统一
        val fraction = if (ui.durationMs > 0L) {
            (ui.positionMs.toFloat() / ui.durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Surface2),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(Accent),
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${formatTime(ui.positionMs)} / ${formatTime(ui.durationMs)}",
                color = TextSecondary,
                fontSize = 16.sp,
            )
            Text(
                text = buildString {
                    append(if (ui.isPlaying) "播放中" else "已暂停")
                    append(" · 倍速 ${formatSpeed(ui.speed)}")
                    if (ui.subtitleOn) append(" · 字幕开")
                    // 弹幕状态优先显示加载中，其次才是「未找到弹幕」这类短提示
                    if (ui.danmakuLoading) append(" · 弹幕加载中")
                    else if (ui.danmakuHint.isNotBlank()) append(" · ${ui.danmakuHint}")
                },
                color = TextDim,
                fontSize = 14.sp,
            )
        }

        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionButton(
                action = PlayerAction.PLAY_PAUSE,
                label = if (ui.isPlaying) "暂停" else "播放",
                ui = ui,
                onAction = onAction,
                primary = true,
            )
            ActionButton(PlayerAction.REWIND, "-10 秒", ui, onAction)
            ActionButton(PlayerAction.FORWARD, "+10 秒", ui, onAction)
            ActionButton(PlayerAction.NEXT, "下一集", ui, onAction, enabled = ui.hasNext)
            ActionButton(PlayerAction.AUDIO, "音轨", ui, onAction)
            ActionButton(PlayerAction.SUBTITLE, "字幕", ui, onAction)
            ActionButton(
                action = PlayerAction.DANMAKU,
                label = if (ui.danmakuOn) "弹幕 开" else "弹幕 关",
                ui = ui,
                onAction = onAction,
            )
            ActionButton(PlayerAction.SPEED, formatSpeed(ui.speed), ui, onAction)
            ActionButton(PlayerAction.EXIT, "退出", ui, onAction)
        }
    }
}

/** 控制条按钮：外面套一圈选中描边（遥控器焦点就是它）。 */
@Composable
private fun ActionButton(
    action: PlayerAction,
    label: String,
    ui: PlayerUiState,
    onAction: (PlayerAction) -> Unit,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    SelectableSlot(selected = ui.selection == action) {
        TvButton(
            text = label,
            onClick = { if (enabled) onAction(action) },
            primary = primary && enabled,
        )
    }
}

/** 统一的选中态包装：放大一点 + 白色描边，和 TvButton 的焦点样式呼应。 */
@Composable
private fun SelectableSlot(selected: Boolean, content: @Composable () -> Unit) {
    val scale by animateFloatAsState(if (selected) 1.05f else 1f, label = "slotScale")
    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) Color.White.copy(alpha = 0.9f) else Color.Transparent,
                shape = RoundedCornerShape(25.dp),
            )
            .padding(2.dp),
    ) {
        content()
    }
}

/* ───────────────────────── 加载 / 错误 ───────────────────────── */

@Composable
private fun LoadingPane() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = Accent,
                strokeWidth = 3.dp,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text("正在加载…", color = TextSecondary, fontSize = 16.sp)
        }
    }
}

@Composable
private fun ErrorPane(ui: PlayerUiState, message: String, onChoose: (Int) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.86f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("播放出错", color = Danger, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(
                text = message,
                color = TextSecondary,
                fontSize = 15.sp,
                lineHeight = 24.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(640.dp),
            )
            Spacer(Modifier.height(26.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SelectableSlot(selected = ui.errorChoice == 0) {
                    TvButton("重试", onClick = { onChoose(0) }, primary = true)
                }
                SelectableSlot(selected = ui.errorChoice == 1) {
                    TvButton("返回", onClick = { onChoose(1) })
                }
            }
        }
    }
}

/* ───────────────────────── 音轨 / 字幕弹层 ───────────────────────── */

@Composable
private fun TrackPane(ui: PlayerUiState, onSelectTrack: (Int) -> Unit, onClose: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(520.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Surface1)
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(18.dp))
                .padding(top = 18.dp, bottom = 12.dp),
        ) {
            Text(
                text = if (ui.overlay == PlayerOverlay.AUDIO) "选择音轨" else "选择字幕",
                color = TextPrimary,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "上下键选择 · 确定键应用 · 返回键关闭",
                color = TextDim,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(12.dp))

            val listState = rememberLazyListState()
            LaunchedEffect(ui.trackSelected) {
                runCatching { listState.animateScrollToItem(ui.trackSelected.coerceAtLeast(0)) }
            }
            LazyColumn(state = listState, modifier = Modifier.heightIn(max = 300.dp)) {
                itemsIndexed(ui.trackOptions) { index, option ->
                    TrackRow(
                        option = option,
                        selected = index == ui.trackSelected,
                        onClick = { onSelectTrack(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackRow(option: TrackOption, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Accent.copy(alpha = 0.20f) else Color.Transparent)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) Accent else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = option.label,
                color = if (selected) TextPrimary else TextSecondary,
                fontSize = 16.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (option.detail.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = option.detail,
                    color = TextDim,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (option.selected) {
            Spacer(Modifier.width(10.dp))
            Text("当前", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/* ───────────────────────── 小工具 ───────────────────────── */

/** 毫秒 → mm:ss / h:mm:ss。 */
internal fun formatTime(ms: Long): String {
    val total = ms.coerceAtLeast(0L) / 1000L
    val hours = total / 3600L
    val minutes = (total % 3600L) / 60L
    val seconds = total % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

/** 1.0 → "1.0x"，1.25 → "1.25x"。 */
internal fun formatSpeed(speed: Float): String {
    val text = if (speed == speed.toInt().toFloat()) "${speed.toInt()}.0" else speed.toString()
    return "${text}x"
}