package com.duplikatumkereso.data

import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.duplikatumkereso.core.ImageHash
import com.duplikatumkereso.core.VideoFingerprint
import kotlin.math.min

/**
 * Gyors képkocka-kiolvasás: videónként EGY hardveres dekódert indítunk, és csak a kulcskockákat
 * (önállóan dekódolható kockák) bontjuk ki. A világosságot közvetlenül a dekóder YUV kimenetének
 * Y síkjából olvassuk, RGB-képpé alakítás nélkül.
 *
 * A MediaMetadataRetriever minden egyes kockánál új dekódert indít; ez sokszor lassabb.
 * Bármilyen hiba esetén null-t adunk, és a hívó a régi módszerre vált.
 */
class FastFrameReader(private val context: Context) {

    fun fingerprint(
        item: MediaItem,
        intervalMs: Long,
        accurate: Boolean,
        isCancelled: () -> Boolean,
    ): VideoFingerprint? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, item.uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null

            val durationMs = when {
                item.durationMs > 0 -> item.durationMs
                format.containsKey(MediaFormat.KEY_DURATION) -> format.getLong(MediaFormat.KEY_DURATION) / 1000
                else -> 0
            }
            if (durationMs <= 0) return null
            val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
            var w = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else 0
            var h = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else 0
            if (rotation == 90 || rotation == 270) w = h.also { h = w }

            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(format, null, null, 0)
            c.start()

            val start = min(intervalMs / 2, durationMs / 2)
            val times = generateSequence(start) { it + intervalMs }.takeWhile { it < durationMs }.toList()
            val hashes = LongArray(times.size)
            val valid = BooleanArray(times.size)
            val info = MediaCodec.BufferInfo()
            var gotAnyOutput = false

            for ((i, t) in times.withIndex()) {
                if (isCancelled()) return null
                // Az első kocka előtt nem szabad flush-olni (a dekóder még nem kapta meg a beállító adatokat).
                if (gotAnyOutput) c.flush()
                extractor.seekTo(t * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val wantUs = if (accurate) t * 1000 else 0L
                val gray = decodeOne(c, extractor, info, wantUs, rotation) ?: continue
                gotAnyOutput = true
                hashes[i] = ImageHash.dHash(gray)
                valid[i] = ImageHash.isInformative(gray)
            }
            // Ha egyetlen kockát sem sikerült így kiolvasni, inkább a régi módszer próbálkozzon.
            if (!gotAnyOutput) return null
            return VideoFingerprint(item.id, durationMs, start, intervalMs, hashes, valid, w, h)
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * Mintákat adagol a dekódernek a keresett pontig, és visszaadja az első (pontos módban a
     * [wantUs] utáni) kimeneti kocka 9×8-as szürke képét.
     */
    private fun decodeOne(
        c: MediaCodec,
        ex: MediaExtractor,
        info: MediaCodec.BufferInfo,
        wantUs: Long,
        rotation: Int,
    ): IntArray? {
        var inputDone = false
        var attempts = 0
        while (attempts++ < 600) {
            if (!inputDone) {
                val inIdx = c.dequeueInputBuffer(5_000)
                if (inIdx >= 0) {
                    val buf = c.getInputBuffer(inIdx)
                    val size = if (buf != null) ex.readSampleData(buf, 0) else -1
                    if (size < 0) {
                        c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        c.queueInputBuffer(inIdx, 0, size, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
            }
            val outIdx = c.dequeueOutputBuffer(info, 5_000)
            if (outIdx >= 0) {
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (info.size > 0 && info.presentationTimeUs >= wantUs) {
                    val gray = c.getOutputImage(outIdx)?.use { lumaGrid(it, rotation) }
                    c.releaseOutputBuffer(outIdx, false)
                    if (gray != null) return gray
                } else {
                    c.releaseOutputBuffer(outIdx, false)
                }
                if (eos) return null
            }
        }
        return null
    }

    /** 9×8 rács átlagos világossága a kép Y síkjából, a forgatást figyelembe véve. */
    private fun lumaGrid(img: Image, rotation: Int): IntArray? {
        val plane = img.planes.firstOrNull() ?: return null
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixStride = plane.pixelStride
        val crop = img.cropRect
        val cw = crop.width()
        val ch = crop.height()
        if (cw <= 0 || ch <= 0) return null
        val gw = ImageHash.WIDTH
        val gh = ImageHash.HEIGHT
        val samples = 8 // cellánként 8×8 mintapont (közel területi átlag, mint a kicsinyítés)
        val out = IntArray(gw * gh)
        for (gy in 0 until gh) {
            for (gx in 0 until gw) {
                var sum = 0
                for (sy in 0 until samples) {
                    for (sx in 0 until samples) {
                        // megjelenítési koordináta 0..1 között
                        val u = (gx + (sx + 0.5f) / samples) / gw
                        val v = (gy + (sy + 0.5f) / samples) / gh
                        // vissza a tárolt (forgatás előtti) kép koordinátáira
                        val (su, sv) = when (rotation) {
                            90 -> v to (1f - u)
                            180 -> (1f - u) to (1f - v)
                            270 -> (1f - v) to u
                            else -> u to v
                        }
                        val px = crop.left + (su * cw).toInt().coerceIn(0, cw - 1)
                        val py = crop.top + (sv * ch).toInt().coerceIn(0, ch - 1)
                        sum += buf.get(py * rowStride + px * pixStride).toInt() and 0xFF
                    }
                }
                out[gy * gw + gx] = sum / (samples * samples)
            }
        }
        return out
    }
}
