package hu.videovago

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Thumbnail strip with audio waveform and segments.
 * Trim mode: one kept range, outside is washed out, teal frame.
 * Multi-cut mode: removed ranges in red, the selected one framed with handles.
 * Dashed lines lead from the selected range's edges to the time labels below.
 */
class FilmstripView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    interface ThumbSource {
        fun get(keyUs: Long): Bitmap?
        fun request(keyUs: Long)
        fun nearest(t: Long): Bitmap?
    }

    var durationUs = 0L
        set(v) { field = v; zoom = 1f; viewStart = 0; invalidate() }
    var posUs = 0L
        set(v) { field = v; follow(); invalidate() }
    var trimMode = false
        set(v) { field = v; invalidate() }
    var segments: List<Segment> = emptyList()
        set(v) { field = v; invalidate() }
    var selected = -1
        set(v) { field = v; invalidate() }
    var waveform: FloatArray? = null
        set(v) { field = v; invalidate() }
    var showWave = true
        set(v) { field = v; invalidate() }
    /** Display aspect ratio (w/h) of the video, for thumbnail slot width. */
    var aspect = 16f / 9f
        set(v) { field = v; invalidate() }
    var thumbs: ThumbSource? = null

    var onSeek: ((Long) -> Unit)? = null
    var onSelect: ((Int) -> Unit)? = null
    /** (isStartEdge, time, finished) */
    var onEdgeDrag: ((Boolean, Long, Boolean) -> Unit)? = null
    var onZoomChanged: (() -> Unit)? = null

    var zoom = 1f
        private set
    private var viewStart = 0L

    private val d = resources.displayMetrics.density
    private val stripH get() = 64 * d
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private val pThumb = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pWave = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6FFFFFF.toInt(); strokeWidth = 1.4f * d; strokeCap = Paint.Cap.ROUND
    }
    private val pWaveShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55000000; strokeWidth = 2.6f * d; strokeCap = Paint.Cap.ROUND
    }
    private val pFrame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d }
    private val pDash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.3f * d
        pathEffect = DashPathEffect(floatArrayOf(4 * d, 3 * d), 0f)
    }
    private val clip = Path()
    private val dash = Path()
    private val src = Rect()
    private val dst = RectF()
    private val rf = RectF()

    private val red = 0xFFC62828.toInt()
    private val teal = 0xFF00695C.toInt()

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean {
            setZoom(zoom * det.scaleFactor, xToT(det.focusX), det.focusX / max(1, width))
            return true
        }
    })

    // ------------------------------------------------------------------ geometry

    private fun span(): Long = max(1L, (durationUs / zoom).toLong())
    private fun tToX(t: Long): Float = (t - viewStart).toFloat() / span() * width
    private fun xToT(x: Float): Long = viewStart + (x / max(1, width) * span()).toLong()

    fun zoomBy(f: Float) = setZoom(zoom * f, posUs, 0.5f)

    private fun setZoom(z: Float, anchor: Long, frac: Float) {
        if (durationUs <= 0) return
        zoom = z.coerceIn(1f, 400f)
        val span = span()
        viewStart = (anchor - (span * frac).toLong()).coerceIn(0L, max(0L, durationUs - span))
        invalidate()
        onZoomChanged?.invoke()
    }

    private fun follow() {
        if (zoom <= 1f || dragMode != DragMode.NONE) return
        val span = span()
        if (posUs < viewStart || posUs > viewStart + span) {
            viewStart = (posUs - span / 10).coerceIn(0L, max(0L, durationUs - span))
        }
    }

    /** Scrolls so that t is visible (e.g. after nudging an edge). */
    fun reveal(t: Long) {
        val span = span()
        if (t < viewStart || t > viewStart + span) {
            viewStart = (t - span / 2).coerceIn(0L, max(0L, durationUs - span))
            invalidate()
        }
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val sh = stripH
        if (durationUs <= 0) {
            pFill.color = 0xFFE7E1EE.toInt()
            rf.set(0f, 0f, w, sh); c.drawRoundRect(rf, 8 * d, 8 * d, pFill)
            return
        }
        val span = span()

        c.save()
        clip.reset()
        rf.set(0f, 0f, w, sh)
        clip.addRoundRect(rf, 8 * d, 8 * d, Path.Direction.CW)
        c.clipPath(clip)
        pFill.color = 0xFF3A3840.toInt()
        c.drawRect(0f, 0f, w, sh, pFill)

        // thumbnails on a power-of-two time grid so cache keys stay stable while zooming
        if (w < 1f) { c.restore(); return }
        val slotUs = max(1000f, span * (sh * aspect.coerceIn(0.3f, 3f)) / w)
        val grid = max(1000L, (1000.0 * 2.0.pow((ln(slotUs / 1000.0) / ln(2.0)).roundToInt())).toLong())
        val src0 = thumbs
        var k = floor(viewStart.toDouble() / grid).toLong()
        val kEnd = ceil((viewStart + span).toDouble() / grid).toLong()
        while (k < kEnd) {
            val t0 = k * grid
            val key = min(t0 + grid / 2, max(0L, durationUs - 1))
            val x1 = tToX(t0)
            val x2 = tToX(t0 + grid)
            val bmp = src0?.get(key) ?: run { src0?.request(key); src0?.nearest(key) }
            if (bmp != null) drawCropped(c, bmp, x1, 0f, x2, sh)
            k++
        }

        // waveform
        val wf = waveform
        if (showWave && wf != null && wf.isNotEmpty()) {
            val step = 2.5f * d
            val mid = sh * 0.62f
            val amp = sh * 0.34f
            var x = 0f
            while (x < w) {
                val b1 = ((xToT(x).toDouble() / durationUs) * wf.size).toInt().coerceIn(0, wf.size - 1)
                val b2 = ((xToT(x + step).toDouble() / durationUs) * wf.size).toInt().coerceIn(b1, wf.size - 1)
                var peak = 0f
                for (i in b1..b2) peak = max(peak, wf[i])
                val hh = max(0.6f * d, peak * amp)
                c.drawLine(x, mid - hh, x, mid + hh, pWaveShadow)
                c.drawLine(x, mid - hh, x, mid + hh, pWave)
                x += step
            }
        }

        // segments
        if (trimMode) {
            segments.firstOrNull()?.let { s ->
                pFill.color = 0xB3FFFFFF.toInt()
                c.drawRect(0f, 0f, tToX(s.startUs), sh, pFill)
                c.drawRect(tToX(s.endUs), 0f, w, sh, pFill)
            }
        } else {
            segments.forEachIndexed { i, s ->
                pFill.color = if (i == selected) 0x8CC62828.toInt() else 0x66C62828
                c.drawRect(tToX(s.startUs), 0f, tToX(s.endUs), sh, pFill)
            }
        }

        // playhead
        val px = tToX(posUs)
        if (px in 0f..w) {
            pFill.color = 0x99000000.toInt()
            c.drawRect(px - 1.5f * d, 0f, px + 1.5f * d, sh, pFill)
            pFill.color = 0xFFFFFFFF.toInt()
            c.drawRect(px - 0.75f * d, 0f, px + 0.75f * d, sh, pFill)
        }
        c.restore()

        // selected frame + handles + dashed leaders
        val sel = selectedSegment() ?: return
        val color = if (trimMode) teal else red
        val x1 = tToX(sel.startUs)
        val x2 = tToX(sel.endUs)
        pFrame.color = color
        rf.set(x1 + 1.5f * d, 1.5f * d, x2 - 1.5f * d, sh - 1.5f * d)
        c.drawRoundRect(rf, 6 * d, 6 * d, pFrame)
        pFill.color = color
        c.drawRect(x1, 0f, x1 + 10 * d, sh, pFill)
        c.drawRect(x2 - 10 * d, 0f, x2, sh, pFill)
        pFill.color = 0xFFFFFFFF.toInt()
        rf.set(x1 + 3.5f * d, sh / 2 - 12 * d, x1 + 6.5f * d, sh / 2 + 12 * d)
        c.drawRoundRect(rf, 1.5f * d, 1.5f * d, pFill)
        rf.set(x2 - 6.5f * d, sh / 2 - 12 * d, x2 - 3.5f * d, sh / 2 + 12 * d)
        c.drawRoundRect(rf, 1.5f * d, 1.5f * d, pFill)

        pDash.color = color
        dash.reset()
        val y0 = sh + 3 * d
        val y1 = height - 3 * d
        dash.moveTo(x1.coerceIn(0f, w), y0)
        dash.lineTo(w * 0.2f, y1)
        dash.moveTo(x2.coerceIn(0f, w), y0)
        dash.lineTo(w * 0.8f, y1)
        c.drawPath(dash, pDash)
    }

    private fun drawCropped(c: Canvas, b: Bitmap, l: Float, t: Float, r: Float, btm: Float) {
        val dw = r - l
        val dh = btm - t
        if (dw <= 0 || dh <= 0) return
        val scale = max(dw / b.width, dh / b.height)
        val sw = (dw / scale).toInt().coerceAtMost(b.width)
        val shh = (dh / scale).toInt().coerceAtMost(b.height)
        val sx = (b.width - sw) / 2
        val sy = (b.height - shh) / 2
        src.set(sx, sy, sx + sw, sy + shh)
        dst.set(l, t, r, btm)
        c.drawBitmap(b, src, dst, pThumb)
    }

    private fun selectedSegment(): Segment? =
        if (trimMode) segments.firstOrNull() else segments.getOrNull(selected)

    // ------------------------------------------------------------------ touch

    private enum class DragMode { NONE, PENDING, START, END, PAN, SCRUB }
    private var dragMode = DragMode.NONE
    private var downX = 0f
    private var lastX = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (durationUs <= 0) return false
        scaler.onTouchEvent(e)
        if (scaler.isInProgress || e.pointerCount > 1) {
            if (dragMode == DragMode.START || dragMode == DragMode.END) {
                onEdgeDrag?.invoke(dragMode == DragMode.START, xToT(lastX).coerceIn(0, durationUs), true)
            }
            dragMode = DragMode.NONE
            return true
        }
        val t = xToT(e.x).coerceIn(0L, durationUs)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = e.x; lastX = e.x
                dragMode = DragMode.PENDING
                val sel = selectedSegment()
                if (sel != null && e.y <= stripH + 8 * d) {
                    val dx1 = abs(e.x - tToX(sel.startUs))
                    val dx2 = abs(e.x - tToX(sel.endUs))
                    val grab = 24 * d
                    if (min(dx1, dx2) < grab) dragMode = if (dx1 <= dx2) DragMode.START else DragMode.END
                }
            }
            MotionEvent.ACTION_MOVE -> {
                when (dragMode) {
                    DragMode.START, DragMode.END -> onEdgeDrag?.invoke(dragMode == DragMode.START, t, false)
                    DragMode.PENDING -> if (abs(e.x - downX) > slop) {
                        dragMode = if (zoom > 1f) DragMode.PAN else DragMode.SCRUB
                    }
                    DragMode.PAN -> {
                        val span = span()
                        viewStart = (viewStart - ((e.x - lastX) / width * span).toLong())
                            .coerceIn(0L, max(0L, durationUs - span))
                        invalidate()
                    }
                    DragMode.SCRUB -> onSeek?.invoke(t)
                    DragMode.NONE -> {}
                }
                lastX = e.x
            }
            MotionEvent.ACTION_UP -> {
                when (dragMode) {
                    DragMode.START, DragMode.END -> onEdgeDrag?.invoke(dragMode == DragMode.START, t, true)
                    DragMode.PENDING -> {
                        performClick()
                        if (trimMode) onSeek?.invoke(t)
                        else {
                            val hit = segments.indexOfFirst { t in it.startUs..it.endUs }
                            onSelect?.invoke(hit)
                            if (hit < 0) onSeek?.invoke(t)
                        }
                    }
                    else -> {}
                }
                dragMode = DragMode.NONE
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragMode == DragMode.START || dragMode == DragMode.END) {
                    onEdgeDrag?.invoke(dragMode == DragMode.START, t, true)
                }
                dragMode = DragMode.NONE
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
