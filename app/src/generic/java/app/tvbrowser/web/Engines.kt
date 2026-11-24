package app.tvbrowser.web

import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.activity.ComponentActivity

/** Engine entry points for the WebView build. */
object Engines {
    const val NAME = "webview"
    const val ERROR_PAGE = "file:///android_asset/error.html"

    fun available(): Boolean = Build.VERSION.SDK_INT < 26 || WebView.getCurrentWebViewPackage() != null

    fun create(activity: ComponentActivity, host: EngineHost): WebEngine = WebViewEngine(activity, host)

    fun flush() = CookieManager.getInstance().flush()

    fun clearCookies() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
    }

    fun clearCache(e: WebEngine?) {
        (e as? WebViewEngine)?.clearCache()
    }
}
