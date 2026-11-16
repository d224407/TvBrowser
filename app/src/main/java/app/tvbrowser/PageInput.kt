package app.tvbrowser

import android.app.Activity
import android.os.Build
import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import app.tvbrowser.data.Panel
import app.tvbrowser.web.TabManager
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/** D-pad to page translation: virtual cursor, edge scrolling and global shortcuts. */
class PageInput(
    activity: Activity,
    private val vm: BrowserViewModel,
    private val mgr: TabManager,
) {
    var cx by mutableFloatStateOf(-1f)
        private set
    var cy by mutableFloatStateOf(-1f)
        private set

    private val density = activity.resources.displayMetrics.density

    private companion object {
        const val BASE_SPEED = 220f // dp/s
        const val MAX_SPEED = 2200f // dp/s
        const val ACCEL_TIME = 0.8f // s to reach max
        const val RESPONSE = 16f // velocity easing while held
        const val DECEL = 12f // velocity easing after release
    }
    private var downTime = 0L
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

        if (!vm.settings.cursorMode) return false

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
                } else if (!down) {
                    touch(wv, MotionEvent.ACTION_UP)
                }
                return true
            }
        }
        return false
    }

    private fun ensureInit(wv: WebView) {
        if (cx < 0f || cy < 0f) {
            cx = wv.width / 2f
            cy = wv.height / 2f
        }
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
        if (overY != 0f && wv.canScrollVertically(if (overY < 0f) -1 else 1)) {
            scrollWheel(wv, nx, ny, -overY / scrollFactor)
        }
        if (overX != 0f && wv.canScrollHorizontally(if (overX < 0f) -1 else 1)) {
            scrollWheel(wv, nx, ny, 0f, -overX / scrollFactor)
        }

        val ox = cx
        val oy = cy
        cx = nx
        cy = ny
        if (cx != ox || cy != oy) dispatchMouse(wv, MotionEvent.ACTION_HOVER_MOVE, cx, cy, 0f, 0f)
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
