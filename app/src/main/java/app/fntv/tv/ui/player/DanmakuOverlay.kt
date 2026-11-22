package app.fntv.tv.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import app.fntv.tv.data.DanmakuComment
import kotlin.math.abs
import kotlin.math.max

/**
 * 弹幕画层：叠在 PlayerView 之上、控制条之下的一个全屏 Canvas。
 *
 * 布局约定：
 *  - 顶部 10% 留给标题/返回按钮，底部 12% 留给控制条，中间才是弹幕区；
 *  - 区域按高度切成 8~10 条轨道，滚动弹幕从右往左，顶部/底部弹幕水平居中；
 *  - 只有「已经出现且还在时间窗内」的弹幕才参与绘制：
 *      滚动弹幕 0..9000ms，顶部/底部弹幕 0..4000ms。
 *
 * 时间来源：
 *  - [positionMs] 由播放器每 500ms 更新一次，直接拿来算位置会「一顿一顿」；
 *  - 所以内部再用 withFrameNanos 做一个本地时钟，在两次更新之间按帧插值，
 *    遇到 seek/切集这种跳变（或父级 800ms 没动 = 暂停）时对齐/冻结。
 *
 * @param comments 已按时间升序的弹幕（DanmakuApi 返回的就是有序的）
 * @param positionMs 播放器当前位置
 * @param enabled 关闭时什么都不画，但仍然占位（避免布局跳动）
 */
@Composable
fun DanmakuOverlay(
    comments: List<DanmakuComment>,
    positionMs: Long,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    // 关掉或本集没有弹幕：留一个空 Box 占住这一层的位置
    if (!enabled || comments.isEmpty()) {
        Box(modifier)
        return
    }

    val textMeasurer = rememberTextMeasurer()
    // 22sp 是在 1080p 电视上试出来的折中值：再大会糊住人脸，再小沙发上看不清
    val textStyle = remember {
        TextStyle(fontSize = FONT_SIZE_SP.sp, fontWeight = FontWeight.Medium)
    }
    val density = LocalDensity.current
    val fontPx = with(density) { FONT_SIZE_SP.sp.toPx() }
    // 单行文本占的高度（含上下留白），用来算轨道间距
    val linePx = fontPx * 1.45f

    BoxWithConstraints(modifier) {
        // 没有确定尺寸时（比如被放进可滚动容器）不画，避免拿到 Infinity 算出 NaN
        if (constraints.maxHeight == Constraints.Infinity || constraints.maxWidth == Constraints.Infinity) {
            return@BoxWithConstraints
        }

        val heightPx = with(density) { maxHeight.toPx() }
        val areaTop = heightPx * TOP_RESERVED
        val areaBottom = heightPx * (1f - BOTTOM_RESERVED)
        val areaHeight = (areaBottom - areaTop).coerceAtLeast(1f)
        // 轨道数由可用高度决定，再夹到 8~10 条：太密会互相盖住，太疏又浪费画面
        val laneCount = (areaHeight / (linePx * 1.35f)).toInt().coerceIn(MIN_LANES, MAX_LANES)

        // 轨道分配只跟「这一集的弹幕列表」有关，算一次就够；切集/换列表时重算
        val sorted = remember(comments) { comments.sortedBy { it.timeMs } }
        val lanes = remember(sorted, laneCount) { assignLanes(sorted, laneCount) }

        // 可见集合按 positionMs 重算（500ms 一次，顺带在这里量文字）；
        // 每帧只重绘、不重新测量，60fps 下才稳。
        val visible = remember(sorted, lanes, positionMs) {
            collectVisible(sorted, lanes, positionMs, textMeasurer, textStyle)
        }

        // 画面内的平滑时钟：两级时间源，避免弹幕随 500ms 的进度刷新一跳一跳
        val clock = rememberDanmakuClock(positionMs)
        val shadowPx = max(1f, fontPx * 0.06f)

        Canvas(Modifier.fillMaxSize()) {
            val nowMs = clock.value // 在 draw 阶段读：只触发重绘，不触发重组
            val width = size.width
            val height = size.height
            if (width <= 0f || height <= 0f) return@Canvas

            val top = height * TOP_RESERVED
            val bottom = height * (1f - BOTTOM_RESERVED)
            val area = (bottom - top).coerceAtLeast(1f)
            val laneHeight = area / laneCount

            for (item in visible) {
                val ageMs = nowMs - item.timeMs
                // 早到的（本地时钟偶尔落后）先不画，等真正到点
                if (ageMs < 0L) continue

                val textWidth = item.layout.size.width.toFloat()
                val textHeight = item.layout.size.height.toFloat()
                // 滚动弹幕和顶部弹幕用同一套「自上而下」的轨道；底部弹幕从下往上数
                val laneTop = top + item.lane * laneHeight + (laneHeight - textHeight) / 2f
                val x: Float
                val y: Float
                if (item.kind == DanmakuKind.SCROLL) {
                    // 9 秒从右边缘完全滑到左边缘：进度 0 时还在屏幕外，进度 1 时已出屏
                    val progress = (ageMs / SCROLL_WINDOW_MS.toFloat()).coerceIn(0f, 1f)
                    x = width - progress * (width + textWidth)
                    y = laneTop
                    // 已经滑出去了就别再调绘制（长弹幕在窗口末尾都会走到这里）
                    if (x + textWidth < 0f || x > width) continue
                } else if (item.kind == DanmakuKind.TOP) {
                    x = (width - textWidth) / 2f
                    y = laneTop
                } else {
                    x = (width - textWidth) / 2f
                    y = bottom - (item.lane + 1) * laneHeight + (laneHeight - textHeight) / 2f
                }

                // 两遍绘制当描边：先偏移一点画黑色底字，再画本色字。
                // 中文小字号压在亮画面上必须要有这层黑边，否则白字直接糊掉。
                drawText(
                    textLayoutResult = item.layout,
                    color = SHADOW,
                    topLeft = Offset(x + shadowPx, y + shadowPx),
                )
                drawText(
                    textLayoutResult = item.layout,
                    color = item.color,
                    topLeft = Offset(x, y),
                )
            }
        }
    }
}

