package app.fntv.tv.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LogoMark(size: Int = 30) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size / 4).dp))
            .background(Accent),
        contentAlignment = Alignment.Center,
    ) {
        Text("F", color = Color(0xFF04101F), fontSize = (size * 0.58).sp, fontWeight = FontWeight.Black)
    }
}

@Composable
fun TopTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bg by animateColorAsState(
        when {
            focused -> Color(0xFF24344F)
            selected -> Color(0xFF17233A)
            else -> Color.Transparent
        },
        label = "tabBg",
    )
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, label = "tabScale")

    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .height(38.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(bg)
            .border(
                if (focused) 2.dp else 1.dp,
                if (focused) Color.White.copy(alpha = 0.75f)
                else if (selected) Accent.copy(alpha = 0.45f) else Color.Transparent,
                RoundedCornerShape(19.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected || focused) TextPrimary else TextSecondary,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
fun TopBar(
    tab: Int,
    onTab: (Int) -> Unit,
    showTabs: Boolean = true,
    title: String? = null,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .padding(horizontal = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            var focused by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (focused) Color(0xFF2A3A57) else Color.Transparent)
                    .border(
                        if (focused) 2.dp else 0.dp,
                        if (focused) Color.White.copy(alpha = 0.8f) else Color.Transparent,
                        RoundedCornerShape(18.dp)
                    )
                    .onFocusChanged { focused = it.isFocused }
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "返回",
                    tint = TextPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
        }

        LogoMark()
        Spacer(Modifier.width(10.dp))
        Text("FnTV", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text("飞牛影视 · 电视版", color = TextDim, fontSize = 11.sp)

        if (title != null) {
            Spacer(Modifier.width(18.dp))
            Text("|", color = TextDim, fontSize = 14.sp)
            Spacer(Modifier.width(18.dp))
            Text(title, color = TextSecondary, fontSize = 14.sp, maxLines = 1)
        }

        Spacer(Modifier.weight(1f))

        if (showTabs) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TopTab("首页", tab == 0) { onTab(0) }
                TopTab("搜索", tab == 1) { onTab(1) }
                TopTab("设置", tab == 2) { onTab(2) }
            }
        }
        trailing?.invoke()
    }
}