package hu.videovago

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Seek bar under the video. Played part is dark teal, removed ranges are lighter,
 * segment boundaries show as ticks, the thumb is a tall bar.
 */
class SeekStripView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var durationUs = 0L
        set(v) { field = v; invalidate() }
    var posUs = 0L
        set(v) { field = v; invalidate() }
    /** Ranges that will not be in the output. */
    var removed: List<Segment> = emptyList()
        set(v) { field = v; invalidate() }
    var marks: LongArray = LongArray(0)
        set(v) { field = v; invalidate() }

    /** (time, finished) */
    var onSeek: ((Long, Boolean) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val cDark = 0xFF00695C.toInt()
    private val cLight = 0xFF9CC5BF.toInt()
    private val cTrack = 0xFFE7E1EE.toInt()
    private val cTrackRemoved = 0xFFF4F1F6.toInt()

    private val inset get() = 3 * d
    private fun x(t: Long): Float {
        if (durationUs <= 0) return inset
        return inset + (width - 2 * inset) * (t.toFloat() / durationUs)
    }
    private fun t(x: Float): Long {
        val f = ((x - inset) / max(1f, width - 2 * inset)).coerceIn(0f, 1f)
        return (f * durationUs).toLong()
    }

    override fun onDraw(c: Canvas) {
        val cy = height / 2f
        val th = 4 * d
        p.color = cTrack
        r.set(inset, cy - th / 2, width - inset, cy + th / 2)
        c.drawRoundRect(r, th / 2, th / 2, p)
        if (durationUs <= 0) return
        val px = x(posUs)

        p.color = cDark
        r.set(inset, cy - th / 2, px, cy + th / 2)
        c.drawRoundRect(r, th / 2, th / 2, p)

        for (s in removed) {
            val a = x(s.startUs)
            val b = x(s.endUs)
            // played part of the removed range
            if (a < px) {
                p.color = cLight
                c.drawRect(a, cy - th / 2, min(b, px), cy + th / 2, p)
            }
            if (b > px) {
                p.color = cTrackRemoved
                c.drawRect(max(a, px), cy - th / 2, b, cy + th / 2, p)
            }
        }

        p.color = cDark
        for (m in marks) {
            val mx = x(m)
            c.drawRect(mx - d, cy - 8 * d, mx + d, cy + 8 * d, p)
        }

        r.set(px - 2.5f * d, cy - 22 * d, px + 2.5f * d, cy + 22 * d)
        c.drawRoundRect(r, 2.5f * d, 2.5f * d, p)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (durationUs <= 0 || !isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                onSeek?.invoke(t(e.x), false)
            }
            MotionEvent.ACTION_UP -> {
                onSeek?.invoke(t(e.x), true)
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> onSeek?.invoke(t(e.x), true)
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
