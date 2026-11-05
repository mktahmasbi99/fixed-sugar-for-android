package app.fixedsugar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

@Suppress("ViewConstructor") // Built in code with the selected date range and readings.

internal class GlucosePlot(
    context: Context,
    private val points: List<Pair<Long, Int>>,
    private val start: Long,
    private val end: Long,
    private val number: (Number) -> String
) : View(context) {
    var selected = -1
    var onSelection: (Int) -> Unit = {}
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val minimum = points.minOf { it.second }.toDouble()
    private val maximum = points.maxOf { it.second }.toDouble()
    private val margin = max(1.0, (maximum - minimum) * 0.1)
    private val low = max(0.0, minimum - margin)
    private val high = maximum + margin
    private var left = 0f
    private var right = 0f
    private val top = 24 * density
    private val bottom get() = height - 12 * density

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun x(index: Int): Float {
        val fraction = (points[index].first - start).toDouble() / (end - start)
        return if (layoutDirection == LAYOUT_DIRECTION_RTL) right - (right - left) * fraction.toFloat()
            else left + (right - left) * fraction.toFloat()
    }
    private fun y(index: Int) = bottom - (bottom - top) * ((points[index].second - low) / (high - low)).toFloat()

    override fun onDraw(canvas: Canvas) {
        paint.textSize = 12 * resources.displayMetrics.scaledDensity
        val lowLabel = number(minimum.toInt())
        val highLabel = number(maximum.toInt())
        val unit = context.getString(R.string.chart_axis)
        val axisWidth = max(paint.measureText(lowLabel), paint.measureText(highLabel)) + 12 * density
        left = if (layoutDirection == LAYOUT_DIRECTION_RTL) 8 * density else axisWidth
        right = width - if (layoutDirection == LAYOUT_DIRECTION_RTL) axisWidth else 8 * density
        val labelX = if (layoutDirection == LAYOUT_DIRECTION_RTL) right + 6 * density else 0f
        paint.color = 0xff435952.toInt()
        canvas.drawText(unit, if (layoutDirection == LAYOUT_DIRECTION_RTL) width - paint.measureText(unit) else 0f,
            paint.textSize, paint)
        fun grid(value: Double, label: String) {
            val yy = bottom - (bottom - top) * ((value - low) / (high - low)).toFloat()
            paint.color = 0xffd5e6df.toInt()
            paint.strokeWidth = density
            canvas.drawLine(left, yy, right, yy, paint)
            paint.color = 0xff435952.toInt()
            canvas.drawText(label, labelX, yy + paint.textSize / 3, paint)
        }
        grid(minimum, lowLabel)
        if (maximum != minimum) grid(maximum, highLabel)
        paint.color = 0xff006b60.toInt()
        paint.strokeWidth = 2 * density
        for (i in 1 until points.size) canvas.drawLine(x(i - 1), y(i - 1), x(i), y(i), paint)
        points.indices.forEach { canvas.drawCircle(x(it), y(it), (if (it == selected) 7 else 4) * density, paint) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val closest = points.indices.minBy { index ->
                val dx = x(index) - event.x
                val dy = y(index) - event.y
                dx * dx + dy * dy
            }
            if (kotlin.math.hypot(x(closest) - event.x, y(closest) - event.y) <= 32 * density) {
                onSelection(closest)
            }
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
