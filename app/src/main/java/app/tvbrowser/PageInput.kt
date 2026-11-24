package app.tvbrowser

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.WebView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.tvbrowser.data.Panel
import app.tvbrowser.ui.CursorView
import app.tvbrowser.web.TabManager
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/** D-pad to page translation: virtual cursor, edge scrolling and global shortcuts. */
class PageInput(
    activity: Activity,
    private val vm: BrowserViewModel,
    private val mgr: TabManager,
    private val cursorView: CursorView,
) {
    private var cx = -1f
    private var cy = -1f
    private var scrollAccX = 0f
    private var scrollAccY = 0f
    private var lastHover = 0L

    private val density = activity.resources.displayMetrics.density

    private companion object {
        const val BASE_SPEED = 220f // dp/s
        const val MAX_SPEED = 2200f // dp/s
        const val ACCEL_TIME = 0.8f // s to reach max
        const val RESPONSE = 16f // velocity easing while held
        const val DECEL = 12f // velocity easing after release
        const val HOVER_INTERVAL_NS = 60_000_000L
        const val LONG_PRESS_MS = 600L
    }
    private var downTime = 0L
    private var touching = false
    private var longFired = false
    private val handler = Handler(Looper.getMainLooper())

    // Long press on a link or image opens the context panel
    private val longPress = Runnable {
        val wv = mgr.activeWebView()
        if (wv != null && touching) {
            touch(wv, MotionEvent.ACTION_CANCEL)
            touching = false
            longFired = true
            val msg = Handler(Looper.getMainLooper()) { m ->
                vm.openContext(m.data.getString("url"), m.data.getString("src"))
                true
            }.obtainMessage()
            wv.requestFocusNodeHref(msg)
        }
    }
    private val pressed = HashSet<Int>()
    private var pressStart = 0L
    private var lastFrame = 0L
    private var running = false
    private var vx = 0f
    private var vy = 0f
    private val scrollFactor: Float = if (Build.VERSION.SDK_INT >= 26) {
        ViewConfiguration.get(activity).scaledVerticalScrollFactor
    } else {
        48f * activity.resources.displayMetrics.density
    }

    /** Returns true when the key was consumed. Only called while the page owns focus. */
    fun handle(e: KeyEvent): Boolean {
        if (mgr.isFullscreen) return false
        val wv = mgr.activeWebView() ?: return false
        val down = e.action == KeyEvent.ACTION_DOWN

        when (e.keyCode) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                if (down && e.repeatCount == 0) vm.openPanel(Panel.MENU)
                return true
            }
            KeyEvent.KEYCODE_SEARCH -> {
                if (down && e.repeatCount == 0) vm.openPanel(Panel.ADDRESS)
                return true
            }
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (down) scrollWheel(wv, wv.width / 2f, wv.height / 2f, 4f)
                return true
            }
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (down) scrollWheel(wv, wv.width / 2f, wv.height / 2f, -4f)
                return true
            }
        }

        if (!vm.settings.cursorMode || imeVisible()) return false

        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (down) press(wv, e.keyCode, e.repeatCount == 0) else release(e.keyCode)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                ensureInit(wv)
                if (down && e.repeatCount == 0) {
                    wv.requestFocus()
                    touch(wv, MotionEvent.ACTION_DOWN)
                    touching = true
                    longFired = false
                    handler.postDelayed(longPress, LONG_PRESS_MS)
                } else if (!down) {
                    handler.removeCallbacks(longPress)
                    if (longFired) longFired = false else if (touching) touch(wv, MotionEvent.ACTION_UP)
                    touching = false
                }
                return true
            }
        }
        return false
    }

    private fun imeVisible(): Boolean =
        ViewCompat.getRootWindowInsets(cursorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true

    private fun ensureInit(wv: WebView) {
        if (cx < 0f || cy < 0f) {
            cx = wv.width / 2f
            cy = wv.height / 2f
            cursorView.moveTo(cx, cy)
        }
    }

    /** Called from composition side effect whenever overlays or mode change. */
    fun setVisible(visible: Boolean) {
        cursorView.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) mgr.activeWebView()?.let { ensureInit(it) }
    }

    private fun press(wv: WebView, code: Int, first: Boolean) {
        if (!first) return
        ensureInit(wv)
        if (code == KeyEvent.KEYCODE_DPAD_UP && cy <= 2f && !wv.canScrollVertically(-1)) {
            vm.showChrome()
            return
        }
        if (pressed.isEmpty()) pressStart = System.nanoTime()
        pressed.add(code)
        if (!running) {
            running = true
            Choreographer.getInstance().postFrameCallback(frame)
        }
    }

    private fun release(code: Int) {
        pressed.remove(code)
    }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            tick(frameTimeNanos)
            if (pressed.isNotEmpty() || abs(vx) > 4f || abs(vy) > 4f) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                running = false
                lastFrame = 0L
                vx = 0f
                vy = 0f
            }
        }
    }

    private fun tick(t: Long) {
        val wv = mgr.activeWebView()
        if (wv == null || vm.panel != Panel.NONE || vm.chromeVisible || !vm.settings.cursorMode) {
            pressed.clear()
            vx = 0f
            vy = 0f
            return
        }
        val dt = if (lastFrame == 0L) 1f / 60f else min((t - lastFrame) / 1e9f, 0.05f)
        lastFrame = t
        val held = (t - pressStart) / 1e9f

        var dx = (if (KeyEvent.KEYCODE_DPAD_RIGHT in pressed) 1f else 0f) -
            (if (KeyEvent.KEYCODE_DPAD_LEFT in pressed) 1f else 0f)
        var dy = (if (KeyEvent.KEYCODE_DPAD_DOWN in pressed) 1f else 0f) -
            (if (KeyEvent.KEYCODE_DPAD_UP in pressed) 1f else 0f)
        if (dx != 0f && dy != 0f) {
            dx *= 0.7071f
            dy *= 0.7071f
        }

        // Ease velocity toward the target: smooth start, smooth stop
        val ratio = min(held / ACCEL_TIME, 1f)
        val target = (BASE_SPEED + (MAX_SPEED - BASE_SPEED) * ratio * ratio) * density
        val k = if (dx == 0f && dy == 0f) DECEL else RESPONSE
        val a = 1f - exp(-k * dt)
        vx += (dx * target - vx) * a
        vy += (dy * target - vy) * a

        val w = wv.width.toFloat()
        val h = wv.height.toFloat()
        val rawX = cx + vx * dt
        val rawY = cy + vy * dt
        val nx = rawX.coerceIn(0f, w - 1f)
        val ny = rawY.coerceIn(0f, h - 1f)

        // Movement past the screen edge becomes 1:1 page scrolling
        val overY = rawY - ny
        val overX = rawX - nx
        scrollAccY = if (overY != 0f) scrollAccY + overY else 0f
        scrollAccX = if (overX != 0f) scrollAccX + overX else 0f
        val py = scrollAccY.toInt()
        val px = scrollAccX.toInt()
        if (py != 0 || px != 0) {
            scrollAccY -= py
            scrollAccX -= px
            val canY = py != 0 && wv.canScrollVertically(py)
            val canX = px != 0 && wv.canScrollHorizontally(px)
            if (canY || canX) {
                wv.scrollBy(if (canX) px else 0, if (canY) py else 0)
            } else {
                // Inner scrollers: fall back to wheel events
                if (py != 0) scrollWheel(wv, nx, ny, -py / scrollFactor)
                if (px != 0) scrollWheel(wv, nx, ny, 0f, -px / scrollFactor)
            }
        }

        val moved = nx != cx || ny != cy
        cx = nx
        cy = ny
        if (moved) {
            cursorView.moveTo(cx, cy)
            if (t - lastHover > HOVER_INTERVAL_NS) {
                lastHover = t
                dispatchMouse(wv, MotionEvent.ACTION_HOVER_MOVE, cx, cy, 0f, 0f)
            }
        }
    }

    private fun scrollWheel(wv: WebView, x: Float, y: Float, vertical: Float, horizontal: Float = 0f) {
        dispatchMouse(wv, MotionEvent.ACTION_SCROLL, x, y, vertical, horizontal)
    }

    private fun dispatchMouse(wv: WebView, action: Int, x: Float, y: Float, vs: Float, hs: Float) {
        val now = SystemClock.uptimeMillis()
        val props = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 0f
            size = 1f
            if (vs != 0f) setAxisValue(MotionEvent.AXIS_VSCROLL, vs)
            if (hs != 0f) setAxisValue(MotionEvent.AXIS_HSCROLL, hs)
        }
        val ev = MotionEvent.obtain(
            now, now, action, 1, arrayOf(props), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0,
        )
        wv.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    private fun touch(wv: WebView, action: Int) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val ev = MotionEvent.obtain(downTime, now, action, cx, cy, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        wv.dispatchTouchEvent(ev)
        ev.recycle()
    }
}
