package hu.videovago

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.nio.ByteOrder
import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Background thumbnails (nearest keyframe, cheap even for 4K HEVC) and an audio
 * waveform for the filmstrip. Display only, never touches the export.
 */
class MediaPreview(
    private val ctx: Context,
    private val uri: Uri,
    private val info: VideoInfo,
    private val thumbHeightPx: Int,
    private val onUpdate: () -> Unit,
) : FilmstripView.ThumbSource {

    private val lock = Object()
    private val cache = TreeMap<Long, Bitmap>()
    private val order = ArrayDeque<Long>()           // insertion order, for eviction
    private val pending = ArrayDeque<Long>()         // newest request served first
    private val pendingSet = HashSet<Long>()
    private val failed = HashSet<Long>()
    @Volatile private var closed = false

    private val sideways = info.rotation == 90 || info.rotation == 270
    private val dispW = if (sideways) info.height else info.width
    private val dispH = if (sideways) info.width else info.height

    private val worker = Thread({ thumbLoop() }, "thumbs").apply { isDaemon = true; start() }

    override fun get(keyUs: Long): Bitmap? = synchronized(lock) { cache[keyUs] }

    override fun request(keyUs: Long) {
        synchronized(lock) {
            if (cache.containsKey(keyUs) || keyUs in pendingSet || keyUs in failed) return
            pending.addLast(keyUs)
            pendingSet.add(keyUs)
            while (pending.size > 48) pendingSet.remove(pending.removeFirst())
            lock.notifyAll()
        }
    }

    override fun nearest(t: Long): Bitmap? = synchronized(lock) {
        val lo = cache.floorEntry(t)
        val hi = cache.ceilingEntry(t)
        when {
            lo == null -> hi?.value
            hi == null -> lo.value
            abs(t - lo.key) <= abs(hi.key - t) -> lo.value
            else -> hi.value
        }
    }

    fun close() {
        closed = true
        synchronized(lock) { lock.notifyAll() }
    }

    private fun thumbLoop() {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(ctx, uri)
        } catch (e: Exception) {
            return
        }
        val h = thumbHeightPx
        val w = max(16, (h.toFloat() * dispW / max(1, dispH)).toInt())
        try {
            while (!closed) {
                val key: Long = synchronized(lock) {
                    while (pending.isEmpty() && !closed) lock.wait()
                    if (closed) return
                    val k = pending.removeLast()
                    pendingSet.remove(k)
                    k
                }
                var bmp: Bitmap? = try {
                    // the box is in display orientation; retrievers return frames already rotated
                    mmr.getScaledFrameAtTime(key, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w * 2, h * 2)
                } catch (e: Exception) {
                    null
                }
                if (bmp != null && sideways && bmp.width > bmp.height) {
                    // some devices hand back the frame unrotated
                    val m = Matrix().apply { postRotate(info.rotation.toFloat()) }
                    bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                }
                synchronized(lock) {
                    if (bmp == null) {
                        failed.add(key)
                    } else {
                        cache[key] = bmp
                        order.addLast(key)
                        while (order.size > 400) cache.remove(order.removeFirst())?.let { /* let GC reclaim */ }
                    }
                }
                if (bmp != null) onUpdate()
            }
        } catch (_: InterruptedException) {
        } finally {
            runCatching { mmr.release() }
        }
    }

    /** Peak per bucket (0..1) of the first audio track; null when there is no audio or it can't be decoded. */
    fun computeWaveform(): FloatArray? {
        if (info.audioTrack < 0 || info.durationUs <= 0) return null
        val buckets = (info.durationUs / 25_000L).toInt().coerceIn(64, 4000)
        val peaks = FloatArray(buckets)
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, uri, null)
            ex.selectTrack(info.audioTrack)
            val fmt = ex.getTrackFormat(info.audioTrack)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return null
            val dec = MediaCodec.createDecoderByType(mime)
            codec = dec
            dec.configure(fmt, null, null, 0)
            dec.start()
            val bi = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            while (!outDone && !closed) {
                if (!inDone) {
                    val i = dec.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val buf = dec.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            dec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = dec.dequeueOutputBuffer(bi, 5_000)
                if (o >= 0) {
                    if (bi.size > 0) {
                        val ob = dec.getOutputBuffer(o)
                        if (ob != null) {
                            ob.position(bi.offset)
                            ob.limit(bi.offset + bi.size)
                            val sb = ob.slice().order(ByteOrder.nativeOrder()).asShortBuffer()
                            var peak = 0
                            var j = 0
                            val n = sb.remaining()
                            while (j < n) {
                                val v = abs(sb.get(j).toInt())
                                if (v > peak) peak = v
                                j += 3
                            }
                            val b = (bi.presentationTimeUs.toDouble() / info.durationUs * buckets).toInt()
                                .coerceIn(0, buckets - 1)
                            peaks[b] = max(peaks[b], peak / 32768f)
                        }
                    }
                    dec.releaseOutputBuffer(o, false)
                    if (bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { ex.release() }
        }
        // fill single empty buckets, then normalise
        for (i in 1 until buckets - 1) {
            if (peaks[i] == 0f) peaks[i] = min(peaks[i - 1], peaks[i + 1])
        }
        val mx = peaks.maxOrNull() ?: 0f
        if (mx <= 0f) return peaks
        for (i in peaks.indices) peaks[i] = (peaks[i] / mx).coerceIn(0f, 1f)
        return peaks
    }
}