/* ───────────────────────── 内部结构 ───────────────────────── */

/** 弹幕类型：4 = 底部、5 = 顶部，其余（含实测出现的 0）按滚动。 */
private enum class DanmakuKind { SCROLL, TOP, BOTTOM }

private fun kindOf(mode: Int): DanmakuKind = when (mode) {
    4 -> DanmakuKind.BOTTOM
    5 -> DanmakuKind.TOP
    else -> DanmakuKind.SCROLL
}

/** 一条「当前要画」的弹幕：轨道和排版都在重组时算好，绘制阶段只做坐标计算。 */
private class VisibleDanmaku(
    val timeMs: Long,
    val kind: DanmakuKind,
    val lane: Int,
    val color: Color,
    val layout: TextLayoutResult,
)

/** 可见时间窗：滚动弹幕和固定的顶部/底部弹幕不一样长。 */
private const val SCROLL_WINDOW_MS = 9_000L
private const val FIXED_WINDOW_MS = 4_000L

/** 顶部/底部各留出的空间比例。 */
private const val TOP_RESERVED = 0.10f
private const val BOTTOM_RESERVED = 0.12f

/** 轨道数范围与顶部/底部弹幕各自占用的固定行数。 */
private const val MIN_LANES = 8
private const val MAX_LANES = 10
private const val FIXED_LANES = 3

/**
 * 同一条轨道两条滚动弹幕的最小间隔。
 * 比 9 秒的窗口短得多：文字前半截还在屏幕里时，下一句已经可以跟着入场了。
 */
private const val LANE_COOLDOWN_MS = 1_400L

/** 字号：比正文稍大，远看才清楚。 */
private const val FONT_SIZE_SP = 22

/** 描边色（纯黑略透一点，避免在纯黑画面上显得太硬）。 */
private val SHADOW = Color.Black.copy(alpha = 0.85f)

/**
 * 轨道分配：按「时间 + 文本」的 hash 起一条轨道，被占就线性往后找空闲的。
 *
 * 之所以不用 cid：DanmakuApi 的模型里不带 cid（同一集弹幕的 cid 只在源站有意义），
 * 用 timeMs + text.hashCode() 已经能满足「同一条弹幕每次都落在同一条轨道」。
 * 全部轨道都占着时退回 hash 起点，宁可叠一点也别让弹幕消失。
 *
 * @return 与 [sorted] 等长的轨道下标数组
 */
private fun assignLanes(sorted: List<DanmakuComment>, laneCount: Int): IntArray {
    val lanes = IntArray(sorted.size)
    if (sorted.isEmpty()) return lanes

    val scrollFree = LongArray(laneCount)
    val topFree = LongArray(FIXED_LANES)
    val bottomFree = LongArray(FIXED_LANES)
    val fixedLanes = FIXED_LANES.coerceAtMost(laneCount)

    for (i in sorted.indices) {
        val c = sorted[i]
        when (kindOf(c.mode)) {
            DanmakuKind.SCROLL -> {
                val start = laneSeed(c) % laneCount
                var lane = start
                var probe = 0
                while (probe < laneCount && scrollFree[lane] > c.timeMs) {
                    lane = (lane + 1) % laneCount
                    probe++
                }
                if (probe >= laneCount) lane = start
                scrollFree[lane] = c.timeMs + LANE_COOLDOWN_MS
                lanes[i] = lane
            }

            DanmakuKind.TOP -> {
                lanes[i] = pickFixedLane(topFree, c.timeMs, fixedLanes, laneSeed(c))
            }

            DanmakuKind.BOTTOM -> {
                lanes[i] = pickFixedLane(bottomFree, c.timeMs, fixedLanes, laneSeed(c))
            }
        }
    }
    return lanes
}

