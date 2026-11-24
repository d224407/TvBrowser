package app.tvbrowser.web

import android.os.Bundle
import android.view.View
import app.tvbrowser.data.Settings

/** Callbacks from an engine to the tab that owns it. */
interface EngineHost {
    val adBlockOn: Boolean
    fun shouldBlock(url: String, pageHost: String, accept: String?): Boolean
    fun cosmeticCss(host: String): String
    fun pageStarted(url: String)
    fun pageFinished(url: String)
    fun urlChanged(url: String)
    fun titleChanged(title: String)
    fun progress(p: Int)
    fun navState(back: Boolean, forward: Boolean)
    fun error(failing: String, desc: String, ssl: Boolean)
    fun errorPageUrl(failing: String, desc: String, ssl: Boolean): String
    fun isSslAllowed(host: String): Boolean
    fun allowSsl(host: String)
    fun fullscreen(on: Boolean, view: View?, onHidden: (() -> Unit)?)
    fun download(url: String, ua: String?, disposition: String?, mime: String?)
    fun gone()
    fun dialog(message: String, confirm: Boolean, result: (Boolean) -> Unit)
    fun settings(): Settings
}

/** What the shared UI and input code need from a browser engine. */
interface WebEngine {
    val view: View
    val url: String?
    val title: String?
    val canGoBack: Boolean
    val canGoForward: Boolean

    fun load(url: String)
    fun reload()
    fun stop()
    fun goBack()
    fun goForward()
    fun saveState(): Bundle?
    fun restoreState(state: Bundle): Boolean
    fun applySettings(s: Settings)
    fun onPause()
    fun onResume()
    fun pauseTimers() {}
    fun resumeTimers() {}
    fun destroy()

    /** True when the page can scroll in this direction (negative = up/left). */
    fun canScrollV(dir: Int): Boolean
    fun canScrollH(dir: Int): Boolean

    /** Scroll natively; false means the caller should send wheel events instead. */
    fun scrollBy(dx: Int, dy: Int): Boolean

    /** Link and image under the last touch, delivered on the main thread. */
    fun linkAtTouch(cb: (link: String?, image: String?) -> Unit)
}
