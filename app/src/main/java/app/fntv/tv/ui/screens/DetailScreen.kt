package app.fntv.tv.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fntv.tv.data.Episode
import app.fntv.tv.data.FnApi
import app.fntv.tv.data.FnException
import app.fntv.tv.data.MediaItem
import app.fntv.tv.data.MetaInfo
import app.fntv.tv.data.MetaRepo
import app.fntv.tv.data.PlayRequest
import app.fntv.tv.data.Repo
import app.fntv.tv.data.Season
import app.fntv.tv.data.imagePath
import app.fntv.tv.ui.Accent
import app.fntv.tv.ui.Bg
import app.fntv.tv.ui.Danger
import app.fntv.tv.ui.ErrorBox
import app.fntv.tv.ui.Gold
import app.fntv.tv.ui.LoadingBox
import app.fntv.tv.ui.Surface1
import app.fntv.tv.ui.Surface2
import app.fntv.tv.ui.TextDim
import app.fntv.tv.ui.TextPrimary
import app.fntv.tv.ui.TextSecondary
import app.fntv.tv.ui.TvButton
import app.fntv.tv.ui.requestFocusWhenReady
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.abs

/* ───────────────────────── 通栏工具 ───────────────────────── */

/**
 * 让 LazyVerticalGrid 里的整宽区块突破 contentPadding 的左右内边距。
 * Lazy 布局只沿滚动方向裁剪，所以水平方向画出线宽之外不会被切掉；
 * 这里按「父容器给出的线宽 + 两侧内边距」重新 measure，并向左偏移贴齐屏幕。
 */
private fun Modifier.fullBleed(horizontalInset: Dp): Modifier = this.layout { measurable, constraints ->
    val inset = horizontalInset.roundToPx()
    val width = constraints.maxWidth + inset * 2
    val placeable = measurable.measure(
        Constraints(
            minWidth = width,
            maxWidth = width,
            minHeight = constraints.minHeight,
            maxHeight = constraints.maxHeight,
        )
    )
    // 对外仍只占线宽，绘制时左右各溢出 inset，正好压掉 grid 的 contentPadding
    layout(constraints.maxWidth, placeable.height) {
        placeable.placeRelative(-inset, 0)
    }
}

/* ───────────────────────── 外部元数据 ───────────────────────── */

/** 外部元数据来源的展示名：接口给的是小写标识，这里转成界面上看到的品牌名。 */
private fun metaSourceLabel(source: String): String = when (source.lowercase()) {
    "bangumi" -> "Bangumi"
    "tmdb" -> "TMDB"
    else -> source.replaceFirstChar { it.uppercase() }.ifEmpty { "元数据" }
}

/* ───────────────────────── 顶部 Hero ───────────────────────── */

