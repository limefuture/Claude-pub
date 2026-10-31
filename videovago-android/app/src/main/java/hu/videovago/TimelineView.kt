package hu.videovago

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Timeline: ruler, keyframe ticks, kept segments, selection, playhead. Drag to seek, pinch to zoom. */
class TimelineView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null,
) : View(ctx, attrs) {

    var durationUs = 0L
        set(v) { field = v; zoom = 1f; viewStart = 0L; invalidate() }
    var keyframes = LongArray(0)
        set(v) { field = v; invalidate() }
    var segments: List<Segment> = emptyList()
        set(v) { field = v; invalidate() }
    var activeSegment = -1
        set(v) { field = v; invalidate() }
    var inUs = 0L
        set(v) { field = v; invalidate() }
    var outUs = 0L
        set(v) { field = v; invalidate() }
    var posUs = 0L
        set(v) { field = v; follow(); invalidate() }

    var onSeek: ((Long) -> Unit)? = null

    private var zoom = 1f
    private var viewStart = 0L
    private val d = resources.displayMetrics.density

    private val pBg = Paint().apply { color = 0xFF111317.toInt() }
    private val pLine = Paint().apply { color = 0xFF2E333D.toInt(); strokeWidth = d }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8B93A3.toInt(); textSize = 10 * d; typeface = Typeface.MONOSPACE
    }
    private val pKey = Paint().apply { color = 0xFF6B7385.toInt() }
    private val pSeg = Paint().apply { color = 0x594F9CFF }
    private val pSegEdge = Paint().apply { color = 0xFF4F9CFF.toInt() }
    private val pSegActive = Paint().apply { color = 0xFFFFFFFF.toInt() }
    private val pSegNum = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 11 * d }
    private val pSel = Paint().apply { color = 0x4DFFC440 }
    private val pSelEdge = Paint().apply { color = 0xFFFFC440.toInt() }
    private val pHead = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF4D4D.toInt() }
    private val headPath = Path()

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean {
            if (durationUs <= 0 || width == 0) return true
            val anchorT = xToT(det.focusX)
            val frac = det.focusX / width
            zoom = (zoom * det.scaleFactor).coerceIn(1f, 500f)
            val span = span()
            viewStart = (anchorT - (span * frac).toLong()).coerceIn(0L, max(0L, durationUs - span))
            invalidate()
            return true
        }
    })

    private fun span(): Long = max(1L, (durationUs / zoom).toLong())
    private fun tToX(t: Long): Float = (t - viewStart).toFloat() / span() * width
    private fun xToT(x: Float): Long = viewStart + (x / max(1, width) * span()).toLong()

    private fun follow() {
        if (zoom <= 1f) return
        val span = span()
        if (posUs < viewStart || posUs > viewStart + span) {
            viewStart = (posUs - span / 10).coerceIn(0L, max(0L, durationUs - span))
        }
    }

    fun zoomBy(f: Float) {
        if (durationUs <= 0) return
        zoom = (zoom * f).coerceIn(1f, 500f)
        val span = span()
        viewStart = (posUs - span / 2).coerceIn(0L, max(0L, durationUs - span))
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (durationUs <= 0) return false
        scaler.onTouchEvent(e)
        if (scaler.isInProgress || e.pointerCount > 1) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                onSeek?.invoke(xToT(e.x).coerceIn(0L, durationUs))
            }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawRect(0f, 0f, w, h, pBg)
        if (durationUs <= 0) return
        val span = span()

        // ruler
        val steps = longArrayOf(100_000, 500_000, 1_000_000, 2_000_000, 5_000_000, 10_000_000, 15_000_000,
            30_000_000, 60_000_000, 120_000_000, 300_000_000, 600_000_000, 1_800_000_000, 3_600_000_000)
        val step = steps.firstOrNull { w / (span.toFloat() / it) > 70 * d } ?: steps.last()
        var t = (ceil(viewStart.toDouble() / step) * step).toLong()
        while (t <= viewStart + span) {
            val x = tToX(t)
            c.drawLine(x, 0f, x, 7 * d, pLine)
            val label = LosslessCutter.fmt(t).let { if (step >= 1_000_000) it.substringBefore('.') else it }
            c.drawText(label, x + 3 * d, 10 * d, pText)
            t += step
        }

        val top = 14 * d
        val bottom = h - 2 * d

        // keyframes
        if (keyframes.isNotEmpty()) {
            val visible = keyframes.count { it in viewStart..(viewStart + span) }
            if (visible < w / (2 * d)) {
                for (k in keyframes) {
                    if (k < viewStart || k > viewStart + span) continue
                    val x = tToX(k)
                    c.drawRect(x, bottom - 7 * d, x + max(1f, d), bottom, pKey)
                }
            }
        }

        // kept segments
        segments.forEachIndexed { i, s ->
            val x1 = tToX(s.startUs)
            val x2 = tToX(s.endUs)
            c.drawRect(x1, top, x2, bottom - 9 * d, pSeg)
            val edge = if (i == activeSegment) pSegActive else pSegEdge
            c.drawRect(x1, top, x1 + 2 * d, bottom - 9 * d, edge)
            c.drawRect(x2 - 2 * d, top, x2, bottom - 9 * d, edge)
            if (x2 - x1 > 18 * d) c.drawText("${i + 1}", x1 + 5 * d, top + 13 * d, pSegNum)
        }

        // selection
        if (outUs > inUs) {
            val x1 = tToX(inUs)
            val x2 = tToX(outUs)
            c.drawRect(x1, bottom - 9 * d, x2, bottom - 3 * d, pSel)
            c.drawRect(x1 - d, top, x1 + d, bottom, pSelEdge)
            c.drawRect(x2 - d, top, x2 + d, bottom, pSelEdge)
        }

        // playhead
        val px = min(max(tToX(posUs), 0f), w)
        c.drawRect(px - d, 0f, px + d, h, pHead)
        headPath.reset()
        headPath.moveTo(px - 6 * d, 0f)
        headPath.lineTo(px + 6 * d, 0f)
        headPath.lineTo(px, 7 * d)
        headPath.close()
        c.drawPath(headPath, pHead)
    }
}
