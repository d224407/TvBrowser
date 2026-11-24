package app.tvbrowser.web

import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import app.tvbrowser.data.Settings
import app.tvbrowser.data.UrlUtils
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** System WebView engine. */
class WebViewEngine(activity: ComponentActivity, private val host: EngineHost) : WebEngine {
    private val wv = WebView(activity)
    private val main = Handler(Looper.getMainLooper())
    private val defaultUa: String = WebSettings.getDefaultUserAgent(activity)
    private val desktopUa: String = run {
        val v = Regex("Chrome/([\\d.]+)").find(defaultUa)?.groupValues?.get(1) ?: "120.0.0.0"
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$v Safari/537.36"
    }

    override val view: View get() = wv
    override val url: String? get() = wv.url
    override val title: String? get() = wv.title
    override val canGoBack: Boolean get() = wv.canGoBack()
    override val canGoForward: Boolean get() = wv.canGoForward()

    init {
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true
        if (Build.VERSION.SDK_INT >= 26) wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        applySettings(host.settings())
        wv.webViewClient = Client()
        wv.webChromeClient = Chrome()
        wv.setDownloadListener { url, ua, cd, mime, _ -> host.download(url, ua, cd, mime) }
    }

    override fun load(url: String) = wv.loadUrl(url)
    override fun reload() = wv.reload()
    override fun stop() = wv.stopLoading()
    override fun goBack() = wv.goBack()
    override fun goForward() = wv.goForward()

    override fun saveState(): Bundle? = Bundle().also { wv.saveState(it) }
    override fun restoreState(state: Bundle): Boolean = wv.restoreState(state) != null

    override fun applySettings(s: Settings) {
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

    override fun onPause() = wv.onPause()
    override fun onResume() = wv.onResume()
    override fun pauseTimers() = wv.pauseTimers()
    override fun resumeTimers() = wv.resumeTimers()

    override fun destroy() {
        (wv.parent as? ViewGroup)?.removeView(wv)
        wv.stopLoading()
        wv.destroy()
    }

    fun clearCache() = wv.clearCache(true)

    override fun canScrollV(dir: Int) = wv.canScrollVertically(dir)
    override fun canScrollH(dir: Int) = wv.canScrollHorizontally(dir)

    override fun scrollBy(dx: Int, dy: Int): Boolean {
        val cy = dy != 0 && wv.canScrollVertically(dy)
        val cx = dx != 0 && wv.canScrollHorizontally(dx)
        if (!cx && !cy) return false
        wv.scrollBy(if (cx) dx else 0, if (cy) dy else 0)
        return true
    }

    override fun linkAtTouch(cb: (String?, String?) -> Unit) {
        val msg = Handler(Looper.getMainLooper()) { m ->
            cb(m.data.getString("url"), m.data.getString("src"))
            true
        }.obtainMessage()
        wv.requestFocusNodeHref(msg)
    }

    private fun injectCosmetic(url: String) {
        if (!host.adBlockOn) return
        val h = UrlUtils.host(url)
        if (h.isEmpty()) return
        val css = host.cosmeticCss(h)
        if (css.isEmpty()) return
        val js = "(function(){var s=document.getElementById('__tvb');if(!s){s=document.createElement('style');" +
            "s.id='__tvb';(document.head||document.documentElement).appendChild(s)}s.textContent=" +
            JSONObject.quote(css) + "})()"
        wv.evaluateJavascript(js, null)
    }

    private inner class Client : WebViewClient() {
        @Volatile
        private var pageHost = ""

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme?.lowercase()
            if (scheme == "tvb-proceed") {
                val u = request.url.getQueryParameter("u")
                if (u != null) {
                    host.allowSsl(UrlUtils.host(u))
                    view.loadUrl(u)
                }
                return true
            }
            return scheme !in INTERNAL_SCHEMES
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (!host.adBlockOn || request.isForMainFrame) return null
            val url = request.url.toString()
            if (!url.startsWith("http")) return null
            return if (host.shouldBlock(url, pageHost, request.requestHeaders?.get("Accept"))) {
                WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            } else {
                null
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            pageHost = UrlUtils.host(url)
            host.pageStarted(url)
        }

        override fun onPageFinished(view: WebView, url: String) {
            host.navState(view.canGoBack(), view.canGoForward())
            if (!url.startsWith(Engines.ERROR_PAGE)) injectCosmetic(url)
            host.pageFinished(url)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            host.urlChanged(url)
            host.navState(view.canGoBack(), view.canGoForward())
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            val failing = request.url.toString()
            host.error(failing, error.description.toString(), false)
            view.loadUrl(host.errorPageUrl(failing, error.description.toString(), false))
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            val failing = error.url
            if (host.isSslAllowed(UrlUtils.host(failing))) {
                handler.proceed()
                return
            }
            handler.cancel()
            host.error(failing, "", true)
            view.loadUrl(host.errorPageUrl(failing, "", true))
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            main.post { host.gone() }
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) = host.progress(newProgress)

        override fun onReceivedTitle(view: WebView, title: String?) {
            if (title.isNullOrEmpty() || view.url?.startsWith(Engines.ERROR_PAGE) == true) return
            host.titleChanged(title)
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            host.fullscreen(true, view) { callback.onCustomViewHidden() }
        }

        override fun onHideCustomView() = host.fullscreen(false, null, null)

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
            fileChooserParams: FileChooserParams,
        ): Boolean {
            filePathCallback.onReceiveValue(null)
            return true
        }

        override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
            host.dialog(message, false) { ok -> if (ok) result.confirm() else result.cancel() }
            return true
        }

        override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
            host.dialog(message, true) { ok -> if (ok) result.confirm() else result.cancel() }
            return true
        }
    }

    private companion object {
        val INTERNAL_SCHEMES = setOf("http", "https", "about", "data", "blob", "javascript", "file")
    }

}
