package app.fntv.tv.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fntv.tv.data.Episode
import app.fntv.tv.data.FnApi
import app.fntv.tv.data.FnException
import app.fntv.tv.data.MediaItem
import app.fntv.tv.data.PlayRequest
import app.fntv.tv.data.Repo
import app.fntv.tv.data.Season
import app.fntv.tv.ui.Accent
import app.fntv.tv.ui.AccentSoft
import app.fntv.tv.ui.Danger
import app.fntv.tv.ui.ErrorBox
import app.fntv.tv.ui.Gold
import app.fntv.tv.ui.LoadingBox
import app.fntv.tv.ui.TextDim
import app.fntv.tv.ui.TextPrimary
import app.fntv.tv.ui.TextSecondary
import app.fntv.tv.ui.TvButton
import app.fntv.tv.ui.requestFocusWhenReady
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

@Composable
private fun EpisodeCard(
    ep: Episode,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .width(118.dp)
            .onFocusChanged { focused = it.isFocused }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(68.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (focused) Color(0xFF22324E) else Color(0xFF151E2F))
                .border(
                    if (focused) 2.dp else 1.dp,
                    if (focused) Accent else Color.White.copy(alpha = 0.07f),
                    RoundedCornerShape(8.dp)
                ),
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
                    Text("${ep.number}", color = TextSecondary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)))
                    )
            )
            Text(
                "第 ${ep.number} 集",
                color = TextPrimary,
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp),
            )
            if (ep.duration > 0 && ep.ts > 0) {
                val p = (ep.ts.toFloat() / ep.duration).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(p)
                        .height(3.dp)
                        .background(Accent)
                )
            }
            if (ep.watched) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text("已看", color = TextDim, fontSize = 9.sp)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            ep.title,
            color = if (focused) TextPrimary else TextSecondary,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SeasonChip(season: Season, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                when {
                    focused -> Color(0xFF2A3E60)
                    selected -> AccentSoft
                    else -> Color(0xFF151E2F)
                }
            )
            .border(
                if (focused) 2.dp else 1.dp,
                if (focused) Color.White.copy(alpha = 0.75f)
                else if (selected) Accent.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.06f),
                RoundedCornerShape(18.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            season.title,
            color = if (selected || focused) TextPrimary else TextSecondary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

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

    Box(Modifier.fillMaxSize()) {
        // 背景
        val bg = Repo.imageUrl(item.poster, 900)
        if (bg.isNotEmpty()) {
            AsyncImage(
                model = bg,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.22f,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xF0060910), Color(0xC0060910), Color(0x99060910))
                        )
                    )
            )
        }

        when {
            loading -> LoadingBox("正在读取剧集…")
            error != null -> ErrorBox(error!!) { reloadKey++ }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(108.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 8.dp, bottom = 40.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(Modifier.padding(bottom = 8.dp)) {
                            Box(
                                Modifier
                                    .width(190.dp)
                                    .height(276.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF131B2C))
                                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                            ) {
                                val img = Repo.imageUrl(item.poster, 600)
                                if (img.isNotEmpty()) {
                                    AsyncImage(
                                        model = img,
                                        contentDescription = item.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }

                            Spacer(Modifier.width(26.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.title,
                                    color = TextPrimary,
                                    fontSize = 30.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (item.rating > 0) {
                                        Text(
                                            String.format("%.1f", item.rating),
                                            color = Gold,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Spacer(Modifier.width(14.dp))
                                    }
                                    val meta = buildList {
                                        if (item.year.isNotEmpty()) add(item.year)
                                        if (item.seasons > 1) add("${item.seasons} 季")
                                        if (item.episodes > 0) add("${item.episodes} 集")
                                        if (item.watched) add("已看完")
                                    }.joinToString(" · ")
                                    if (meta.isNotEmpty()) {
                                        Text(meta, color = TextSecondary, fontSize = 13.sp)
                                    }
                                }
                                Spacer(Modifier.height(14.dp))
                                Text(
                                    item.overview.ifEmpty { "暂无简介" },
                                    color = TextSecondary,
                                    fontSize = 13.sp,
                                    lineHeight = 22.sp,
                                    maxLines = 5,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(20.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TvButton(
                                        text = if (item.ts > 0 && !item.watched) "继续播放" else "播放",
                                        onClick = { startPlay() },
                                        primary = true,
                                        focusRequester = playFocus,
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    TvButton("标记已看", onClick = {
                                        scope.launch {
                                            try {
                                                FnApi.markWatched(Repo.server, Repo.token, item.guid)
                                                notice = "已标记为已看"
                                            } catch (e: Exception) {
                                                notice = "标记失败：${e.message}"
                                            }
                                        }
                                    })
                                    if (notice != null) {
                                        Spacer(Modifier.width(16.dp))
                                        Text(notice!!, color = Accent, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }

                    if (seasons.size > 1) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(bottom = 4.dp),
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

                    if (episodes.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Row(Modifier.padding(top = 4.dp, bottom = 2.dp)) {
                                Text("选集", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.width(10.dp))
                                Text("${episodes.size} 集", color = TextDim, fontSize = 12.sp)
                            }
                        }
                        items(episodes, key = { it.guid }) { ep ->
                            EpisodeCard(ep, onClick = { startPlay(ep) }, focusRequester = null)
                        }
                    } else if (seriesLike) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                "飞牛里还没有这一季的剧集文件",
                                color = Danger,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}