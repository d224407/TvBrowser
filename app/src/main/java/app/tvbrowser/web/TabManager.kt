package app.tvbrowser.web

import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import app.tvbrowser.R
import app.tvbrowser.BrowserViewModel
import app.tvbrowser.adblock.AdBlockEngine
import app.tvbrowser.data.Panel
import app.tvbrowser.data.TabState
import app.tvbrowser.data.UrlUtils
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** Owns WebView instances: creation, LRU eviction, lifecycle and client wiring. */
class TabManager(
    private val activity: ComponentActivity,
    private val vm: BrowserViewModel,
    private val adBlock: AdBlockEngine,
    private val fullscreenHost: FrameLayout,
) {
    private val views = LinkedHashMap<Long, WebView>(8, 0.75f, true)
    private val saved = HashMap<Long, Bundle>()
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    var adBlockOn = true

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    private val maxLive: Int = run {
        val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (am.isLowRamDevice) 2 else 4
    }

    private val defaultUa: String by lazy { WebSettings.getDefaultUserAgent(activity) }
    private val desktopUa: String by lazy {
        val v = Regex("Chrome/([\\d.]+)").find(defaultUa)?.groupValues?.get(1) ?: "120.0.0.0"
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$v Safari/537.36"
    }

    val isFullscreen: Boolean get() = customView != null

    fun activeWebView(): WebView? = views[vm.activeId]

    fun webViewFor(tab: TabState): WebView {
        views[tab.id]?.let { return it }
        val wv = create(tab)
        views[tab.id] = wv
        evictExtra()
        return wv
    }

    fun load(tab: TabState, url: String) {
        tab.url = url
        tab.title = ""
        val existing = views[tab.id]
        if (existing != null) existing.loadUrl(url) else webViewFor(tab)
    }

    fun goBack() {
        activeWebView()?.let { if (it.canGoBack()) it.goBack() }
    }

    fun goForward() {
        activeWebView()?.let { if (it.canGoForward()) it.goForward() }
    }

    fun reloadOrStop(tab: TabState) {
        val wv = views[tab.id] ?: return
        if (tab.loading) wv.stopLoading() else wv.reload()
    }

    fun destroy(id: Long) {
        saved.remove(id)
        dispose(id, keepState = false)
    }

    fun destroyAll() {
        exitFullscreen()
        views.keys.toList().forEach { dispose(it, keepState = false) }
    }

    fun pause() {
        CookieManager.getInstance().flush()
        activeWebView()?.onPause()
        views.values.firstOrNull()?.pauseTimers()
    }

    fun resume() {
        views.values.firstOrNull()?.resumeTimers()
        activeWebView()?.onResume()
    }

    /** Keep only the active WebView; others keep their state in memory. */
    fun trim() {
        views.keys.toList().filter { it != vm.activeId }.forEach { dispose(it, keepState = true) }
    }

    fun exitFullscreen(): Boolean {
        val v = customView ?: return false
        val cb = customCallback
        customView = null
        customCallback = null
        fullscreenHost.removeView(v)
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        fullscreenHost.visibility = View.GONE
        cb?.onCustomViewHidden()
        return true
    }

    fun clearCache() {
        activeWebView()?.clearCache(true)
    }

    fun clearCookies() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
    }

    fun applySettingsToAll(reloadForUa: Boolean) {
        adBlockOn = vm.settings.adBlock
        for ((id, wv) in views) {
            applySettings(wv)
            if (reloadForUa && id == vm.activeId) wv.reload()
        }
    }

    private fun evictExtra() {
        while (views.size > maxLive) {
            val victim = views.keys.firstOrNull { it != vm.activeId } ?: return
            dispose(victim, keepState = true)
        }
    }

    private fun dispose(id: Long, keepState: Boolean) {
        val wv = views.remove(id) ?: return
        if (keepState) {
            val b = Bundle()
            wv.saveState(b)
            saved[id] = b
        }
        (wv.parent as? ViewGroup)?.removeView(wv)
        wv.stopLoading()
        wv.destroy()
    }

    private fun create(tab: TabState): WebView {
        val wv = WebView(activity)
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true
        if (Build.VERSION.SDK_INT >= 26) wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        applySettings(wv)
        wv.webViewClient = Client(tab)
        wv.webChromeClient = Chrome(tab)
        wv.setDownloadListener { url, ua, cd, mime, _ ->
            if (Downloads.enqueue(activity, url, ua, cd, mime)) vm.openPanel(Panel.DOWNLOADS)
        }
        adBlockOn = vm.settings.adBlock
        val state = saved.remove(tab.id)
        if (state != null && wv.restoreState(state) != null) {
            // restored from memory
        } else if (tab.url.isNotEmpty()) {
            wv.loadUrl(tab.url)
        }
        return wv
    }

    private fun applySettings(wv: WebView) {
        val s = vm.settings
        val ws = wv.settings
        ws.javaScriptEnabled = s.javascript
        ws.domStorageEnabled = true
        ws.setNeedInitialFocus(false)
        ws.useWideViewPort = true
        ws.loadWithOverviewMode = true
        ws.setSupportMultipleWindows(false)
        ws.javaScriptCanOpenWindowsAutomatically = false
        ws.allowFileAccess = false
        ws.allowContentAccess = false
        ws.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        ws.textZoom = s.textZoom
        ws.userAgentString = if (s.desktopMode) desktopUa else defaultUa
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, s.thirdPartyCookies)
        }
    }

    private fun injectCosmetic(view: WebView, url: String) {
        if (!adBlockOn) return
        val host = UrlUtils.host(url)
        if (host.isEmpty()) return
        val css = adBlock.cosmeticCss(host)
        if (css.isEmpty()) return
        val js = "(function(){var s=document.getElementById('__tvb');if(!s){s=document.createElement('style');" +
            "s.id='__tvb';(document.head||document.documentElement).appendChild(s)}s.textContent=" +
            JSONObject.quote(css) + "})()"
        view.evaluateJavascript(js, null)
    }

    private inner class Client(val tab: TabState) : WebViewClient() {
        @Volatile
        private var pageHost = ""

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme?.lowercase()
            return scheme !in INTERNAL_SCHEMES
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (!adBlockOn || request.isForMainFrame) return null
            val url = request.url.toString()
            if (!url.startsWith("http")) return null
            val type = AdBlockEngine.guessType(url, request.requestHeaders?.get("Accept"))
            return if (adBlock.shouldBlock(url, pageHost, type)) {
                WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            } else {
                null
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            pageHost = UrlUtils.host(url)
            tab.loading = true
            if (!url.startsWith(ERROR_PAGE)) tab.url = url
        }

        override fun onPageFinished(view: WebView, url: String) {
            tab.loading = false
            tab.canBack = view.canGoBack()
            tab.canForward = view.canGoForward()
            if (url.startsWith(ERROR_PAGE)) return
            tab.title = view.title.orEmpty()
            injectCosmetic(view, url)
            if (url.startsWith("http")) vm.recordVisit(url, tab.title)
            vm.persistTabs()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            if (!url.startsWith(ERROR_PAGE)) tab.url = url
            tab.canBack = view.canGoBack()
            tab.canForward = view.canGoForward()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            val failing = request.url.toString()
            tab.url = failing
            val q = "?t=" + Uri.encode(activity.getString(R.string.error_title)) +
                "&d=" + Uri.encode(error.description.toString()) +
                "&r=" + Uri.encode(activity.getString(R.string.retry)) +
                "&u=" + Uri.encode(failing)
            view.loadUrl(ERROR_PAGE + q)
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            handler.post {
                dispose(tab.id, keepState = false)
                tab.generation++
            }
            return true
        }
    }

    private inner class Chrome(val tab: TabState) : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            tab.progress = newProgress
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            if (title.isNullOrEmpty() || view.url?.startsWith(ERROR_PAGE) == true) return
            tab.title = title
        }

        override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            customCallback = callback
            fullscreenHost.addView(
                view,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            fullscreenHost.visibility = View.VISIBLE
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            view.requestFocus()
        }

        override fun onHideCustomView() {
            exitFullscreen()
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            val ok = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
            if (ok.isNotEmpty()) request.grant(ok.toTypedArray()) else request.deny()
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            callback.invoke(origin, false, false)
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: WebChromeClient.FileChooserParams,
        ): Boolean {
            filePathCallback.onReceiveValue(null)
            return true
        }

        override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
            AlertDialog.Builder(activity)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }

        override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
            AlertDialog.Builder(activity)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }
    }

    companion object {
        const val ERROR_PAGE = "file:///android_asset/error.html"
        private val INTERNAL_SCHEMES = setOf("http", "https", "about", "data", "blob", "javascript", "file")
    }
}
