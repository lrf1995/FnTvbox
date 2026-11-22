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

private val LETTER_ROWS = listOf(
    listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
    listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
    listOf("z", "x", "c", "v", "b", "n", "m"),
)

private val DIGIT_ROW = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "-", "=")

private val SYMBOL_ROWS = listOf(
    listOf("!", "@", "#", "$", "%", "^", "&", "*", "(", ")", "_", "+"),
    listOf("[", "]", "{", "}", ";", ":", "'", "\"", "<", ">", "?", "|"),
    listOf("~", "`", "\\", "/", ",", ".", " "),
)

@Composable
private fun Key(
    label: String,
    width: Int = 56,
    primary: Boolean = false,
    active: Boolean = false,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .width(width.dp)
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    primary && focused -> Accent
                    primary -> AccentSoft
                    active -> Color(0xFF254265)
                    focused -> Color(0xFF2E3E5E)
                    else -> Color(0xFF182135)
                }
            )
            .border(
                if (focused) 2.dp else 1.dp,
                if (focused) Color.White.copy(alpha = 0.8f)
                else if (active) Accent.copy(alpha = 0.7f)
                else Color.White.copy(alpha = 0.07f),
                RoundedCornerShape(8.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (primary && focused) Color(0xFF04101F) else TextPrimary,
            fontSize = if (label.length > 2) 12.sp else 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * 遥控器可用的软键盘（含大写与符号，够输入常见密码/片名）。
 *
 * 说明：中文需要系统输入法，这个键盘只负责 ASCII；搜索建议用拼音首字母或英文关键词。
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
    var shift by remember { mutableStateOf(false) }
    var symbols by remember { mutableStateOf(false) }
    var reveal by remember { mutableStateOf(false) }
    val firstKey = remember { FocusRequester() }

    val shown = if (password && !reveal) "•".repeat(text.length) else text

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xF00A0F1A))
                .padding(horizontal = 48.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                Text(title, color = TextSecondary, fontSize = 13.sp)
                if (password) {
                    Text(
                        if (reveal) "（明文）" else "（密文）",
                        color = if (reveal) Gold else TextDim,
                        fontSize = 11.sp,
                    )
                }
            }

            // 焦点必须在 Dialog 自己的窗口里请求；弹窗刚挂载时可能还没布局好，多试几次
            LaunchedEffect(Unit) {
                repeat(4) { attempt ->
                    runCatching { firstKey.requestFocus() }
                    delay(60L + attempt * 80L)
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF101827))
                    .border(1.dp, Accent.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = shown.ifEmpty { " " },
                    color = TextPrimary,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))

            val letters = if (shift) LETTER_ROWS.map { row -> row.map(String::uppercase) } else LETTER_ROWS
            val rows: List<List<String>> = if (symbols) SYMBOL_ROWS else letters

            rows.forEachIndexed { rowIndex, row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(bottom = 5.dp),
                ) {
                    row.forEachIndexed { chIndex, ch ->
                        Key(
                            label = if (ch == " ") "空格" else ch,
                            width = if (ch == " ") 96 else if (row.size > 10) 50 else 54,
                            focusRequester = if (!symbols && rowIndex == 0 && chIndex == 0) firstKey else null,
                        ) {
                            text += ch
                            if (shift && !symbols) shift = false
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Key("大写", width = 76, active = shift) { shift = !shift; symbols = false }
                Key("符号", width = 76, active = symbols) { symbols = !symbols }
                if (password) {
                    Key(if (reveal) "隐藏" else "显示", width = 76, active = reveal) { reveal = !reveal }
                }
                Key("空格", width = 84) { text += " " }
                Key("删除", width = 76) { if (text.isNotEmpty()) text = text.dropLast(1) }
                Key("清空", width = 68) { text = "" }
                Key("取消", width = 76) { onDismiss() }
                Key("确定", width = 90, primary = true) { onConfirm(text) }
            }
        }
    }
}