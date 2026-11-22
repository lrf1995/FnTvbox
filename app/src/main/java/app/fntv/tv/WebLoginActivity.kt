package app.fntv.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * 用「飞牛账号」登录：
 *
 * 1. 先打开 fnOS 系统首页，用你的 NAS 账号（就是登录飞牛 App 的那个）登录；
 * 2. 检测到已登录后自动跳到 `/v`（飞牛影视）；
 * 3. 影视网页通过 NAS 的 app-auth/SSO 拿到会话，种下 `Trim-MC-token`；
 * 4. 客户端读取该 Cookie 作为 API token。
 *
 * 这样绕开了 `/v/api/v2/user/loginByPassword`（它对部分账号会返回 -15），
 * 也与官方「先登 NAS、再进影视」的行为一致。
 */
class WebLoginActivity : ComponentActivity() {

    private var web: WebView? = null
    private var hint: TextView? = null
    private var done = false
    private var wentToV = false
    private var base = ""
    private var tries = 0
    private var sawPassword = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val raw = intent.getStringExtra("server").orEmpty().trim().trimEnd('/')
        base = raw.ifEmpty { "http://192.168.31.100:5666" }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0F1A"))
        }

        hint = TextView(this).apply {
            text = "① 用飞牛账号登录（和飞牛 App 同一个账号）→ ② 登录后自动进入影视"
            setTextColor(Color.parseColor("#9AA6BC"))
            textSize = 15f
            setPadding(48, 28, 48, 20)
        }
        root.addView(hint)

        val w = WebView(this)
        web = w
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.databaseEnabled = true
        w.settings.loadWithOverviewMode = true
        w.settings.useWideViewPort = true
        w.settings.userAgentString = w.settings.userAgentString + " FnTV/1.1"

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)

        w.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                tryCatchToken(view?.url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                tryCatchToken(url ?: view?.url)
                detectLoggedIn(view)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = false

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                // 内网 NAS 常见自签证书
                handler?.proceed()
            }
        }

        root.addView(
            w,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        setContentView(root)

        w.loadUrl("$base/")
        w.postDelayed(watch, 900)
    }

    private val watch = object : Runnable {
        override fun run() {
            tryCatchToken(web?.url)
            detectLoggedIn(web)
            if (!done) web?.postDelayed(this, 1200)
        }
    }

    /** 登录成功（页面上没有密码输入框、且不在 /login）就跳到影视。 */
    private fun detectLoggedIn(view: WebView?) {
        if (view == null || done || wentToV) return
        tries++
        view.evaluateJavascript(
            "(function(){try{var p=document.querySelector('input[type=password]');" +
                "var path=location.pathname;" +
                "if(p) return 'P';" +
                "if(path.indexOf('login')>=0) return 'L';" +
                "return 'OK';}catch(e){return 'X';}})()"
        ) { result ->
            val r = result.orEmpty()
            val url = view.url.orEmpty()
            val alreadyV = Uri.parse(url).path?.startsWith("/v") == true
            val hasCookie = !CookieManager.getInstance().getCookie(url).isNullOrBlank()

            when {
                r.contains("P") -> sawPassword = true
                r.contains("OK") && !alreadyV && (sawPassword || hasCookie) -> {
                    wentToV = true
                    runOnUiThread {
                        hint?.text = "已登录飞牛账号，正在进入影视…（如出现授权确认页，请点允许）"
                    }
                    view.loadUrl("$base/v")
                }
                tries > 40 && !wentToV -> {
                    wentToV = true
                    view.loadUrl("$base/v")
                }
            }
        }
    }

    private fun tryCatchToken(url: String?) {
        if (done || url == null) return
        val cookie = CookieManager.getInstance().getCookie(url) ?: return
        val token = Regex("Trim-MC-token=([^;\\s]+)").find(cookie)?.groupValues?.getOrNull(1)
        if (token.isNullOrBlank()) return

        done = true
        val baseUrl = runCatching {
            val u = Uri.parse(url)
            "${u.scheme}://${u.host}${if (u.port > 0) ":${u.port}" else ""}"
        }.getOrNull().orEmpty()

        setResult(
            Activity.RESULT_OK,
            Intent().apply {
                putExtra("token", token)
                putExtra("server", baseUrl.ifEmpty { base })
            }
        )
        finish()
    }

    override fun onDestroy() {
        web?.removeCallbacks(watch)
        web?.destroy()
        web = null
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web?.canGoBack() == true) web?.goBack() else super.onBackPressed()
    }
}