/** 顶部/底部弹幕的轨道：只有最上面/最下面的 [fixedLanes] 条可用。 */
private fun pickFixedLane(free: LongArray, timeMs: Long, fixedLanes: Int, seed: Int): Int {
    val start = seed % fixedLanes
    var lane = start
    var probe = 0
    while (probe < fixedLanes && free[lane] > timeMs) {
        lane = (lane + 1) % fixedLanes
        probe++
    }
    if (probe >= fixedLanes) lane = start
    free[lane] = timeMs + LANE_COOLDOWN_MS
    return lane
}

/** 非负的稳定 hash：同一集的同一条弹幕每次都得到同一个值。 */
private fun laneSeed(c: DanmakuComment): Int {
    var h = c.timeMs xor (c.timeMs ushr 32)
    h = h * 31 + c.text.hashCode()
    return h.toInt() and Int.MAX_VALUE
}

/**
 * 取出当前时间窗内的弹幕并量好排版。
 *
 * 入参 [sorted] 已按时间升序，所以扫到「还没到点的」就可以直接 break —— 一集 1600 条
 * 也只扫前几十条，不会在 500ms 一次的刷新里做全量测量。
 */
private fun collectVisible(
    sorted: List<DanmakuComment>,
    lanes: IntArray,
    positionMs: Long,
    textMeasurer: TextMeasurer,
    style: TextStyle,
): List<VisibleDanmaku> {
    val out = ArrayList<VisibleDanmaku>()
    for (i in sorted.indices) {
        val c = sorted[i]
        val age = positionMs - c.timeMs
        if (age < 0L) break

        val kind = kindOf(c.mode)
        val window = if (kind == DanmakuKind.SCROLL) SCROLL_WINDOW_MS else FIXED_WINDOW_MS
        if (age > window) continue

        val layout = textMeasurer.measure(AnnotatedString(c.text), style = style)
        out += VisibleDanmaku(
            timeMs = c.timeMs,
            kind = kind,
            lane = lanes.getOrElse(i) { 0 },
            color = Color(c.color),
            layout = layout,
        )
    }
    return out
}

/**
 * 弹幕本地时钟。
 *
 * [positionMs] 每 500ms 才更新一次，直接用它算滚动位置会变成 2fps。
 * 这里在两次更新之间按帧推进，并处理三种情况：
 *  1. 正常播放：父级小步前进（~500ms），本地时钟继续按帧走，不打断动画；
 *  2. seek / 切集 / 快退：父级跳变超过 1 秒或向后跳，本地时钟直接对齐；
 *  3. 暂停 / 缓冲：父级 800ms 没变化，本地时钟停走，弹幕就定在原处。
 */
@Composable
private fun rememberDanmakuClock(positionMs: Long): State<Long> {
    val latest = rememberUpdatedState(positionMs)
    val clock = remember { mutableLongStateOf(positionMs) }

    LaunchedEffect(Unit) {
        var lastFrameNs = 0L
        var lastParentMs = latest.value
        var lastParentAtNs = 0L

        while (true) {
            withFrameNanos { nowNs ->
                // 第一帧先对齐，避免开场时本地时钟从 0 开始追
                if (lastFrameNs == 0L) {
                    lastFrameNs = nowNs
                    lastParentAtNs = nowNs
                    lastParentMs = latest.value
                    clock.longValue = latest.value
                    return@withFrameNanos
                }

                // 一帧最多算 100ms：从后台切回来时别让弹幕瞬移一大段
                val deltaMs = ((nowNs - lastFrameNs) / 1_000_000L).coerceIn(0L, 100L)
                lastFrameNs = nowNs

                val parent = latest.value
                if (parent != lastParentMs) {
                    val step = parent - lastParentMs
                    lastParentMs = parent
                    lastParentAtNs = nowNs
                    val jump = step < 0L || step > 1_500L || abs(parent - clock.longValue) > 1_000L
                    if (jump) clock.longValue = parent
                }

                val stalled = (nowNs - lastParentAtNs) / 1_000_000L > 800L
                if (!stalled) clock.longValue += deltaMs
            }
        }
    }
    return clock
}