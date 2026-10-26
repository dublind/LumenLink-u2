package cl.ti3042.lumenlink

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

class LdrBar(context: Context) : View(context) {
    companion object {
        val amber: Int = Color.rgb(245, 166, 35)
        val amberDark: Int = Color.rgb(176, 104, 0)
    }
    var percent: Float = 0f
    var threshold: Float = 30f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = android.graphics.RectF()
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val radius = height / 2f
        box.set(0f, 0f, width.toFloat(), height.toFloat())
        paint.shader = null; paint.color = Color.rgb(224, 233, 237)
        canvas.drawRoundRect(box, radius, radius, paint)
        val fill = width * percent.coerceIn(0f, 100f) / 100f
        if (fill > 0f) {
            paint.shader = android.graphics.LinearGradient(0f, 0f, width.toFloat(), 0f,
                Color.rgb(16, 44, 64), Color.rgb(255, 214, 10), android.graphics.Shader.TileMode.CLAMP)
            box.set(0f, 0f, fill.coerceAtLeast(height.toFloat()), height.toFloat())
            canvas.drawRoundRect(box, radius, radius, paint)
            paint.shader = null
        }
        val x = width * threshold.coerceIn(0f, 100f) / 100f
        paint.color = Color.WHITE; paint.strokeWidth = 5 * density
        canvas.drawLine(x, 0f, x, height.toFloat(), paint)
        paint.color = Color.rgb(16, 44, 64); paint.strokeWidth = 2 * density
        canvas.drawLine(x, 0f, x, height.toFloat(), paint)
    }
}
