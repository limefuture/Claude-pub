package hu.videovago

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/** A kept range of the source, in microseconds. */
data class Segment(var startUs: Long, var endUs: Long)

class VideoInfo(
    val name: String,
    val sizeBytes: Long,
    val durationUs: Long,
    val width: Int,
    val height: Int,
    /** Clockwise display rotation already stored in the source. */
    val rotation: Int,
    val fps: Double,
    val videoMime: String,
    val audioMime: String?,
    val videoTrack: Int,
    val audioTrack: Int,
    val keyframes: LongArray,
    val location: Pair<Float, Float>?,
)

/**
 * Cuts and rotates without re-encoding: compressed samples are copied from
 * MediaExtractor straight into MediaMuxer, rotation goes into the MP4
 * display matrix (setOrientationHint).
 */
class LosslessCutter(
    private val ctx: Context,
    private val src: Uri,
    private val info: VideoInfo,
) {
    class Cancelled : Exception()

    @Volatile
    var cancelled = false

    /** Tracks the muxer could not take, e.g. an audio codec MP4 doesn't support. */
    val skipped = mutableListOf<String>()

    fun export(
        segments: List<Segment>,
        rotationCw: Int,
        keepAudio: Boolean,
        separate: Boolean,
        outputName: String? = null,
        onProgress: (Float, String) -> Unit,
    ): List<Uri> {
        val segs = segments.filter { it.endUs - it.startUs > 1_000 }.sortedBy { it.startUs }
        require(segs.isNotEmpty()) { "Nincs kijelölt szakasz." }
        val total = segs.sumOf { it.endUs - it.startUs }.coerceAtLeast(1)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        // MediaStore adds " (1)" etc. itself if the name is taken.
        val base = outputName?.trim()?.removeSuffix(".mp4")?.replace(Regex("""[\\/:*?"<>|]"""), "_")
            ?.takeIf { it.isNotBlank() }
            ?: (info.name.substringBeforeLast('.').ifBlank { "video" } + "_vagott_" + stamp)
        val outs = mutableListOf<Uri>()
        if (separate && segs.size > 1) {
            var done = 0L
            segs.forEachIndexed { i, s ->
                val label = "Szakasz ${i + 1}/${segs.size} mentése"
                outs += writeFile("${base}_${i + 1}.mp4", listOf(s), rotationCw, keepAudio) { us ->
                    onProgress((done + us).toFloat() / total, label)
                }
                done += s.endUs - s.startUs
            }
        } else {
            outs += writeFile("$base.mp4", segs, rotationCw, keepAudio) { us ->
                onProgress(us.toFloat() / total, "Mentés")
            }
        }
        return outs
    }

    private fun writeFile(
        name: String,
        segs: List<Segment>,
        rotationCw: Int,
        keepAudio: Boolean,
        progress: (Long) -> Unit,
    ): Uri {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Videovago")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Nem sikerült létrehozni a kimeneti fájlt.")
        try {
            val pfd = resolver.openFileDescriptor(uri, "rw")
                ?: throw IOException("Nem írható a kimeneti fájl.")
            pfd.use { mux(it.fileDescriptor, segs, rotationCw, keepAudio, progress) }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    private fun mux(
        fd: FileDescriptor,
        segs: List<Segment>,
        rotationCw: Int,
        keepAudio: Boolean,
        progress: (Long) -> Unit,
    ) {
        val vEx = MediaExtractor()
        var aEx: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        var started = false
        try {
            vEx.setDataSource(ctx, src, null)
            val m = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m

            val vFmt = vEx.getTrackFormat(info.videoTrack)
            // Rotation is set once, explicitly, below.
            if (vFmt.containsKey(MediaFormat.KEY_ROTATION)) vFmt.removeKey(MediaFormat.KEY_ROTATION)
            m.setOrientationHint(((info.rotation + rotationCw) % 360 + 360) % 360)
            info.location?.let { (lat, lon) -> runCatching { m.setLocation(lat, lon) } }
            val outV = try {
                m.addTrack(vFmt)
            } catch (e: Exception) {
                throw IOException("Ezt a videókódeket (${info.videoMime}) nem lehet MP4-be másolni ezen a telefonon.")
            }
            vEx.selectTrack(info.videoTrack)
            var bufSize = maxInput(vFmt)

            var outA = -1
            if (keepAudio && info.audioTrack >= 0) {
                val ex = MediaExtractor()
                ex.setDataSource(ctx, src, null)
                val aFmt = ex.getTrackFormat(info.audioTrack)
                try {
                    outA = m.addTrack(aFmt)
                    ex.selectTrack(info.audioTrack)
                    aEx = ex
                    bufSize = max(bufSize, maxInput(aFmt))
                } catch (e: Exception) {
                    skipped += "hang (${info.audioMime})"
                    ex.release()
                }
            }

            m.start()
            started = true

            var buf = ByteBuffer.allocateDirect(bufSize)
            val bi = MediaCodec.BufferInfo()
            val frameUs = if (info.fps > 1) (1_000_000.0 / info.fps).toLong() else 33_333L
            var accUs = 0L          // where the current segment starts in the output
            var doneUs = 0L         // for progress
            var lastAudioOut = -1L
            var lastProgress = 0L

            for (seg in segs) {
                val segLen = seg.endUs - seg.startUs
                // Lossless cuts can only start on a keyframe.
                vEx.seekTo(seg.startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                var baseUs = -1L
                while (true) {
                    val t = vEx.sampleTime
                    if (t < 0) break
                    if (vEx.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                        baseUs = t; break
                    }
                    vEx.advance()
                }
                if (baseUs < 0) continue
                aEx?.seekTo(baseUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

                var vDone = false
                var aDone = aEx == null
                var vEnd = 0L       // end of written video, relative to baseUs
                var aEnd = 0L
                var prevAudioIn = -1L
                var audioDur = 0L

                while (!vDone || !aDone) {
                    if (cancelled) throw Cancelled()
                    val vt = if (vDone) Long.MAX_VALUE else vEx.sampleTime
                    // Stop at the first frame (decode order) shown at/after the end point:
                    // every frame before it only references earlier frames, so the tail decodes cleanly.
                    if (!vDone && (vt < 0 || (vt >= seg.endUs && vt > baseUs))) {
                        vDone = true; continue
                    }
                    val at = if (aDone) Long.MAX_VALUE else aEx!!.sampleTime
                    if (!aDone && (at < 0 || at >= seg.endUs)) {
                        aDone = true; continue
                    }
                    if (!vDone && vt <= at) {
                        // Frames shown before the keyframe (open-GOP leading pictures) can't be decoded: skip.
                        if (vt >= baseUs) {
                            buf = copySample(vEx, m, outV, buf, bi, vt - baseUs + accUs)
                            vEnd = max(vEnd, vt - baseUs + frameUs)
                            val now = System.nanoTime()
                            if (now - lastProgress > 80_000_000L) {
                                lastProgress = now
                                progress(doneUs + (vt - seg.startUs).coerceIn(0, segLen))
                            }
                        }
                        vEx.advance()
                    } else {
                        val ex = aEx!!
                        if (prevAudioIn >= 0 && at > prevAudioIn) audioDur = at - prevAudioIn
                        prevAudioIn = at
                        if (at >= baseUs) {
                            val out = at - baseUs + accUs
                            if (out > lastAudioOut) {
                                buf = copySample(ex, m, outA, buf, bi, out)
                                lastAudioOut = out
                                aEnd = max(aEnd, at - baseUs + audioDur)
                            }
                        }
                        ex.advance()
                    }
                }
                accUs += max(vEnd, aEnd)
                doneUs += segLen
                progress(doneUs)
            }
            m.stop()
            started = false
        } finally {
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { vEx.release() }
            runCatching { aEx?.release() }
        }
    }

    private fun copySample(
        ex: MediaExtractor,
        muxer: MediaMuxer,
        track: Int,
        buf0: ByteBuffer,
        bi: MediaCodec.BufferInfo,
        ptsUs: Long,
    ): ByteBuffer {
        var buf = buf0
        val need = ex.sampleSize
        if (need > buf.capacity()) buf = ByteBuffer.allocateDirect((need * 1.25).toInt())
        buf.clear()
        val size = ex.readSampleData(buf, 0)
        if (size < 0) return buf
        var flags = 0
        if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        bi.set(0, size, ptsUs, flags)
        muxer.writeSampleData(track, buf, bi)
        return buf
    }

    private fun maxInput(f: MediaFormat): Int {
        val declared = if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
        return max(declared, 8 * 1024 * 1024)
    }

    companion object {
        /** Reads stream info and every keyframe position (from the sample table, no decoding). */
        fun probe(ctx: Context, uri: Uri): VideoInfo {
            var name = uri.lastPathSegment ?: "video"
            var size = 0L
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.let { name = it }
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }

            val ex = MediaExtractor()
            try {
                ex.setDataSource(ctx, uri, null)
                var vt = -1
                var at = -1
                for (i in 0 until ex.trackCount) {
                    val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                    if (vt < 0 && mime.startsWith("video/")) vt = i
                    else if (at < 0 && mime.startsWith("audio/")) at = i
                }
                require(vt >= 0) { "Nincs videósáv a fájlban." }
                val vf = ex.getTrackFormat(vt)
                val af = if (at >= 0) ex.getTrackFormat(at) else null

                val mmr = MediaMetadataRetriever()
                var rotation = 0
                var location: Pair<Float, Float>? = null
                var durationUs = if (vf.containsKey(MediaFormat.KEY_DURATION)) vf.getLong(MediaFormat.KEY_DURATION) else 0L
                try {
                    mmr.setDataSource(ctx, uri)
                    rotation = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    if (durationUs <= 0) {
                        durationUs = (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000
                    }
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)?.let { loc ->
                        Regex("""([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""").find(loc)?.let {
                            location = it.groupValues[1].toFloat() to it.groupValues[2].toFloat()
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    runCatching { mmr.release() }
                }
                if (rotation == 0 && vf.containsKey(MediaFormat.KEY_ROTATION)) rotation = vf.getInteger(MediaFormat.KEY_ROTATION)

                ex.selectTrack(vt)
                val keys = ArrayList<Long>()
                var count = 0
                var lastT = 0L
                while (true) {
                    val t = ex.sampleTime
                    if (t < 0) break
                    if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) keys.add(t)
                    lastT = max(lastT, t)
                    count++
                    ex.advance()
                }
                if (durationUs <= 0) durationUs = lastT
                var fps = 0.0
                if (vf.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    fps = runCatching { vf.getNumber(MediaFormat.KEY_FRAME_RATE)?.toDouble() ?: 0.0 }.getOrDefault(0.0)
                }
                if (fps <= 1 && durationUs > 0) fps = count * 1_000_000.0 / durationUs

                return VideoInfo(
                    name = name,
                    sizeBytes = size,
                    durationUs = durationUs,
                    width = vf.getInteger(MediaFormat.KEY_WIDTH),
                    height = vf.getInteger(MediaFormat.KEY_HEIGHT),
                    rotation = ((rotation % 360) + 360) % 360,
                    fps = fps,
                    videoMime = vf.getString(MediaFormat.KEY_MIME) ?: "?",
                    audioMime = af?.getString(MediaFormat.KEY_MIME),
                    videoTrack = vt,
                    audioTrack = at,
                    keyframes = keys.sorted().toLongArray(),
                    location = location,
                )
            } finally {
                ex.release()
            }
        }

        fun prevKey(keys: LongArray, t: Long, strict: Boolean = false): Long {
            if (keys.isEmpty()) return t
            var lo = 0
            var hi = keys.size - 1
            var ans = keys[0]
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val ok = if (strict) keys[mid] < t - 500 else keys[mid] <= t + 500
                if (ok) { ans = keys[mid]; lo = mid + 1 } else hi = mid - 1
            }
            return ans
        }

        fun nextKey(keys: LongArray, t: Long, fallback: Long): Long {
            var lo = 0
            var hi = keys.size - 1
            var ans = fallback
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (keys[mid] > t + 500) { ans = keys[mid]; hi = mid - 1 } else lo = mid + 1
            }
            return ans
        }

        fun fmt(us: Long): String {
            val ms = max(us, 0) / 1000
            val h = ms / 3_600_000
            val m = (ms / 60_000) % 60
            val s = (ms / 1000) % 60
            val f = ms % 1000
            return if (h > 0) String.format(Locale.US, "%d:%02d:%02d.%03d", h, m, s, f)
            else String.format(Locale.US, "%02d:%02d.%03d", m, s, f)
        }
    }
}
