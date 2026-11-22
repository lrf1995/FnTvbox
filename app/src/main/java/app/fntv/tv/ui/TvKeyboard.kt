package app.fntv.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.Dp
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

private val SYMBOL_ROWS = listOf(
    listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "-", "="),
    listOf("!", "@", "#", "$", "%", "^", "&", "*", "(", ")", "_", "+"),
    listOf("[", "]", "{", "}", ";", ":", "'", "\"", "<", ">", "?", "|"),
    listOf("~", "`", "\\", "/", ",", ".", " "),
)

private val NUM_ROWS = listOf(
    listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
    listOf(".", ":", "/", "-", "_"),
)

@Composable
private fun Key(
    label: String,
    width: Dp = 54.dp,
    primary: Boolean = false,
    active: Boolean = false,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .width(width)
            .height(40.dp)
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
            fontSize = if (label.length > 3) 11.sp else 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/**
 * 遥控器软键盘（含大写/符号/手机扫码输入）。
 *
 * 按键宽度按实际可用宽度计算，4K 电视把密度报成 3.0（逻辑宽 640dp）时也不会横向溢出。
 */
@Composable
fun TvKeyboardDialog(
    title: String,
    initial: String,
    password: Boolean = false,
    numeric: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    var shift by remember { mutableStateOf(false) }
    var symbols by remember { mutableStateOf(false) }
    var reveal by remember { mutableStateOf(false) }
    var qrOpen by remember { mutableStateOf(false) }
    var numberPad by remember { mutableStateOf(numeric) }
    val firstKey = remember { FocusRequester() }

    val shown = if (password && !reveal) "•".repeat(text.length) else text

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(Color(0xF00A0F1A))
        ) {
            // 数字键盘 10 列，字母/符号 12 列，分别按可用宽度算键宽
            val keyW = if (numberPad) {
                ((maxWidth - 32.dp - 5.dp * 9) / 10).coerceIn(38.dp, 72.dp)
            } else {
                ((maxWidth - 32.dp - 5.dp * 11) / 12).coerceIn(30.dp, 54.dp)
            }
            val actW = ((maxWidth - 32.dp - 6.dp * 8) / 9).coerceIn(40.dp, 84.dp)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(bottom = 6.dp),
                ) {
                    Text(title, color = TextSecondary, fontSize = 12.sp)
                    if (password) {
                        Text(
                            if (reveal) "（明文）" else "（密文）",
                            color = if (reveal) Gold else TextDim,
                            fontSize = 10.sp,
                        )
                    }
                }

                LaunchedEffect(Unit) {
                    repeat(4) { attempt ->
                        runCatching { firstKey.requestFocus() }
                        delay(60L + attempt * 80L)
                    }
                }

                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF101827))
                        .border(1.dp, Accent.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = shown.ifEmpty { " " },
                        color = TextPrimary,
                        fontSize = 17.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(8.dp))

                val letters = if (shift) LETTER_ROWS.map { row -> row.map(String::uppercase) } else LETTER_ROWS
                val rows: List<List<String>> = when {
                    numberPad -> NUM_ROWS
                    symbols -> SYMBOL_ROWS
                    else -> letters
                }

                rows.forEachIndexed { rowIndex, row ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier.padding(bottom = 4.dp),
                    ) {
                        row.forEachIndexed { chIndex, ch ->
                            Key(
                                label = if (ch == " ") "空格" else ch,
                                width = if (ch == " ") keyW * 2 else keyW,
                                focusRequester = if (rowIndex == 0 && chIndex == 0) firstKey else null,
                            ) {
                                text += ch
                                if (shift && !symbols) shift = false
                            }
                        }
                    }
                }

                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Key(if (numberPad) "字母" else "数字", width = actW, active = numberPad) {
                        numberPad = !numberPad
                        symbols = false
                    }
                    if (!numberPad) {
                        Key("大写", width = actW, active = shift) { shift = !shift; symbols = false }
                        Key("符号", width = actW, active = symbols) { symbols = !symbols }
                    }
                    Key("手机输入", width = actW, primary = true) { qrOpen = true }
                    if (password) {
                        Key(if (reveal) "隐藏" else "显示", width = actW, active = reveal) { reveal = !reveal }
                    } else {
                        Key("空格", width = actW) { text += " " }
                    }
                    Key("删除", width = actW) { if (text.isNotEmpty()) text = text.dropLast(1) }
                    Key("清空", width = actW) { text = "" }
                    Key("取消", width = actW) { onDismiss() }
                    Key("确定", width = actW, primary = true) { onConfirm(text) }
                }
            }
        }
    }

    if (qrOpen) {
        QrInputDialog(
            title = title,
            password = password,
            onValue = { value ->
                text = value
                qrOpen = false
            },
            onDismiss = { qrOpen = false },
        )
    }
}