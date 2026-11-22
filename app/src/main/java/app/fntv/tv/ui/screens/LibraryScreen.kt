package app.fntv.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fntv.tv.data.FnException
import app.fntv.tv.data.Lib
import app.fntv.tv.data.MediaItem
import app.fntv.tv.data.Repo
import app.fntv.tv.ui.Accent
import app.fntv.tv.ui.Danger
import app.fntv.tv.ui.EmptyBox
import app.fntv.tv.ui.ErrorBox
import app.fntv.tv.ui.LoadingBox
import app.fntv.tv.ui.PosterCard
import app.fntv.tv.ui.TextDim
import app.fntv.tv.ui.TextPrimary
import app.fntv.tv.ui.TextSecondary
import app.fntv.tv.ui.requestFocusWhenReady
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(
    lib: Lib,
    parentGuid: String?,
    title: String,
    onOpenItem: (MediaItem) -> Unit,
    onOpenFolder: (MediaItem) -> Unit,
    onSessionExpired: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val key = parentGuid ?: lib.guid
    var items by remember(key) { mutableStateOf<List<MediaItem>>(emptyList()) }
    var page by remember(key) { mutableIntStateOf(0) }
    var loading by remember(key) { mutableStateOf(true) }
    var loadingMore by remember(key) { mutableStateOf(false) }
    var done by remember(key) { mutableStateOf(false) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var total by remember(key) { mutableIntStateOf(0) }
    val firstFocus = remember { FocusRequester() }

    suspend fun fetch(next: Int) {
        try {
            val more = Repo.loadItems(
                ancestorGuid = lib.guid,
                page = next,
                parentGuid = parentGuid,
            )
            items = if (next == 1) more else items + more
            page = next
            if (more.isEmpty()) done = true
            error = null
        } catch (e: Exception) {
            if ((e as? FnException)?.code == -2) onSessionExpired()
            error = e.message ?: "加载失败"
        }
    }

    LaunchedEffect(key) {
        loading = true
        items = emptyList()
        done = false
        fetch(1)
        loading = false
    }

    LaunchedEffect(items.size) {
        if (items.isNotEmpty() && page <= 1) requestFocusWhenReady(firstFocus)
    }

    fun loadMore() {
        if (loadingMore || done || loading) return
        loadingMore = true
        scope.launch {
            fetch(page + 1)
            loadingMore = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        when {
            loading && items.isEmpty() -> LoadingBox()
            error != null && items.isEmpty() -> ErrorBox(error!!) {
                scope.launch {
                    loading = true
                    fetch(1)
                    loading = false
                }
            }
            items.isEmpty() -> EmptyBox("这个媒体库里还没有内容")
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(128.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 6.dp, bottom = 40.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(
                            Modifier.padding(bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(title, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                if (total > 0) "共 $total 部" else "共 ${items.size} 部",
                                color = TextDim,
                                fontSize = 12.sp,
                            )
                        }
                    }

                    itemsIndexed(items, key = { _, item -> item.guid }) { index, item ->
                        PosterCard(
                            item = item,
                            width = 132.dp,
                            height = 190.dp,
                            onClick = {
                                if (item.isFolder) onOpenFolder(item) else onOpenItem(item)
                            },
                            focusRequester = if (index == 0) firstFocus else null,
                            onFocused = {
                                if (index >= items.size - 6) loadMore()
                            },
                        )
                    }
                }
            }
        }
    }
}