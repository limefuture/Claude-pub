package hu.videovago

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Small illustration on the mode picker cards. */
class ModeDiagramView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var multi = false
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = 0xFF1C1B1F.toInt()
    }
    private val r = RectF()

    private fun bar(l: Float, t: Float, rr: Float, b: Float, color: Int, outline: Boolean = true) {
        r.set(l, t, rr, b)
        fill.color = color
        canvasRef?.drawRoundRect(r, 6 * d, 6 * d, fill)
        if (outline) canvasRef?.drawRoundRect(r, 6 * d, 6 * d, stroke)
    }

    private var canvasRef: Canvas? = null

    override fun onDraw(c: Canvas) {
        canvasRef = c
        val w = width.toFloat()
        val h = height.toFloat()
        val top = 4 * d
        val barH = h * 0.36f
        val resTop = top + barH + 8 * d
        val light = 0xFFE8DEF0.toInt()
        val result = 0xFF7FA8A3.toInt()
        val tick = 0xFF8B8494.toInt()

        if (!multi) {
            val a = w * 0.22f
            val b = w * 0.80f
            bar(0f, top, w, top + barH, light)
            // removed ends
            fill.color = 0xFF77727F.toInt()
            r.set(0f, top, a, top + barH); c.drawRoundRect(r, 6 * d, 6 * d, fill)
            r.set(b, top, w, top + barH); c.drawRoundRect(r, 6 * d, 6 * d, fill)
            c.drawRect(a - 6 * d, top, a, top + barH, fill)
            c.drawRect(b, top, b + 6 * d, top + barH, fill)
            r.set(0f, top, w, top + barH); c.drawRoundRect(r, 6 * d, 6 * d, stroke)
            fill.color = tick
            for (i in 1..5) {
                val x = a + (b - a) * i / 6f
                c.drawRect(x - 0.5f * d, top + barH * 0.25f, x + 0.5f * d, top + barH * 0.75f, fill)
            }
            fill.color = 0xFF0097A7.toInt()
            for (x in floatArrayOf(a, b)) {
                c.drawRect(x - 1.5f * d, top - 2 * d, x + 1.5f * d, top + barH + 2 * d, fill)
                c.drawCircle(x, top - 2 * d, 3 * d, fill)
                c.drawCircle(x, top + barH + 2 * d, 3 * d, fill)
            }
            bar(a, resTop, b, resTop + barH, result)
        } else {
            bar(0f, top, w, top + barH, light)
            val cuts = arrayOf(floatArrayOf(0.18f, 0.38f), floatArrayOf(0.60f, 0.78f))
            for (cut in cuts) {
                fill.color = 0xFF9E5052.toInt()
                c.drawRect(w * cut[0], top + 0.75f * d, w * cut[1], top + barH - 0.75f * d, fill)
                fill.color = 0xFFB3261E.toInt()
                c.drawRect(w * cut[0] - d, top, w * cut[0] + d, top + barH, fill)
                c.drawRect(w * cut[1] - d, top, w * cut[1] + d, top + barH, fill)
            }
            fill.color = tick
            for (x in floatArrayOf(0.09f, 0.47f, 0.53f, 0.88f)) {
                c.drawRect(w * x - 0.5f * d, top + barH * 0.25f, w * x + 0.5f * d, top + barH * 0.75f, fill)
            }
            // kept parts, joined
            val k1 = w * 0.18f
            val k2 = w * (0.60f - 0.38f)
            val k3 = w * (1f - 0.78f)
            bar(0f, resTop, k1, resTop + barH, result)
            bar(k1, resTop, k1 + k2, resTop + barH, result)
            bar(k1 + k2, resTop, k1 + k2 + k3, resTop + barH, result)
        }
        canvasRef = null
    }
}
