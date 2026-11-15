package app.tvbrowser

import android.app.Activity
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import app.tvbrowser.data.Panel
import app.tvbrowser.web.TabManager
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
    private var downTime = 0L

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
            KeyEvent.KEYCODE_DPAD_UP -> return step(wv, down, e, 0f, -1f)
            KeyEvent.KEYCODE_DPAD_DOWN -> return step(wv, down, e, 0f, 1f)
            KeyEvent.KEYCODE_DPAD_LEFT -> return step(wv, down, e, -1f, 0f)
            KeyEvent.KEYCODE_DPAD_RIGHT -> return step(wv, down, e, 1f, 0f)
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

    private fun step(wv: WebView, down: Boolean, e: KeyEvent, dirX: Float, dirY: Float): Boolean {
        if (!down) return true
        ensureInit(wv)
        val w = wv.width.toFloat()
        val h = wv.height.toFloat()
        val speed = (10f + min(e.repeatCount, 40) * 1.5f) * density
        val tick = 1f + min(e.repeatCount, 20) / 5f
        val bandV = 64f * density
        val bandH = 48f * density

        var nx = cx + dirX * speed
        var ny = cy + dirY * speed

        if (dirY < 0f && cy < bandV) {
            if (wv.canScrollVertically(-1)) {
                scrollWheel(wv, cx, cy, tick)
                ny = cy
            } else if (cy <= 2f && e.repeatCount == 0) {
                vm.showChrome()
            }
        } else if (dirY > 0f && cy > h - bandV && wv.canScrollVertically(1)) {
            scrollWheel(wv, cx, cy, -tick)
            ny = cy
        }
        if (dirX < 0f && cx < bandH && wv.canScrollHorizontally(-1)) {
            scrollWheel(wv, cx, cy, 0f, tick)
            nx = cx
        } else if (dirX > 0f && cx > w - bandH && wv.canScrollHorizontally(1)) {
            scrollWheel(wv, cx, cy, 0f, -tick)
            nx = cx
        }

        cx = nx.coerceIn(0f, w - 1f)
        cy = ny.coerceIn(0f, h - 1f)
        dispatchMouse(wv, MotionEvent.ACTION_HOVER_MOVE, cx, cy, 0f, 0f)
        return true
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
