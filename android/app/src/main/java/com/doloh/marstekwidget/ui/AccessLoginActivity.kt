package com.doloh.marstekwidget.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import com.doloh.marstekwidget.R
import com.doloh.marstekwidget.data.AccessCookie
import com.doloh.marstekwidget.settings.ServerUrlValidator
import com.doloh.marstekwidget.settings.SettingsStore
import com.doloh.marstekwidget.widget.WidgetRefreshScheduler

class AccessLoginActivity : Activity() {
    private lateinit var status: TextView
    private var webView: WebView? = null
    private var completed = false
    private var baseUrl: String = ""
    private var allowedHosts: Set<String> = emptySet()

    @SuppressLint("SetJavaScriptEnabled") // Required by Cloudflare Access OTP; navigation is restricted to the configured server and *.cloudflareaccess.com over HTTPS.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val base = SettingsStore(this).load().serverUrl?.let(ServerUrlValidator::normalize)
        if (base == null || !base.startsWith("https://")) { finish(); return }
        baseUrl = base
        allowedHosts = ServerUrlValidator.trustedHosts(base)
        val serverHost = allowedHosts.firstOrNull()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(8))
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.login_title)
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        })
        status = TextView(this).apply {
            text = getString(R.string.login_instructions)
            textSize = 14f
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(status, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        val loginWebView = WebView(this)
        webView = loginWebView
        root.addView(loginWebView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
        SystemBarsInsetHelper.apply(root, 16, 14, 16, 8)
        setContentView(root)

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(loginWebView, false)
        loginWebView.settings.javaScriptEnabled = true
        loginWebView.settings.domStorageEnabled = true
        loginWebView.settings.allowFileAccess = false
        loginWebView.settings.allowContentAccess = false
        loginWebView.settings.javaScriptCanOpenWindowsAutomatically = false
        loginWebView.settings.setSupportMultipleWindows(false)
        loginWebView.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        loginWebView.webChromeClient = WebChromeClient()
        loginWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                return if (ServerUrlValidator.isTrusted(uri.toString(), allowedHosts)) {
                    false
                } else {
                    status.text = getString(R.string.login_blocked)
                    true
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
                if (uri.host == serverHost && hasAccessCookie()) {
                    if (completed) return
                    completed = true
                    cookies.flush()
                    status.text = getString(R.string.widget_connected)
                    WidgetRefreshScheduler.ensurePeriodic(this@AccessLoginActivity)
                    WidgetRefreshScheduler.refreshNow(this@AccessLoginActivity)
                    setResult(RESULT_OK)
                    finish()
                } else {
                    status.text = if (uri.host == serverHost) {
                        getString(R.string.login_instructions)
                    } else {
                        getString(R.string.login_waiting)
                    }
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) status.text = getString(R.string.login_failed)
            }
        }
        loginWebView.loadUrl("$baseUrl/")
    }

    private fun hasAccessCookie(): Boolean = AccessCookie.present(ServerUrlValidator.statusUrl(baseUrl))

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        webView?.let { it.stopLoading(); it.destroy() }
        webView = null
        super.onDestroy()
    }
}
