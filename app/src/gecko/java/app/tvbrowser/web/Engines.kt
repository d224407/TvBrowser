package app.tvbrowser.web

import android.content.Context
import androidx.activity.ComponentActivity
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.StorageController

/** Engine entry points for the GeckoView build. */
object Engines {
    const val NAME = "gecko"
    const val ERROR_PAGE = "resource://android/assets/error.html"

    private var runtime: GeckoRuntime? = null

    fun runtime(ctx: Context): GeckoRuntime = runtime ?: GeckoRuntime.create(
        ctx.applicationContext,
        GeckoRuntimeSettings.Builder()
            .forceUserScalableEnabled(true)
            .contentBlocking(
                ContentBlocking.Settings.Builder()
                    .antiTracking(ContentBlocking.AntiTracking.DEFAULT)
                    .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS)
                    .build(),
            )
            .build(),
    ).also { runtime = it }

    fun available(): Boolean = true

    fun create(activity: ComponentActivity, host: EngineHost): WebEngine =
        GeckoEngine(activity, runtime(activity), host)

    fun flush() {}

    fun clearCookies() {
        runtime?.storageController?.clearData(
            StorageController.ClearFlags.COOKIES or StorageController.ClearFlags.DOM_STORAGES,
        )
    }

    fun clearCache(e: WebEngine?) {
        runtime?.storageController?.clearData(StorageController.ClearFlags.ALL_CACHES)
    }
}
