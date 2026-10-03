package io.github.ramziag.weather

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

/**
 * Low-to-high temperature bar for the 10-day list. All rows share one scale ([lo]..[hi]) so days can be
 * compared at a glance; the fill is tinted cold-blue to warm-orange by absolute temperature.
 */
class RangeBar(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        val tv = TypedValue()
        context.theme.resolveAttribute(R.attr.wLine, tv, true)
        color = tv.data
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    private var lo = 0.0
    private var hi = 1.0
    private var min = 0.0
    private var max = 1.0
    private var x0 = 0f
    private var x1 = 0f

    fun set(lo: Double, hi: Double, min: Double, max: Double) {
        this.lo = lo
        this.hi = hi
        this.min = min
        this.max = max
        layoutFill()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutFill()

    private fun layoutFill() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= h || min.isNaN() || max.isNaN()) {
            fill.shader = null
            return
        }
        val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        x0 = ((min - lo) / span * w).toFloat().coerceIn(0f, w - h)
        x1 = ((max - lo) / span * w).toFloat().coerceIn(x0 + h, w)
        fill.shader = LinearGradient(x0, 0f, x1, 0f, colorFor(min), colorFor(max), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val r = h / 2
        canvas.drawRoundRect(0f, 0f, width.toFloat(), h, r, r, track)
        if (fill.shader != null) canvas.drawRoundRect(x0, 0f, x1, h, r, r, fill)
    }

    private fun colorFor(celsius: Double): Int {
        // Pastel stops: freezing, cool, mild, warm, hot.
        val t = ((celsius + 10) / 45).coerceIn(0.0, 1.0) * (STOPS.size - 1)
        val i = t.toInt().coerceAtMost(STOPS.size - 2)
        return blend(STOPS[i], STOPS[i + 1], (t - i).toFloat())
    }

    private fun blend(a: Int, b: Int, f: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - f) + ((b shr shift) and 0xFF) * f).toInt() shl shift
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
    }

    private companion object {
        val STOPS = intArrayOf(0x8FB8F2, 0x86CFE6, 0x9DD9A8, 0xF4C772, 0xF29A85)
    }
}
