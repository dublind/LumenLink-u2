package cl.ti3042.lumenlink

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

class TrendView(context: Context) : View(context) {
    var values: List<Float> = emptyList()
    var lineColor: Int = Color.rgb(8, 127, 140)
    var fixedRange: ClosedFloatingPointRange<Float>? = null
    var marker: Float? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        paint.color = Color.rgb(224, 233, 237); paint.strokeWidth = density
        for (i in 1..3) canvas.drawLine(0f, height * i / 4f, width.toFloat(), height * i / 4f, paint)
        val low = fixedRange?.start ?: ((values.minOrNull() ?: 0f) - 1f)
        val high = fixedRange?.endInclusive ?: ((values.maxOrNull() ?: 0f) + 1f)
        fun y(value: Float) = height - (value - low) / (high - low) * (height - 16 * density) - 8 * density
        marker?.let { m ->
            paint.color = Color.rgb(16, 44, 64); paint.strokeWidth = 2 * density
            canvas.drawLine(0f, y(m), width.toFloat(), y(m), paint)
        }
        if (values.size < 2) return
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = index.toFloat() * width / (values.size - 1)
            if (index == 0) path.moveTo(x, y(value)) else path.lineTo(x, y(value))
        }
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 3 * density; paint.color = lineColor
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
    }
}
