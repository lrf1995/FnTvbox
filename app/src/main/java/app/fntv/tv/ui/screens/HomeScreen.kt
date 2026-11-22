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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fntv.tv.data.FnException
import app.fntv.tv.data.HomeData
import app.fntv.tv.data.Lib
import app.fntv.tv.data.MediaItem
import app.fntv.tv.data.Repo
import app.fntv.tv.ui.Accent
import app.fntv.tv.ui.ErrorBox
import app.fntv.tv.ui.LoadingBox
import app.fntv.tv.ui.SectionRow
import app.fntv.tv.ui.TextDim
import app.fntv.tv.ui.TextPrimary
import app.fntv.tv.ui.TextSecondary
import app.fntv.tv.ui.TvButton
import app.fntv.tv.ui.requestFocusWhenReady

@Composable
private fun LibChip(lib: Lib, onClick: () -> Unit, focusRequester: FocusRequester?) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .width(148.dp)
            .height(58.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (focused) Brush.linearGradient(listOf(Color(0xFF1E3B66), Color(0xFF152944)))
                else Brush.linearGradient(listOf(Color(0xFF141D2E), Color(0xFF101725)))
            )
            .border(
                if (focused) 2.dp else 1.dp,
                if (focused) Accent else Color.White.copy(alpha = 0.07f),
                RoundedCornerShape(12.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            lib.title,
            color = if (focused) TextPrimary else TextSecondary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            categoryLabel(lib.category),
            color = TextDim,
            fontSize = 10.sp,
            maxLines = 1,
        )
    }
}

private fun categoryLabel(category: String): String = when (category.lowercase()) {
    "movie" -> "电影"
    "tv" -> "电视剧"
    "video" -> "其他视频"
    "livechannel" -> "电视直播"
    "anime" -> "动漫"
    else -> category.ifEmpty { "媒体库" }
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
    val firstChipFocus = remember { FocusRequester() }

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

    LaunchedEffect(data) {
        if (data != null) requestFocusWhenReady(firstChipFocus)
    }

    Box(Modifier.fillMaxSize()) {
        when {
            loading && data == null -> LoadingBox()
            error != null && data == null -> ErrorBox(error!!) { tick++ }
            data != null -> {
                val home = data!!
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 6.dp, bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    item {
                        Column(Modifier.padding(horizontal = 48.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "晚上好，${Repo.username.ifEmpty { "朋友" }}",
                                        color = TextPrimary,
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "${Repo.server.replace("http://", "").replace("https://", "")} · ${home.libs.size} 个媒体库",
                                        color = TextDim,
                                        fontSize = 12.sp,
                                    )
                                }
                                TvButton("刷新", onClick = { tick++ })
                            }
                            Spacer(Modifier.height(16.dp))
                            Text("媒体库", color = TextSecondary, fontSize = 12.sp)
                        }
                    }

                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 48.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(home.libs) { lib ->
                                LibChip(
                                    lib = lib,
                                    onClick = { onOpenLib(lib) },
                                    focusRequester = if (home.libs.firstOrNull()?.guid == lib.guid) firstChipFocus else null,
                                )
                            }
                        }
                    }

                    if (home.continueWatching.isNotEmpty()) {
                        item {
                            SectionRow(
                                title = "继续观看",
                                subtitle = "接着上次的进度",
                                items = home.continueWatching,
                                onItemClick = onOpenItem,
                            )
                        }
                    }

                    items(home.rows) { row ->
                        SectionRow(
                            title = row.lib.title,
                            subtitle = categoryLabel(row.lib.category),
                            items = row.items,
                            onItemClick = onOpenItem,
                        )
                    }
                }
            }
        }
    }
}