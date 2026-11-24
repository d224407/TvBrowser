package app.tvbrowser

import android.app.AlertDialog
import android.content.ComponentCallbacks2
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import app.tvbrowser.data.Panel
import app.tvbrowser.ui.BrowserRoot
import app.tvbrowser.ui.CursorView
import app.tvbrowser.ui.TvTheme
import app.tvbrowser.web.TabManager

class MainActivity : ComponentActivity() {
    private val vm: BrowserViewModel by viewModels()
    private lateinit var mgr: TabManager
    private lateinit var input: PageInput

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 26 && WebView.getCurrentWebViewPackage() == null) {
            AlertDialog.Builder(this)
                .setMessage(R.string.webview_missing)
                .setOnDismissListener { finish() }
                .show()
            return
        }

        val fullscreen = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        mgr = TabManager(this, vm, (application as TvBrowserApp).adBlock, fullscreen)
        val cursor = CursorView(this)
        input = PageInput(this, vm, mgr, cursor)
        vm.attach(mgr)

        setContent {
            TvTheme {
                BrowserRoot(vm, mgr, input, onExit = { finish() })
            }
        }
        addContentView(
            cursor,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        addContentView(
            fullscreen,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (mgr.exitFullscreen()) return
                if (!vm.handleBack()) finish()
            }
        })

        if (savedInstanceState == null) openIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openIntent(intent)
    }

    private fun openIntent(i: Intent?) {
        val url = i?.dataString ?: return
        if (i.action != Intent.ACTION_VIEW) return
        val current = vm.active
        if (current != null && current.url.isEmpty()) {
            vm.navigate(url)
        } else {
            vm.newTab()
            vm.navigate(url)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!::mgr.isInitialized) return super.dispatchKeyEvent(event)
        if (vm.panel == Panel.NONE && !vm.chromeVisible) {
            if (input.handle(event)) return true
            val handled = super.dispatchKeyEvent(event)
            if (!handled && event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                val wv = mgr.activeWebView()
                if (wv != null && !mgr.isFullscreen && !wv.canScrollVertically(-1)) vm.showChrome()
            }
            return handled
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        if (!::mgr.isInitialized) return
        mgr.resume()
    }

    override fun onPause() {
        if (!::mgr.isInitialized) {
            super.onPause()
            return
        }
        mgr.pause()
        vm.persistTabs()
        super.onPause()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!::mgr.isInitialized) return
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) mgr.trim()
    }

    override fun onDestroy() {
        if (::mgr.isInitialized) {
            vm.detach()
            mgr.destroyAll()
        }
        super.onDestroy()
    }
}
