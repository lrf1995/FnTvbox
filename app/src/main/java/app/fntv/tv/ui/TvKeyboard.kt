package app.fntv.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

private val ROWS = listOf(
    listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
    listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
    listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
    listOf("z", "x", "c", "v", "b", "n", "m", ".", "-", ":", "/"),
)

@Composable
private fun Key(
    label: String,
    width: Int = 56,
    primary: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .width(width.dp)
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    primary && focused -> Accent
                    primary -> AccentSoft
                    focused -> Color(0xFF2E3E5E)
                    else -> Color(0xFF182135)
                }
            )
            .border(
                if (focused) 2.dp else 1.dp,
                if (focused) Color.White.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.07f),
                RoundedCornerShape(8.dp)
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (primary && focused) Color(0xFF04101F) else TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * 遥控器可用的软键盘（10 键宽，适合 1080p 电视）。
 * 中文输入法无法在纯 D-pad 下使用，片名搜索建议用拼音首字母或英文/数字关键词。
 */
@Composable
fun TvKeyboardDialog(
    title: String,
    initial: String,
    password: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val show = if (password) "•".repeat(text.length) else text
    // 弹窗打开后必须把焦点抢进键盘，否则遥控器方向键会落在背后的页面上
    val firstKey = remember { FocusRequester() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xF00A0F1A))
                .padding(horizontal = 60.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 12.dp),
        ) {
            Text(title, color = TextSecondary, fontSize = 14.sp)
        }

        // 焦点必须在 Dialog 自己的窗口里请求；弹窗刚挂载时可能还没布局好，多试几次
        LaunchedEffect(Unit) {
            repeat(4) { attempt ->
                runCatching { firstKey.requestFocus() }
                delay(60L + attempt * 80L)
            }
        }

        Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF101827))
                    .border(1.dp, Accent.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = show.ifEmpty { " " },
                    color = TextPrimary,
                    fontSize = 20.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(18.dp))

            ROWS.forEachIndexed { rowIndex, row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(bottom = 6.dp),
                ) {
                    row.forEachIndexed { chIndex, ch ->
                        Key(
                            label = ch,
                            width = if (row.size > 10) 52 else 56,
                            focusRequester = if (rowIndex == 0 && chIndex == 0) firstKey else null,
                        ) { text += ch }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Key("空格", width = 120) { text += " " }
                Key("删除", width = 96) { if (text.isNotEmpty()) text = text.dropLast(1) }
                Key("清空", width = 84) { text = "" }
                Key("取消", width = 96) { onDismiss() }
                Key("确定", width = 110, primary = true) { onConfirm(text) }
            }
        }
    }
}