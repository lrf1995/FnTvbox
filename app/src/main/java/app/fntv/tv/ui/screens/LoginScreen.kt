package app.fntv.tv.ui.screens

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fntv.tv.WebLoginActivity
import app.fntv.tv.data.Discovery
import app.fntv.tv.data.FnException
import app.fntv.tv.data.Prefs
import app.fntv.tv.data.Repo
import app.fntv.tv.ui.Accent
import app.fntv.tv.ui.AccentSoft
import app.fntv.tv.ui.Bg
import app.fntv.tv.ui.Danger
import app.fntv.tv.ui.LogoMark
import app.fntv.tv.ui.TextDim
import app.fntv.tv.ui.TextPrimary
import app.fntv.tv.ui.TextSecondary
import app.fntv.tv.ui.TvButton
import app.fntv.tv.ui.TvKeyboardDialog
import app.fntv.tv.ui.requestFocusWhenReady
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
private fun InputRow(
    label: String,
    value: String,
    placeholder: String,
    isPassword: Boolean = false,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.padding(bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = TextSecondary, fontSize = 12.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                if (focused) "按 OK 用键盘编辑" else "可编辑",
                color = if (focused) Accent else TextDim,
                fontSize = 10.sp,
            )
        }
        Spacer(Modifier.height(5.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (focused) Color(0xFF1A2740) else Color(0xFF101827))
                .border(
                    if (focused) 2.dp else 1.dp,
                    if (focused) Accent else Color.White.copy(alpha = 0.12f),
                    RoundedCornerShape(10.dp)
                )
                .onFocusChanged { focused = it.isFocused }
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            val shown = when {
                value.isEmpty() -> placeholder
                isPassword -> "•".repeat(value.length)
                else -> value
            }
            Text(
                shown,
                color = if (value.isEmpty()) TextDim else TextPrimary,
                fontSize = 15.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun LoginScreen(onLogged: () -> Unit) {
    var server by remember { mutableStateOf(Prefs.server) }
    var username by remember { mutableStateOf(Prefs.username) }
    var password by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var cooldown by remember { mutableIntStateOf(0) }
    var discovering by remember { mutableStateOf(false) }
    var discoverHint by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val serverFocus = remember { FocusRequester() }
    val userFocus = remember { FocusRequester() }
    val passFocus = remember { FocusRequester() }
    val connectFocus = remember { FocusRequester() }
    var lastEditing by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(cooldown) {
        if (cooldown > 0) {
            delay(1000)
            cooldown -= 1
        }
    }

    val webLogin = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val token = result.data?.getStringExtra("token").orEmpty()
            val base = result.data?.getStringExtra("server").orEmpty()
            if (token.isNotEmpty()) {
                loading = true
                error = null
                scope.launch {
                    try {
                        Repo.adoptSession(base.ifEmpty { server }, token)
                        onLogged()
                    } catch (e: Exception) {
                        error = "网页登录成功，但保存会话失败：${e.message}"
                    } finally {
                        loading = false
                    }
                }
            } else {
                error = "网页登录没有拿到 token，请确认已经登录成功"
            }
        }
    }

    fun submit() {
        if (loading || cooldown > 0) return
        error = null
        if (server.isBlank() || username.isBlank() || password.isEmpty()) {
            error = "请把地址、用户名、密码都填完整（方向键选中输入框，按 OK 编辑）"
            return
        }
        loading = true
        scope.launch {
            try {
                Repo.login(server, username, password)
                onLogged()
            } catch (e: Exception) {
                val raw = e.message ?: "登录失败"
                error = friendlyLoginError(raw)
                if (raw.contains("-15", true) ||
                    raw.contains("Password Incorrect", true) ||
                    raw.contains("密码")
                ) {
                    cooldown = 30
                }
            } finally {
                loading = false
            }
        }
    }

    // 表单卡片：宽度自适应，小屏（<640dp）也保证在屏幕内
    val formCard: @Composable () -> Unit = {
        Column(
            Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xCC0C1424))
                .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(18.dp))
                .padding(22.dp)
        ) {
            Text("连接到你的飞牛 NAS", color = TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "地址填 NAS 的 IP + 端口，例如 192.168.1.10:5666",
                color = TextDim,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                TvButton(
                    text = if (discovering) "正在扫描…" else "自动发现 NAS",
                    onClick = {
                        if (discovering) return@TvButton
                        discovering = true
                        discoverHint = null
                        scope.launch {
                            try {
                                val list = Discovery.discoverNas()
                                if (list.isEmpty()) {
                                    discoverHint = "没扫到 NAS：确认电视和 NAS 在同一 Wi-Fi，或手动填地址"
                                } else {
                                    server = list.first()
                                    discoverHint = "发现 ${list.size} 个，已填入 ${list.first()}"
                                }
                            } catch (e: Exception) {
                                discoverHint = "扫描失败：${e.message}"
                            } finally {
                                discovering = false
                            }
                        }
                    },
                )
                if (discoverHint != null) {
                    Spacer(Modifier.width(10.dp))
                    Text(discoverHint!!, color = Accent, fontSize = 10.sp, lineHeight = 14.sp)
                }
            }
            Spacer(Modifier.height(10.dp))

            InputRow("服务器地址", server, "192.168.31.100:5666", focusRequester = serverFocus) {
                lastEditing = "server"; editing = "server"
            }
            InputRow("用户名", username, "NAS 本地账号", focusRequester = userFocus) {
                lastEditing = "username"; editing = "username"
            }
            InputRow("密码", password, "区分大小写", isPassword = true, focusRequester = passFocus) {
                lastEditing = "password"; editing = "password"
            }

            if (error != null) {
                Text(error!!, color = Danger, fontSize = 12.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(8.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                TvButton(
                    text = when {
                        loading -> "连接中…"
                        cooldown > 0 -> "请等待 ${cooldown}s"
                        else -> "连接并进入"
                    },
                    onClick = { submit() },
                    primary = true,
                    focusRequester = connectFocus,
                )
                Spacer(Modifier.width(10.dp))
                TvButton(
                    text = "用飞牛账号登录",
                    onClick = {
                        if (loading) return@TvButton
                        if (server.isBlank()) {
                            error = "请先填服务器地址（可点「自动发现 NAS」），再打开网页登录"
                            return@TvButton
                        }
                        webLogin.launch(
                            Intent(context, WebLoginActivity::class.java)
                                .putExtra("server", server)
                        )
                    },
                )
                if (loading) {
                    Spacer(Modifier.width(14.dp))
                    CircularProgressIndicator(
                        color = Accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "账号密码登录走影视接口；若提示被拒绝，点「用飞牛账号登录」——先登 fnOS（和飞牛 App 同一个账号），会自动进影视并取回会话。",
                color = TextDim,
                fontSize = 10.sp,
                lineHeight = 15.sp,
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF071022), Color(0xFF0A0E18), Color(0xFF05070C))
                )
            )
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxWidth < 900.dp
            if (compact) {
                // 小屏/高密度 4K 电视：上下堆叠，整页可滚动
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 28.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LogoMark(38)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("FnTV", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Black)
                            Text("飞牛影视 · 电视版", color = TextDim, fontSize = 11.sp)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    formCard()
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "局域网直连 · 不经过第三方服务器 · 进度回传飞牛",
                        color = TextDim,
                        fontSize = 10.sp,
                    )
                }
            } else {
                Row(
                    Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.width(300.dp).padding(end = 40.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LogoMark(46)
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text("FnTV", color = TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Black)
                                Text("飞牛影视 · 电视版", color = TextDim, fontSize = 12.sp)
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        Text(
                            "把 NAS 里的电影、剧集和番剧，\n变成沙发上用遥控器就能看的影院。",
                            color = TextSecondary,
                            fontSize = 14.sp,
                            lineHeight = 24.sp,
                        )
                        Spacer(Modifier.height(22.dp))
                        Text("· 局域网直连，不经过任何第三方服务器", color = TextDim, fontSize = 12.sp)
                        Spacer(Modifier.height(6.dp))
                        Text("· 支持 H.264 / HEVC 硬解与断点续播", color = TextDim, fontSize = 12.sp)
                        Spacer(Modifier.height(6.dp))
                        Text("· 播放进度回传飞牛，手机/电脑接着看", color = TextDim, fontSize = 12.sp)
                    }
                    formCard()
                }
            }
        }

        // 首焦点给「服务器地址」，进来就能按 OK 打开键盘；而不是落在按钮上
        LaunchedEffect(Unit) { requestFocusWhenReady(serverFocus) }
        LaunchedEffect(editing) {
            if (editing == null) {
                when (lastEditing) {
                    "server" -> requestFocusWhenReady(serverFocus)
                    "username" -> requestFocusWhenReady(userFocus)
                    "password" -> requestFocusWhenReady(passFocus)
                    else -> Unit
                }
            }
        }
    }

    when (editing) {
        "server" -> TvKeyboardDialog("服务器地址", server, numeric = true, onDismiss = { editing = null }) {
            server = it.trim(); editing = null
        }
        "username" -> TvKeyboardDialog("用户名", username, onDismiss = { editing = null }) {
            username = it.trim(); editing = null
        }
        "password" -> TvKeyboardDialog("密码", password, password = true, onDismiss = { editing = null }) {
            password = it; editing = null
        }
    }
}

/** 把飞牛返回的英文错误码翻成可操作的中文提示。 */
private fun friendlyLoginError(raw: String): String {
    val lower = raw.lowercase()
    return when {
        lower.contains("password incorrect") || lower.contains("-15") ||
            raw.contains("密码错误") || raw.contains("密码不正确") ->
            "登录被拒绝（$raw）。飞牛的 -15 不区分「密码错」和「失败过多被临时限流」，" +
                "限流期间即使密码正确也一律报这个。请先核对密码（注意大小写/符号），" +
                "若确认无误请等 10 分钟再试一次，不要连续点；也可以直接点「用网页登录」绕过。"
        lower.contains("user not exist") || lower.contains("not found") || raw.contains("用户不存在") ->
            "账号不存在：请填 fnOS 的本地用户名（不是邮箱，也不是飞牛云 ID）。"
        raw.contains("访问码") ->
            "这台 NAS 开了「应用访问码」：请先用「用网页登录」完成门禁验证。"
        else -> raw
    }
}