@Composable
private fun StatusTag(text: String, tint: Color, bg: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, color = tint, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/**
 * 沉浸式头图：横版剧照铺满屏宽，底部/左侧双层渐变压暗，左下叠标题、评分、
 * 元信息、三行简介与操作按钮。
 *
 * meta 是外部元数据（Bangumi/TMDB）的兜底数据，可能为 null 或加载中；
 * 为空时这里所有取值的表现都和没接元数据之前完全一致。
 */
@Composable
private fun HeroSection(
    item: MediaItem,
    meta: MetaInfo?,
    height: Dp,
    playLabel: String,
    playFocus: FocusRequester,
    notice: String?,
    onPlay: () -> Unit,
    onMarkWatched: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 评分：外部评分有效且和飞牛评分不一致（容差 0.05，避开 8.6 与 8.6000001 这种浮点差）时，
    // 改用外部评分并标出来源；否则仍用飞牛自带的 item.rating。
    val externalRating = meta?.rating?.takeIf { it > 0.0 }
    val displayRating: Double
    val ratingSource: String?
    if (externalRating != null && abs(externalRating - item.rating) >= 0.05) {
        displayRating = externalRating
        ratingSource = metaSourceLabel(meta?.source.orEmpty())
    } else {
        displayRating = item.rating
        ratingSource = null
    }

    // 简介：飞牛简介为空或不足 10 字时才用外部简介兜底；两边都有内容时优先飞牛。
    val displayOverview = if (item.overview.length < 10) {
        meta?.overview?.takeIf { it.isNotBlank() } ?: item.overview
    } else {
        item.overview
    }

    // 主演/声优：最多取前 6 个名字，单行展示，避免把 Hero 撑高。
    val castNames = meta?.cast
        ?.map { it.name }
        ?.filter { it.isNotBlank() }
        ?.take(6)
        .orEmpty()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
    ) {
        // 背景优先横版剧照（backdrops），没有才退回竖版海报；
        // 飞牛的两处图都没有时用外部元数据的海报（meta.poster 已是完整 URL，直接交给 AsyncImage）。
        val localPath = item.raw?.imagePath("backdrops") ?: item.poster
        val backdrop = Repo.imageUrl(localPath, 1000)
            .ifEmpty { meta?.poster?.takeIf { it.isNotBlank() }.orEmpty() }
        if (backdrop.isNotEmpty()) {
            AsyncImage(
                model = backdrop,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize().background(Surface1))
        }

        // 底部 → 黑：下半部分一路过渡到页面底色，文字压在图上也清晰
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.40f to Color.Black.copy(alpha = 0.25f),
                        0.72f to Color.Black.copy(alpha = 0.74f),
                        1f to Bg,
                    )
                )
        )
        // 左 → 右：左侧压暗给文字，右侧保留剧照细节
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.86f),
                        0.42f to Color.Black.copy(alpha = 0.42f),
                        0.78f to Color.Transparent,
                    )
                )
        )
        // 顶部轻轻压暗，和顶栏交界不生硬
        Box(
            Modifier
                .fillMaxWidth()
                .height(72.dp)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.45f),
                        1f to Color.Transparent,
                    )
                )
        )

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 48.dp, end = 48.dp, bottom = 22.dp),
        ) {
            Text(
                item.title,
                color = TextPrimary,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (displayRating > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("★", color = Gold, fontSize = 14.sp)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            String.format("%.1f", displayRating),
                            color = Gold,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        // 评分来自外部元数据时，紧跟着标一个小来源标签
                        if (ratingSource != null) {
                            Spacer(Modifier.width(6.dp))
                            StatusTag(ratingSource, TextSecondary, Surface2)
                        }
                    }
                }
                val metaLine = buildList {
                    if (item.year.isNotEmpty()) add(item.year)
                    if (item.seasons > 1) add("${item.seasons} 季")
                    if (item.episodes > 0) add("${item.episodes} 集")
                }.joinToString(" · ")
                if (metaLine.isNotEmpty()) {
                    Text(metaLine, color = TextSecondary, fontSize = 13.sp)
                }
                // 外部元数据带放送日期时，接在年份/季集数后面：例如「2024-04-07 放送」
                val airDate = meta?.airDate.orEmpty()
                if (airDate.isNotEmpty()) {
                    Text("$airDate 放送", color = TextSecondary, fontSize = 13.sp, maxLines = 1)
                }
                if (item.watched) {
                    StatusTag("已看完", Color(0xFF04101F), Accent)
                } else if (item.ts > 0 && !item.watched) {
                    StatusTag("继续观看", Gold, Gold.copy(alpha = 0.16f))
                }
            }

            if (displayOverview.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    displayOverview,
                    color = TextSecondary,
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 760.dp),
                )
            }

            // 主演/声优：只占一行，放在简介和按钮之间，不参与焦点，不会把 Hero 撑高
            if (castNames.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.widthIn(max = 760.dp),
                ) {
                    Text(
                        "主演/声优",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        castNames.joinToString(" · "),
                        color = TextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TvButton(
                    text = playLabel,
                    onClick = onPlay,
                    primary = true,
                    focusRequester = playFocus,
                )
                Spacer(Modifier.width(12.dp))
                TvButton("标记已看", onClick = onMarkWatched)
                if (notice != null) {
                    Spacer(Modifier.width(16.dp))
                    Text(
                        notice,
                        color = if (notice.startsWith("标记失败")) Danger else Accent,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/* ───────────────────────── 季胶囊 ───────────────────────── */

@Composable
private fun SeasonChip(season: Season, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "seasonScale")
    val bg by animateColorAsState(
        when {
            selected -> Accent
            focused -> Surface2
            else -> Surface1
        },
        label = "seasonBg",
    )
    val border by animateColorAsState(
        when {
            focused -> Color.White.copy(alpha = 0.9f)
            selected -> Accent
            else -> Color.White.copy(alpha = 0.07f)
        },
        label = "seasonBorder",
    )
    val shape = RoundedCornerShape(19.dp)

    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .height(38.dp)
            .clip(shape)
            .background(bg)
            .border(if (focused) 2.dp else 1.dp, border, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            season.title,
            color = when {
                selected -> Color(0xFF04101F)
                focused -> TextPrimary
                else -> TextSecondary
            },
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/* ───────────────────────── 剧集卡 ───────────────────────── */

@Composable
private fun EpisodeCard(
    ep: Episode,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, label = "epScale")
    val borderColor by animateColorAsState(
        if (focused) Accent else Color.White.copy(alpha = 0.07f),
        label = "epBorder",
    )
    val shape = RoundedCornerShape(10.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { focused = it.isFocused }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clickable(onClick = onClick),
    ) {
        // 16:9 缩略图：有图用图，没图用深色底 + 集号大字
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(shape)
                .background(Surface1)
                .border(if (focused) 2.dp else 1.dp, borderColor, shape),
        ) {
            val img = Repo.imageUrl(ep.poster, 300)
            if (img.isNotEmpty()) {
                AsyncImage(
                    model = img,
                    contentDescription = ep.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "${ep.number}",
                        color = TextDim,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // 底部渐变，压住集号与标题
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.42f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.85f),
                        )
                    )
            )

            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(
                    "第 ${ep.number} 集",
                    color = if (focused) Accent else TextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    ep.title,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // 观看进度
            if (ep.duration > 0 && ep.ts > 0) {
                val p = (ep.ts.toFloat() / ep.duration).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.White.copy(alpha = 0.16f))
                )
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(p)
                        .height(3.dp)
                        .background(Accent)
                )
            }

            // 已看角标
            if (ep.watched) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.66f))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                ) {
                    Text("已看", color = TextSecondary, fontSize = 9.sp)
                }
            }

            // 焦点态：再叠一层极淡的 accent 罩，让当前卡在深色页面上更醒目
            if (focused) {
                Box(Modifier.fillMaxSize().background(Accent.copy(alpha = 0.08f)))
            }
        }
    }
}

