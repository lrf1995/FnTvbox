package app.fntv.tv.ui.screens

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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fntv.tv.data.FnException
import app.fntv.tv.data.MediaItem
import app.fntv.tv.data.Repo
import app.fntv.tv.ui.EmptyBox
import app.fntv.tv.ui.ErrorBox
import app.fntv.tv.ui.LoadingBox
import app.fntv.tv.ui.PosterCard
import app.fntv.tv.ui.TextDim
import app.fntv.tv.ui.TextPrimary
import app.fntv.tv.ui.TvButton
import app.fntv.tv.ui.TvKeyboardDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * 搜索页：遥控器输入关键词 → Repo.search → 6 列海报墙。
 *
 * 纯 D-pad 下没有可用的中文输入法，所以同时提示「拼音首字母 / 英文 / 数字」搜索；
 * 服务端会话过期（FnException.code == -2）时交给上层回登录页。
 */
@Composable
fun SearchScreen(
    onOpenItem: (MediaItem) -> Unit,
    onSessionExpired: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searched by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    var keyboard by remember { mutableStateOf(false) }
    val inputFocus = remember { FocusRequester() }

    // 首次进入把焦点放在「输入关键词」按钮上，遥控器一进来就能按确认打开键盘。
    LaunchedEffect(Unit) {
        // 焦点节点可能还没挂载：先请一次，等一帧再兜底一次
        runCatching { inputFocus.requestFocus() }
        delay(120)
        runCatching { inputFocus.requestFocus() }
    }

    LaunchedEffect(query, tick) {
        val q = query.trim()
        if (q.isEmpty()) {
            results = emptyList()
            error = null
            loading = false
            searched = false
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            results = Repo.search(q)
            searched = true
            loading = false
        } catch (e: CancellationException) {
            // 关键词变化会取消上一次搜索，状态交给新的 Effect，这里不写任何快照状态。
            throw e
        } catch (e: Exception) {
            if ((e as? FnException)?.code == -2) onSessionExpired()
            results = emptyList()
            error = e.message ?: "搜索失败，请稍后重试"
            searched = true
            loading = false
        }
    }

    val statusLine = when {
        query.isBlank() -> "按「输入关键词」打开遥控器键盘，输入后自动搜索"
        loading -> "正在搜索…"
        error != null -> "搜索失败"
        !searched -> "正在准备搜索…"
        results.isNotEmpty() -> "找到 ${results.size} 个结果"
        else -> "没有找到相关影片"
    }

    Column(Modifier.fillMaxSize()) {
        /* ── 顶部一行：当前关键词 + 输入按钮 ── */
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 48.dp, end = 48.dp, top = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (query.isBlank()) "输入片名关键词" else query.trim(),
                    color = if (query.isBlank()) TextDim else TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = statusLine,
                    color = TextDim,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(20.dp))
            TvButton(
                text = "输入关键词",
                onClick = { keyboard = true },
                primary = query.isBlank(),
                focusRequester = inputFocus,
            )
        }

        /* ── 结果区：搜索中 / 出错 / 未搜索 / 空结果 / 海报墙 ── */
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            when {
                loading -> LoadingBox("正在搜索「${query.trim()}」…")
                error != null -> ErrorBox(error!!) { tick++ }
                !searched -> EmptyBox("还没有搜索，先输入片名关键词")
                results.isEmpty() -> EmptyBox("没有找到相关影片")
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(128.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 4.dp, bottom = 40.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(results, key = { it.guid }) { item ->
                        PosterCard(
                            item = item,
                            onClick = { onOpenItem(item) },
                            width = 132.dp,
                            height = 190.dp,
                        )
                    }
                }
            }
        }

        /* ── 底部小字提示：中文输入不便 ── */
        Text(
            text = "提示：遥控器下中文输入不便，可用拼音首字母或英文/数字搜索",
            color = TextDim,
            fontSize = 11.sp,
            maxLines = 1,
            modifier = Modifier.padding(start = 48.dp, end = 48.dp, top = 4.dp, bottom = 10.dp),
        )
    }

    if (keyboard) {
        TvKeyboardDialog(
            title = "搜索片名",
            initial = query,
            onDismiss = { keyboard = false },
            onConfirm = { input ->
                query = input.trim()
                keyboard = false
                tick++ // 关键词没变时也允许重新搜索
            },
        )
    }
}