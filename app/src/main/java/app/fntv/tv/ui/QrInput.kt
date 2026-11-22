package app.fntv.tv.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.concurrent.thread

/**
 * 手机扫码输入：
 *
 * TV 端临时起一个只读/单表单的 HTTP 服务，二维码里是 `http://<TV IP>:<port>/t/<随机串>`。
 * 手机连同一个 Wi-Fi 扫码打开页面，输入文字点提交，TV 端立刻收到并填进当前输入框。
 *
 * 为什么这样做：遥控器打密码/片名是最痛的环节；这条路不依赖任何第三方服务，
 * 数据不出局域网，页面也只在该输入框打开期间存在（关闭即停服务）。
 */
class LanInputServer {

    private var socket: ServerSocket? = null
    private var worker: Thread? = null
    private var token: String = ""
    private var pageTitle: String = ""
    private var passwordMode: Boolean = false
    private var onText: (String) -> Unit = {}

    val port: Int get() = socket?.localPort ?: 0

    fun start(
        title: String,
        password: Boolean,
        onText: (String) -> Unit,
    ): Int {
        if (socket != null) return port
        pageTitle = title
        passwordMode = password
        this.onText = onText
        token = (1..6).map { "abcdefghjkmnpqrstuvwxyz23456789".random() }.joinToString("")

        val ss = ServerSocket(0)
        socket = ss
        worker = thread(name = "lan-input", isDaemon = true) {
            while (!ss.isClosed) {
                val client = try {
                    ss.accept()
                } catch (e: Exception) {
                    break
                }
                runCatching { handle(client) }
            }
        }
        return port
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
        worker = null
    }

    fun path(): String = "/t/$token"

    private fun handle(client: Socket) {
        client.use { c ->
            c.soTimeout = 10_000
            val reader = BufferedReader(InputStreamReader(c.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0) ?: "GET"
            val path = parts.getOrNull(1) ?: "/"

            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }

            if (!path.startsWith("/t/$token")) {
                respond(c.getOutputStream(), 404, "text/plain; charset=utf-8", "not found")
                return
            }

            if (method.equals("POST", true)) {
                val body = CharArray(contentLength.coerceAtMost(64 * 1024))
                var read = 0
                while (read < body.size) {
                    val n = reader.read(body, read, body.size - read)
                    if (n <= 0) break
                    read += n
                }
                val raw = String(body, 0, read)
                val value = parseFormValue(raw, "text").trim()
                if (value.isNotEmpty()) onText(value)
                respond(c.getOutputStream(), 200, "text/html; charset=utf-8", resultHtml())
            } else {
                respond(c.getOutputStream(), 200, "text/html; charset=utf-8", formHtml())
            }
        }
    }

    private fun parseFormValue(body: String, key: String): String {
        for (pair in body.split('&')) {
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            val k = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
            if (k != key) continue
            return URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
        }
        return ""
    }

    private fun respond(out: OutputStream, code: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $code OK\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun formHtml(): String {
        val inputType = if (passwordMode) "password" else "text"
        val safeTitle = pageTitle.replace("<", "&lt;").replace(">", "&gt;")
        return """
            <!doctype html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>$safeTitle</title>
            <style>
              body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;background:#0b1020;color:#eef2fb;
                   display:flex;min-height:100vh;align-items:center;justify-content:center;margin:0;padding:24px}
              .card{width:100%;max-width:520px;background:#141d2f;border:1px solid #24314a;border-radius:16px;padding:24px}
              h1{font-size:18px;margin:0 0 6px} p{color:#94a3b8;font-size:13px;margin:0 0 18px}
              input{width:100%;box-sizing:border-box;font-size:18px;padding:14px;border-radius:10px;border:1px solid #33415c;
                    background:#0e1626;color:#eef2fb}
              button{width:100%;margin-top:14px;font-size:17px;padding:14px;border:0;border-radius:10px;
                     background:#4f9cff;color:#04101f;font-weight:600}
              small{display:block;color:#64748b;margin-top:12px;font-size:11px}
            </style></head><body><div class="card">
            <h1>$safeTitle</h1>
            <p>在手机上输入，点「发送到电视」即可自动填入。</p>
            <form method="POST" action="${path()}">
              <input type="$inputType" name="text" autocomplete="off" autocapitalize="off" spellcheck="false" autofocus>
              <button type="submit">发送到电视</button>
            </form>
            <small>只在你打开输入框期间可用，关闭后服务即停止。</small>
            </div></body></html>
        """.trimIndent()
    }

    private fun resultHtml(): String = """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1"><title>已发送</title>
        <style>body{font-family:-apple-system,sans-serif;background:#0b1020;color:#eef2fb;display:flex;
              min-height:100vh;align-items:center;justify-content:center;margin:0}
              div{text-align:center}h1{font-size:22px;margin-bottom:8px}p{color:#94a3b8;font-size:14px}</style>
        </head><body><div><h1>✓ 已发送到电视</h1><p>可以关闭这个页面了</p></div></body></html>
    """.trimIndent()
}

/** 取本机局域网 IPv4（优先 192.168/10/172 网段）。 */
fun localIpv4(): String? {
    return runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()
}

private fun makeQr(text: String, size: Int = 512): Bitmap {
    val hints = mapOf(
        EncodeHintType.CHARACTER_SET to "UTF-8",
        EncodeHintType.MARGIN to 1,
    )
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    for (x in 0 until matrix.width) {
        for (y in 0 until matrix.height) {
            bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    return bmp
}

/**
 * 扫码输入面板：显示二维码 + 等待手机提交。
 * [onValue] 在收到文字时回调（主线程），调用方负责把值写进输入框。
 */
@Composable
fun QrInputDialog(
    title: String,
    password: Boolean,
    onValue: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val server = remember { LanInputServer() }
    var status by remember { mutableStateOf("正在准备二维码…") }
    var url by remember { mutableStateOf("") }
    var qr by remember { mutableStateOf<Bitmap?>(null) }
    val ip = remember { localIpv4() }

    DisposableEffect(Unit) {
        if (ip == null) {
            status = "没找到电视的局域网 IP，请确认电视已联网"
        } else {
            val port = runCatching {
                server.start(title, password) { value ->
                    scope.launch(Dispatchers.Main) {
                        status = "已收到，正在填入…"
                        onValue(value)
                    }
                }
            }.getOrElse { e ->
                Log.w("QrInput", "启动输入服务失败", e)
                -1
            }
            if (port > 0) {
                val u = "http://$ip:$port${server.path()}"
                url = u
                status = "用手机扫码，或打开下面的地址"
                runCatching { qr = makeQr(u) }
            } else {
                status = "启动失败：电视可能禁止了局域网访问"
            }
        }
        onDispose { server.stop() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xF00A0F1A))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("手机扫码输入", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "手机连同一个 Wi-Fi，扫码后在手机上输入「$title」，点发送即可",
                color = TextSecondary,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(18.dp))

            Box(
                Modifier
                    .size(300.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White)
                    .padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = qr
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "扫码输入二维码",
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(status, color = Color(0xFF334155), fontSize = 12.sp, textAlign = TextAlign.Center)
                }
            }

            Spacer(Modifier.height(14.dp))
            if (url.isNotEmpty()) {
                Text(url, color = Accent, fontSize = 13.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text(status, color = TextDim, fontSize = 12.sp)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton("取消", onClick = onDismiss)
            }
        }
    }
}