/* ───────────────────────── 详情页 ───────────────────────── */

@Composable
fun DetailScreen(
    item: MediaItem,
    onPlay: (PlayRequest) -> Unit,
    onSessionExpired: () -> Unit,
    refreshTick: Int = 0,
) {
    val scope = rememberCoroutineScope()
    var loading by remember(item.guid) { mutableStateOf(true) }
    var error by remember(item.guid) { mutableStateOf<String?>(null) }
    var seasons by remember(item.guid) { mutableStateOf<List<Season>>(emptyList()) }
    var seasonIndex by remember(item.guid) { mutableIntStateOf(0) }
    var episodes by remember(item.guid) { mutableStateOf<List<Episode>>(emptyList()) }
    // 外部元数据（Bangumi/TMDB）：可能为空——没 key、网络失败、条目匹配不上都返回 null
    var meta by remember(item.guid) { mutableStateOf<MetaInfo?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember(item.guid) { mutableIntStateOf(0) }
    var focusedOnce by remember(item.guid) { mutableStateOf(false) }
    val playFocus = remember { FocusRequester() }

    val seriesLike = item.type == "TV" || item.type == "Season" || item.type == "Collection"

    suspend fun loadEpisodes(seasonGuid: String) {
        episodes = Repo.episodes(seasonGuid)
    }

    LaunchedEffect(item.guid, refreshTick, reloadKey) {
        loading = true
        error = null
        try {
            if (seriesLike) {
                seasons = Repo.seasons(item.guid)
                if (seasons.isNotEmpty()) {
                    val idx = seasons.indexOfFirst { it.number == item.seasonNumber }
                    seasonIndex = if (idx >= 0) idx else 0
                    loadEpisodes(seasons[seasonIndex].guid)
                }
            }
        } catch (e: Exception) {
            if ((e as? FnException)?.code == -2) onSessionExpired()
            error = e.message ?: "加载详情失败"
        } finally {
            loading = false
        }
    }

    // 外部元数据单独一个协程：和上面的剧集加载并行，互不阻塞。
    // lookup 内部已处理异常/无 key，这里再包一层 runCatching 兜底，绝不让详情页报错或空白。
    LaunchedEffect(item.guid, refreshTick) {
        meta = null
        meta = runCatching { MetaRepo.lookup(item) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
    }

    // 只在本页首次进入时聚焦「播放」，切季/播放返回后的刷新不再抢焦点
    LaunchedEffect(loading, episodes.size) {
        if (!loading && !focusedOnce) {
            focusedOnce = true
            requestFocusWhenReady(playFocus)
        }
    }

    fun startPlay(fromEpisode: Episode? = null) {
        if (seriesLike) {
            if (episodes.isEmpty()) {
                notice = "还没有可播放的剧集"
                return
            }
            val index = when {
                fromEpisode != null -> episodes.indexOfFirst { it.guid == fromEpisode.guid }.coerceAtLeast(0)
                else -> {
                    val resume = episodes.indexOfFirst { it.ts > 0 && !it.watched }
                    val next = episodes.indexOfFirst { !it.watched }
                    if (resume >= 0) resume else if (next >= 0) next else 0
                }
            }
            onPlay(PlayRequest(item.title, episodes, index))
        } else {
            val single = Episode(
                guid = item.guid,
                title = item.title,
                number = 1,
                watched = item.watched,
                ts = item.ts,
                duration = item.duration,
                poster = item.poster,
            )
            onPlay(PlayRequest(item.title, listOf(single), 0))
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Bg)
    ) {
        when {
            loading -> LoadingBox("正在读取剧集…")
            error != null -> ErrorBox(error!!) { reloadKey++ }
            else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                // 内容区已扣掉顶栏：取内容区高度的一半，换算到整屏约 46%（和首页 Hero 同一套比例）
                val heroHeight = (maxHeight * 0.50f).coerceIn(280.dp, 560.dp)
                val resume = item.ts > 0 && !item.watched
                val playLabel = if (resume) "▶ 继续播放" else "▶ 播放"

                // 整页只有一个滚动容器：Hero / 季切换 / 选集都作为 grid item
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(128.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 44.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // ① 通栏 Hero（fullBleed 抵消左右 contentPadding）
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        HeroSection(
                            modifier = Modifier.fullBleed(48.dp),
                            item = item,
                            meta = meta,
                            height = heroHeight,
                            playLabel = playLabel,
                            playFocus = playFocus,
                            notice = notice,
                            onPlay = { startPlay() },
                            onMarkWatched = {
                                scope.launch {
                                    try {
                                        FnApi.markWatched(Repo.server, Repo.token, item.guid)
                                        notice = "已标记为已看"
                                    } catch (e: Exception) {
                                        notice = "标记失败：${e.message}"
                                    }
                                }
                            },
                        )
                    }

                    // ② 多季时一行季胶囊
                    if (seasons.size > 1) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                seasons.forEachIndexed { idx, season ->
                                    SeasonChip(season, idx == seasonIndex) {
                                        seasonIndex = idx
                                        scope.launch {
                                            try {
                                                loadEpisodes(season.guid)
                                            } catch (e: Exception) {
                                                if ((e as? FnException)?.code == -2) onSessionExpired()
                                                error = e.message
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ③ 选集
                    if (episodes.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                            ) {
                                Box(
                                    Modifier
                                        .width(4.dp)
                                        .height(18.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(Accent)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "选集",
                                    color = TextPrimary,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "· 共 ${episodes.size} 集",
                                    color = TextDim,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                        items(episodes, key = { it.guid }) { ep ->
                            EpisodeCard(ep, onClick = { startPlay(ep) }, focusRequester = null)
                        }
                    } else if (seriesLike) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column(Modifier.padding(top = 8.dp)) {
                                Text(
                                    "这一季还没有剧集",
                                    color = TextPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "换个季数看看，或稍后在飞牛影视里重新扫描一次媒体库。",
                                    color = TextDim,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
