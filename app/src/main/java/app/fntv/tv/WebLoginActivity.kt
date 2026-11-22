package app.fntv.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity

/**
 * 网页登录兜底：直接打开飞牛 fnOS 的网页，让用户在网页里登录。
 *
 * 为什么要这条链路：
 *  - 飞牛 `POST /v/api/v1/login` 对「密码错」和「失败过多被临时限流」都返回 `-15 Password Incorrect`，
 *    用户无法区分，越试锁得越久；
 *  - 网页登录走的是同一套账号，还能顺带处理「应用访问码」和二次验证；
 *  - 登录成功后网页会种下 `Trim-MC-token` Cookie，客户端拿它当 token 用（与 API 登录拿到的等价）。
 *
 * 结果通过 setResult 返回：server（最终基址）+ token。
 */
class WebLoginActivity : ComponentActivity() {

    private var web: WebView? = null
    private var done = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val raw = intent.getStringExtra("server").orEmpty()
        val base = raw.trim().trimEnd('/').ifEmpty { "http://192.168.1.10:5666" }
        val startUrl = if (base.endsWith("/v")) base else "$base/v"

        val w = WebView(this)
        web = w
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.databaseEnabled = true
        w.settings.loadWithOverviewMode = true
        w.settings.useWideViewPort = true
        w.settings.builtInZoomControls = false
        w.settings.userAgentString = w.settings.userAgentString + " FnTV/1.0"

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)

        w.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                tryCatchToken(view?.url ?: startUrl, view)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                tryCatchToken(url ?: view?.url ?: startUrl, view)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = false

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                // 内网 NAS 常见自签证书：与 API 客户端口径一致，直接放行
                handler?.proceed()
            }
        }

        w.loadUrl(startUrl)
        setContentView(w)

        // 登录是异步的（可能跳多个页面），定时轮询 Cookie 直到拿到 token
        w.postDelayed(cookieWatch, 800)
    }

    private val cookieWatch = object : Runnable {
        override fun run() {
            tryCatchToken(web?.url, web)
            if (!done) web?.postDelayed(this, 1200)
        }
    }

    private fun tryCatchToken(url: String?, view: WebView?) {
        if (done) return
        val target = url ?: return
        val cookie = CookieManager.getInstance().getCookie(target) ?: return
        val token = Regex("Trim-MC-token=([^;\\s]+)").find(cookie)?.groupValues?.getOrNull(1)
        if (token.isNullOrBlank()) return

        done = true
        val base = runCatching {
            val u = Uri.parse(target)
            "${u.scheme}://${u.host}${if (u.port > 0) ":${u.port}" else ""}"
        }.getOrNull().orEmpty()

        setResult(
            Activity.RESULT_OK,
            Intent().apply {
                putExtra("token", token)
                putExtra("server", base.ifEmpty { intent.getStringExtra("server") })
            }
        )
        finish()
    }

    override fun onDestroy() {
        web?.removeCallbacks(cookieWatch)
        web?.destroy()
        web = null
        super.onDestroy()
    }

    /** 调试用：把当前 Cookie 名打出来（不含值），排查取不到 token 的情况。 */

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web?.canGoBack() == true) web?.goBack() else super.onBackPressed()
    }
}