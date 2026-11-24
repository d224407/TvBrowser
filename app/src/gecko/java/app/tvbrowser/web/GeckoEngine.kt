package app.tvbrowser.web

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import app.tvbrowser.data.Settings
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse

/** GeckoView engine: one session and one view per tab. */
class GeckoEngine(
    ctx: Context,
    private val runtime: GeckoRuntime,
    private val host: EngineHost,
) : WebEngine {
    private val geckoView = GeckoView(ctx)
    private val session: GeckoSession
    private var state: GeckoSession.SessionState? = null
    private var curUrl: String? = null
    private var curTitle: String? = null
    private var back = false
    private var fwd = false
    private var scrollY = 0
    private var ctxLink: String? = null
    private var ctxImage: String? = null
    private var ctxTime = 0L

    override val view: View get() = geckoView
    override val url: String? get() = curUrl
    override val title: String? get() = curTitle
    override val canGoBack: Boolean get() = back
    override val canGoForward: Boolean get() = fwd

    init {
        val s = host.settings()
        session = GeckoSession(
            GeckoSessionSettings.Builder()
                .useTrackingProtection(s.adBlock)
                .allowJavascript(s.javascript)
                .build(),
        )
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) = host.pageStarted(url)

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                host.pageFinished(curUrl.orEmpty())
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) = host.progress(progress)

            override fun onSessionStateChange(session: GeckoSession, sessionState: GeckoSession.SessionState) {
                state = sessionState
            }
        }
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                back = canGoBack
                host.navState(back, fwd)
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                fwd = canGoForward
                host.navState(back, fwd)
            }

            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: List<GeckoSession.PermissionDelegate.ContentPermission?>,
                hasUserGesture: Boolean,
            ) {
                curUrl = url
                if (url != null) host.urlChanged(url)
            }

            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest,
            ): GeckoResult<AllowOrDeny>? {
                val scheme = request.uri.substringBefore(':').lowercase()
                return if (scheme in INTERNAL_SCHEMES) GeckoResult.allow() else GeckoResult.deny()
            }

            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? = null

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError,
            ): GeckoResult<String>? {
                val failing = uri.orEmpty()
                val desc = "Error " + error.code
                host.error(failing, desc, false)
                return GeckoResult.fromValue(host.errorPageUrl(failing, desc, false))
            }
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                curTitle = title
                if (!title.isNullOrEmpty()) host.titleChanged(title)
            }

            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                host.fullscreen(fullScreen, null) { session.exitFullScreen() }
            }

            override fun onContextMenu(
                session: GeckoSession,
                screenX: Int,
                screenY: Int,
                element: GeckoSession.ContentDelegate.ContextElement,
            ) {
                ctxLink = element.linkUri
                ctxImage = element.srcUri
                ctxTime = SystemClock.uptimeMillis()
            }

            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                host.download(
                    response.uri,
                    session.settings.userAgentOverride,
                    response.headers["content-disposition"],
                    response.headers["content-type"],
                )
            }

            override fun onCrash(session: GeckoSession) = host.gone()

            override fun onKill(session: GeckoSession) = host.gone()
        }
        session.scrollDelegate = GeckoSession.ScrollDelegate { _, _, y -> scrollY = y }

        session.open(runtime)
        geckoView.setSession(session)
        session.setActive(true)
        session.setFocused(true)
        applySettings(s)
    }

    override fun load(url: String) = session.loadUri(url)
    override fun reload() = session.reload()
    override fun stop() = session.stop()
    override fun goBack() = session.goBack()
    override fun goForward() = session.goForward()

    override fun saveState(): Bundle? = state?.let { Bundle().apply { putString("g", it.toString()) } }

    override fun restoreState(state: Bundle): Boolean {
        val st = state.getString("g")?.let { GeckoSession.SessionState.fromString(it) } ?: return false
        session.restoreState(st)
        return true
    }

    override fun applySettings(s: Settings) {
        session.settings.useTrackingProtection = s.adBlock
        session.settings.allowJavascript = s.javascript
        session.settings.userAgentMode = if (s.desktopMode) {
            GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
        } else {
            GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        }
        session.settings.viewportMode = if (s.desktopMode) {
            GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
        } else {
            GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        }
        runtime.settings.fontSizeFactor = s.textZoom / 100f
        runtime.settings.contentBlocking.setCookieBehavior(
            if (s.thirdPartyCookies) {
                ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS
            } else {
                ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY
            },
        )
    }

    override fun onPause() {
        session.setFocused(false)
        session.setActive(false)
    }

    override fun onResume() {
        session.setActive(true)
        session.setFocused(true)
    }

    override fun destroy() {
        (geckoView.parent as? android.view.ViewGroup)?.removeView(geckoView)
        geckoView.releaseSession()
        session.close()
    }

    override fun canScrollV(dir: Int): Boolean = if (dir < 0) scrollY > 0 else true
    override fun canScrollH(dir: Int): Boolean = true

    // Wheel events drive scrolling
    override fun scrollBy(dx: Int, dy: Int): Boolean = false

    override fun linkAtTouch(cb: (String?, String?) -> Unit) {
        val fresh = SystemClock.uptimeMillis() - ctxTime < 1500
        cb(if (fresh) ctxLink else null, if (fresh) ctxImage else null)
    }

    private companion object {
        val INTERNAL_SCHEMES = setOf("http", "https", "about", "data", "blob", "javascript", "file", "resource")
    }
}
