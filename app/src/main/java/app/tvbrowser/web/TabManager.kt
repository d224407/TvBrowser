package app.tvbrowser.web

import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import app.tvbrowser.BrowserViewModel
import app.tvbrowser.R
import app.tvbrowser.adblock.AdBlockEngine
import app.tvbrowser.data.Panel
import app.tvbrowser.data.Settings
import app.tvbrowser.data.TabState
import app.tvbrowser.data.UrlUtils

/** Owns engine instances: creation, LRU eviction, lifecycle and event wiring. */
class TabManager(
    private val activity: ComponentActivity,
    private val vm: BrowserViewModel,
    private val adBlock: AdBlockEngine,
    private val fullscreenHost: FrameLayout,
) {
    private val engines = LinkedHashMap<Long, WebEngine>(8, 0.75f, true)
    private val saved = HashMap<Long, Bundle>()
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    var adBlockOn = true

    private val sslAllowed = HashSet<String>()
    private var customView: View? = null
    private var fullscreenOn = false
    private var onHidden: (() -> Unit)? = null

    private val maxLive: Int = run {
        val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (am.isLowRamDevice) 2 else 4
    }

    val isFullscreen: Boolean get() = fullscreenOn

    fun activeEngine(): WebEngine? = engines[vm.activeId]

    fun engineFor(tab: TabState): WebEngine {
        engines[tab.id]?.let { return it }
        val e = create(tab)
        engines[tab.id] = e
        evictExtra()
        return e
    }

    fun engineOf(v: View?): WebEngine? = engines.values.firstOrNull { it.view === v }

    fun load(tab: TabState, url: String) {
        tab.url = url
        tab.title = ""
        val existing = engines[tab.id]
        if (existing != null) existing.load(url) else engineFor(tab)
    }

    fun goBack() {
        activeEngine()?.let { if (it.canGoBack) it.goBack() }
    }

    fun goForward() {
        activeEngine()?.let { if (it.canGoForward) it.goForward() }
    }

    fun reloadOrStop(tab: TabState) {
        val e = engines[tab.id] ?: return
        if (tab.loading) e.stop() else e.reload()
    }

    fun destroy(id: Long) {
        saved.remove(id)
        dispose(id, keepState = false)
    }

    fun destroyAll() {
        exitFullscreen()
        engines.keys.toList().forEach { dispose(it, keepState = false) }
    }

    fun pause() {
        Engines.flush()
        activeEngine()?.onPause()
        engines.values.firstOrNull()?.pauseTimers()
    }

    fun resume() {
        engines.values.firstOrNull()?.resumeTimers()
        activeEngine()?.onResume()
    }

    /** Keep only the active engine; others keep their state in memory. */
    fun trim() {
        engines.keys.toList().filter { it != vm.activeId }.forEach { dispose(it, keepState = true) }
    }

    fun exitFullscreen(): Boolean {
        if (!fullscreenOn) return false
        val cb = onHidden
        clearFullscreen()
        cb?.invoke()
        return true
    }

    private fun clearFullscreen() {
        customView?.let { fullscreenHost.removeView(it) }
        customView = null
        onHidden = null
        fullscreenOn = false
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        fullscreenHost.visibility = View.GONE
    }

    fun clearCache() = Engines.clearCache(activeEngine())

    fun clearCookies() = Engines.clearCookies()

    fun applySettingsToAll(reloadForUa: Boolean) {
        adBlockOn = vm.settings.adBlock
        for ((id, e) in engines) {
            e.applySettings(vm.settings)
            if (reloadForUa && id == vm.activeId) e.reload()
        }
    }

    private fun evictExtra() {
        while (engines.size > maxLive) {
            val victim = engines.keys.firstOrNull { it != vm.activeId } ?: return
            dispose(victim, keepState = true)
        }
    }

    private fun dispose(id: Long, keepState: Boolean) {
        val e = engines.remove(id) ?: return
        if (keepState) e.saveState()?.let { saved[id] = it }
        e.destroy()
    }

    private fun create(tab: TabState): WebEngine {
        adBlockOn = vm.settings.adBlock
        val e = Engines.create(activity, TabHost(tab))
        val state = saved.remove(tab.id)
        if (state != null && e.restoreState(state)) {
            // restored from memory
        } else if (tab.url.isNotEmpty()) {
            e.load(tab.url)
        }
        return e
    }

    private fun errorPage(failing: String, desc: String, ssl: Boolean): String {
        var q = "?t=" + Uri.encode(activity.getString(R.string.error_title)) +
            "&d=" + Uri.encode(desc) +
            "&r=" + Uri.encode(activity.getString(R.string.retry)) +
            "&u=" + Uri.encode(failing)
        if (ssl) q += "&x=" + Uri.encode(activity.getString(R.string.ssl_continue))
        return Engines.ERROR_PAGE + q
    }

    private inner class TabHost(val tab: TabState) : EngineHost {
        private val engine: WebEngine? get() = engines[tab.id]

        override val adBlockOn: Boolean get() = this@TabManager.adBlockOn

        override fun shouldBlock(url: String, pageHost: String, accept: String?): Boolean =
            adBlock.shouldBlock(url, pageHost, AdBlockEngine.guessType(url, accept))

        override fun cosmeticCss(host: String): String = adBlock.cosmeticCss(host)

        override fun pageStarted(url: String) {
            tab.loading = true
            if (!url.startsWith(Engines.ERROR_PAGE)) tab.url = url
        }

        override fun pageFinished(url: String) {
            tab.loading = false
            if (url.startsWith(Engines.ERROR_PAGE)) return
            tab.title = engine?.title.orEmpty()
            if (url.startsWith("http")) vm.recordVisit(url, tab.title)
            vm.persistTabs()
        }

        override fun urlChanged(url: String) {
            if (!url.startsWith(Engines.ERROR_PAGE)) tab.url = url
        }

        override fun titleChanged(title: String) {
            tab.title = title
        }

        override fun progress(p: Int) {
            tab.progress = p
        }

        override fun navState(back: Boolean, forward: Boolean) {
            tab.canBack = back
            tab.canForward = forward
        }

        override fun error(failing: String, desc: String, ssl: Boolean) {
            tab.url = failing
        }

        override fun errorPageUrl(failing: String, desc: String, ssl: Boolean): String =
            errorPage(failing, if (ssl) activity.getString(R.string.ssl_error) else desc, ssl)

        override fun isSslAllowed(host: String): Boolean = host in sslAllowed

        override fun allowSsl(host: String) {
            sslAllowed.add(host)
        }

        override fun fullscreen(on: Boolean, view: View?, onHidden: (() -> Unit)?) {
            if (!on) {
                if (fullscreenOn) clearFullscreen()
                return
            }
            if (fullscreenOn) {
                onHidden?.invoke()
                return
            }
            fullscreenOn = true
            this@TabManager.onHidden = onHidden
            if (view != null) {
                customView = view
                fullscreenHost.addView(
                    view,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                )
                fullscreenHost.visibility = View.VISIBLE
                view.requestFocus()
            }
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        override fun download(url: String, ua: String?, disposition: String?, mime: String?) {
            if (Downloads.enqueue(activity, url, ua, disposition, mime)) vm.openPanel(Panel.DOWNLOADS)
        }

        override fun gone() {
            dispose(tab.id, keepState = false)
            tab.generation++
        }

        override fun dialog(message: String, confirm: Boolean, result: (Boolean) -> Unit) {
            val b = AlertDialog.Builder(activity)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result(true) }
                .setOnCancelListener { result(false) }
            if (confirm) b.setNegativeButton(android.R.string.cancel) { _, _ -> result(false) }
            b.show()
        }

        override fun settings(): Settings = vm.settings
    }
}
