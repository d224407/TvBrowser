package app.tvbrowser.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** Lightweight cursor overlay drawn outside Compose so movement never triggers recomposition. */
class CursorView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val radius = 9f * resources.displayMetrics.density
    private var x = -1f
    private var y = -1f

    init {
        isFocusable = false
        isClickable = false
        visibility = GONE
    }

    fun moveTo(nx: Float, ny: Float) {
        x = nx
        y = ny
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (x < 0f) return
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(200, 255, 255, 255)
        canvas.drawCircle(x, y, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * resources.displayMetrics.density
        paint.color = Color.DKGRAY
        canvas.drawCircle(x, y, radius, paint)
    }
}
