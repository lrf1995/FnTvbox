package app.fntv.tv.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fntv.tv.data.Prefs
import app.fntv.tv.data.Repo
import app.fntv.tv.ui.Accent
import app.fntv.tv.ui.Danger
import app.fntv.tv.ui.Surface1
import app.fntv.tv.ui.Surface2
import app.fntv.tv.ui.TextDim
import app.fntv.tv.ui.TextPrimary
import app.fntv.tv.ui.TextSecondary
import coil.Coil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页：连接 / 播放 / 数据 / 关于 四组卡片。
 *
 * TV 上不用 Material 的 Switch（焦点与摇杆操作体验都差），
 * 开关用「一行文字 + 右侧开/关胶囊」表示，整行可点击切换。
 */
@Composable
fun SettingsScreen(
    onLoggedOut: () -> Unit,
    onRefreshHome: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var autoNext by remember { mutableStateOf(Prefs.autoNextEpisode) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusSeq by remember { mutableIntStateOf(0) }
    val firstFocus = remember { FocusRequester() }

    fun notify(text: String) {
        statusSeq += 1
        status = text
    }

    // LazyColumn 首项要等布局挂载后才有焦点节点，先试一次，失败再等一帧重试。
    LaunchedEffect(Unit) {
        runCatching { firstFocus.requestFocus() }
        delay(120)
        runCatching { firstFocus.requestFocus() }
    }

    // 提示语几秒后自动消失，不打断操作。
    LaunchedEffect(statusSeq) {
        if (statusSeq > 0) {
            delay(2600)
            status = null
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 8.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Column(Modifier.fillMaxWidth()) {
                Text("设置", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("账号、播放偏好与本机缓存", color = TextDim, fontSize = 12.sp)
            }
        }

        /* ── 连接 ── */
        item {
            SettingsGroup("连接") {
                SettingRow(
                    title = "服务器",
                    value = Repo.server.ifEmpty { "未连接" },
                    valueColor = if (Repo.server.isEmpty()) Danger else TextSecondary,
                    focusRequester = firstFocus,
                )
                SettingRow(
                    title = "账号",
                    value = Repo.username.ifEmpty { "未登录" },
                )
                SettingRow(
                    title = "登录状态",
                    subtitle = "token 只保存在本机，不显示明文",
                    value = if (Repo.isLoggedIn) "token 有效" else "token 无效",
                    valueColor = if (Repo.isLoggedIn) Accent else Danger,
                )
                SettingRow(
                    title = "重新登录",
                    subtitle = "退出当前账号并回到登录页",
                    value = "退出",
                    onAction = {
                        Repo.logout()
                        onLoggedOut()
                    },
                )
            }
        }

        /* ── 播放 ── */
        item {
            SettingsGroup("播放") {
                SettingRow(
                    title = "自动连播下一集",
                    subtitle = "一集看完自动播下一集",
                    onAction = {
                        autoNext = !autoNext
                        Prefs.autoNextEpisode = autoNext
                    },
                    trailing = { TogglePill(autoNext) },
                )
            }
        }

        /* ── 数据 ── */
        item {
            SettingsGroup("数据") {
                SettingRow(
                    title = "刷新首页缓存",
                    subtitle = "清空内存缓存，重新向 NAS 拉取首页",
                    value = "刷新",
                    onAction = {
                        Repo.invalidate()
                        onRefreshHome()
                        notify("已刷新")
                    },
                )
                SettingRow(
                    title = "清空图片缓存",
                    subtitle = "删除海报缩略图的内存与磁盘缓存",
                    onAction = {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                try {
                                    val loader = Coil.imageLoader(context)
                                    loader.diskCache?.clear()
                                    loader.memoryCache?.clear()
                                    "图片缓存已清空"
                                } catch (e: Exception) {
                                    "清空失败：${e.message ?: "未知错误"}"
                                }
                            }
                            notify(result)
                        }
                    },
                )
                status?.let { text ->
                    Text(
                        text = text,
                        color = Accent,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 18.dp, top = 8.dp, bottom = 6.dp),
                    )
                }
            }
        }

        /* ── 关于 ── */
        item {
            SettingsGroup("关于") {
                SettingRow("版本", value = "1.0.0")
                SettingRow(
                    title = "许可证",
                    subtitle = "协议：GPL-3.0（派生自 fntv-electron / AniBox 的公开协议实现）",
                )
                SettingRow(
                    title = "免责声明",
                    subtitle = "非飞牛官方客户端，仅用于播放你自己 NAS 上的媒体",
                )
            }
        }
    }
}

/* ───────────────────────── 分组卡片 ───────────────────────── */

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 2.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(16.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Accent)
            )
            Spacer(Modifier.width(10.dp))
            Text(title, color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Surface1)
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            content()
        }
    }
}

/* ───────────────────────── 可聚焦设置行 ───────────────────────── */

/**
 * 一行设置：整行是一个焦点单元（56dp、圆角 12dp、Surface1 底、焦点时 Accent 边框 + 亮底）。
 * onAction 为空时只可聚焦、不响应确认（用于纯信息行）；trailing 放开关胶囊等。
 */
@Composable
private fun SettingRow(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    valueColor: Color = TextSecondary,
    focusRequester: FocusRequester? = null,
    onAction: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val bg by animateColorAsState(
        targetValue = if (focused) Color(0xFF1D2A44) else Surface1,
        label = "settingRowBg",
    )
    val shape = RoundedCornerShape(12.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(shape)
            .background(bg)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Accent else Color.Transparent,
                shape = shape,
            )
            .onFocusChanged { focused = it.isFocused }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .then(
                if (onAction != null) {
                    Modifier.clickable(onClick = onAction)
                } else {
                    Modifier.focusable()
                }
            )
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (focused) TextPrimary else TextPrimary.copy(alpha = 0.92f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    color = if (focused) TextSecondary else TextDim,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (value != null) {
            Spacer(Modifier.width(14.dp))
            Text(
                text = value,
                color = if (focused) TextPrimary else valueColor,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (trailing != null) {
            Spacer(Modifier.width(14.dp))
            trailing()
        }
    }
}

/* ───────────────────────── 开 / 关 胶囊 ───────────────────────── */

@Composable
private fun TogglePill(on: Boolean) {
    Box(
        modifier = Modifier
            .width(48.dp)
            .height(26.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(if (on) Accent else Surface2)
            .border(
                width = 1.dp,
                color = if (on) Accent else Color.White.copy(alpha = 0.12f),
                shape = RoundedCornerShape(13.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (on) "开" else "关",
            color = if (on) Color(0xFF04101F) else TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}