package com.doloh.marstekwidget.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import com.doloh.marstekwidget.R
import com.doloh.marstekwidget.settings.ServerUrlValidator
import com.doloh.marstekwidget.settings.SettingsStore

class DashboardWebActivity : Activity() {
    private var webView: WebView? = null
    private lateinit var status: TextView

    @SuppressLint("SetJavaScriptEnabled") // The dashboard is a read-only first-party PWA; navigation is host-allowlisted below.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val base = SettingsStore(this).load().serverUrl?.let(ServerUrlValidator::normalize)
        if (base == null) { finish(); return }
        val allowed = ServerUrlValidator.trustedHosts(base)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.rgb(7, 21, 34))
        }
        status = TextView(this).apply {
            text = getString(R.string.main_dashboard_loading)
            textSize = 11f
            setTextColor(android.graphics.Color.rgb(160, 176, 187))
            setPadding(dp(14), dp(7), dp(14), dp(7))
        }
        root.addView(status, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        val dashboard = WebView(this)
        webView = dashboard
        root.addView(dashboard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
        SystemBarsInsetHelper.apply(root, 0, 0, 0, 0)
        setContentView(root)

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(dashboard, false)
        dashboard.settings.javaScriptEnabled = true
        dashboard.settings.domStorageEnabled = true
        dashboard.settings.allowFileAccess = false
        dashboard.settings.allowContentAccess = false
        dashboard.settings.javaScriptCanOpenWindowsAutomatically = false
        dashboard.settings.setSupportMultipleWindows(false)
        dashboard.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW // LAN http dashboards are same-origin
        dashboard.webChromeClient = WebChromeClient()
        dashboard.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !(ServerUrlValidator.isTrusted(request.url.toString(), allowed) ||
                    (base.startsWith("http://") && request.url.host == ServerUrlValidator.host(base)))

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                status.visibility = View.GONE
                if (Uri.parse(url).host in allowed) cookies.flush()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    status.visibility = View.VISIBLE
                    status.text = getString(R.string.login_failed)
                }
            }
        }
        dashboard.loadUrl("$base/")
    }

    @Deprecated("Deprecated in Android but retained for WebView history navigation")
    override fun onBackPressed() {
        val current = webView
        if (current?.canGoBack() == true) current.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        webView?.let { it.stopLoading(); it.destroy() }
        webView = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
