package com.duplikatumkereso.data

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import com.duplikatumkereso.core.FolderRef
import com.duplikatumkereso.core.ImageHash
import com.duplikatumkereso.core.VideoFingerprint
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min

/**
 * Minden fájlhozzáférés itt történik, kizárólag helyben (MediaStore + ContentResolver).
 */
class MediaRepository(private val context: Context) {
    private val resolver: ContentResolver = context.contentResolver

    data class TrashedItem(
        val id: Long,
        val uri: Uri,
        val name: String,
        val relativePath: String,
        val size: Long,
        val isVideo: Boolean,
        /** másodperc */
        val expires: Long,
    )

    private val projection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaStore.MediaColumns.DURATION,
        MediaStore.MediaColumns.WIDTH,
        MediaStore.MediaColumns.HEIGHT,
        MediaStore.MediaColumns.DATE_EXPIRES,
    )

    private fun selection(relativePath: String, includeSub: Boolean, videos: Boolean, images: Boolean): Pair<String, Array<String>> {
        val types = buildList {
            if (videos) add(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString())
            if (images) add(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString())
        }
        val typeSql = types.joinToString(" OR ") { "${MediaStore.Files.FileColumns.MEDIA_TYPE}=?" }
        val sql = "($typeSql) AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\'"
        return sql to (types + FolderRef.likePattern(relativePath, includeSub)).toTypedArray()
    }

    private fun itemUri(volume: String, id: Long, isVideo: Boolean): Uri =
        if (isVideo) {
            ContentUris.withAppendedId(MediaStore.Video.Media.getContentUri(volume), id)
        } else {
            ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(volume), id)
        }

    fun query(o: ScanOptions): List<MediaItem> {
        if (!o.videos && !o.images) return emptyList()
        val (sql, args) = selection(o.relativePath, o.includeSubfolders, o.videos, o.images)
        val result = ArrayList<MediaItem>()
        resolver.query(MediaStore.Files.getContentUri(o.volume), projection, sql, args, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val isVideo = c.getInt(6) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                result.add(
                    MediaItem(
                        id = id,
                        uri = itemUri(o.volume, id, isVideo),
                        name = c.getString(1) ?: "?",
                        relativePath = c.getString(3) ?: "",
                        size = c.getLong(2),
                        mime = c.getString(4) ?: if (isVideo) "video/*" else "image/*",
                        isVideo = isVideo,
                        durationMs = if (c.isNull(7)) 0 else c.getLong(7),
                        width = if (c.isNull(8)) 0 else c.getInt(8),
                        height = if (c.isNull(9)) 0 else c.getInt(9),
                        dateModified = c.getLong(5),
                        volume = o.volume,
                    ),
                )
            }
        }
        return result
    }

    /** Méret + 3 × 1 MB (eleje, közepe, vége): gyors előszűrés azonos méretű fájlokhoz. */
    fun partialHash(item: MediaItem): String {
        val chunk = 1 shl 20
        val md = MessageDigest.getInstance("SHA-256")
        resolver.openFileDescriptor(item.uri, "r")!!.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                val size = ch.size()
                md.update(ByteBuffer.allocate(8).putLong(size).array())
                val buf = ByteBuffer.allocate(chunk)
                val positions = listOf(0L, max(0L, size / 2 - chunk / 2), max(0L, size - chunk)).distinct()
                for (pos in positions) {
                    buf.clear()
                    var p = pos
                    while (buf.hasRemaining()) {
                        val n = ch.read(buf, p)
                        if (n <= 0) break
                        p += n
                    }
                    buf.flip()
                    md.update(buf)
                }
            }
        }
        return md.digest().toHex()
    }

    /** Teljes SHA-256: csak az előszűrésen átjutott fájloknál, a 100%-os bizonyossághoz. */
    fun fullHash(item: MediaItem, isCancelled: () -> Boolean): String? {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 20)
        resolver.openInputStream(item.uri)!!.use { input ->
            while (true) {
                if (isCancelled()) return null
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().toHex()
    }

    /**
     * Képkockák kiolvasása [intervalMs] időközönként, a videó intervalMs/2 pontjától.
     * A kockákat a dekóder eleve kicsiben adja vissza, így a felbontás nem számít.
     */
    fun videoFingerprint(item: MediaItem, intervalMs: Long, accurate: Boolean, isCancelled: () -> Boolean): VideoFingerprint? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, item.uri)
            val duration = item.durationMs.takeIf { it > 0 }
                ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: return null
            if (duration <= 0) return null
            val start = min(intervalMs / 2, duration / 2)
            val times = generateSequence(start) { it + intervalMs }.takeWhile { it < duration }.toList()
            val option = if (accurate) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            val hashes = LongArray(times.size)
            val valid = BooleanArray(times.size)
            for ((i, t) in times.withIndex()) {
                if (isCancelled()) return null
                val bm = try {
                    r.getScaledFrameAtTime(t * 1000, option, 64, 64)
                } catch (e: Exception) {
                    null
                } ?: continue
                val gray = toGray9x8(bm)
                hashes[i] = ImageHash.dHash(gray)
                valid[i] = ImageHash.isInformative(gray)
            }
            return VideoFingerprint(item.id, duration, start, intervalMs, hashes, valid)
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { r.release() }
        }
    }

    /** Kép ujjlenyomata a rendszer bélyegképéből; null, ha nem olvasható. */
    fun imageHash(item: MediaItem): Long? = try {
        val bm = resolver.loadThumbnail(item.uri, Size(64, 64), null)
        ImageHash.dHash(toGray9x8(bm))
    } catch (e: Exception) {
        null
    }

    private fun toGray9x8(src: Bitmap): IntArray {
        val soft = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
        val small = Bitmap.createScaledBitmap(soft, ImageHash.WIDTH, ImageHash.HEIGHT, true)
        val px = IntArray(ImageHash.WIDTH * ImageHash.HEIGHT)
        small.getPixels(px, 0, ImageHash.WIDTH, 0, 0, ImageHash.WIDTH, ImageHash.HEIGHT)
        if (small !== soft) small.recycle()
        if (soft !== src) soft.recycle()
        src.recycle()
        return IntArray(px.size) { ImageHash.gray(px[it]) }
    }

    // ---- Kuka (Android 11+ rendszerkuka, 30 nap után végleg törlődik) ----

    fun trashRequest(uris: List<Uri>): PendingIntent = MediaStore.createTrashRequest(resolver, uris, true)

    fun restoreRequest(uris: List<Uri>): PendingIntent = MediaStore.createTrashRequest(resolver, uris, false)

    fun deleteRequest(uris: List<Uri>): PendingIntent = MediaStore.createDeleteRequest(resolver, uris)

    fun queryTrashed(folder: FolderRef, includeSub: Boolean): List<TrashedItem> {
        val (sql, args) = selection(folder.relativePath, includeSub, videos = true, images = true)
        val bundle = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, sql)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns.DATE_EXPIRES} DESC")
        }
        val out = ArrayList<TrashedItem>()
        resolver.query(MediaStore.Files.getContentUri(folder.volume), projection, bundle, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val isVideo = c.getInt(6) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                out.add(
                    TrashedItem(
                        id = id,
                        uri = itemUri(folder.volume, id, isVideo),
                        name = c.getString(1) ?: "?",
                        relativePath = c.getString(3) ?: "",
                        size = c.getLong(2),
                        isVideo = isVideo,
                        expires = if (c.isNull(10)) 0 else c.getLong(10),
                    ),
                )
            }
        }
        return out
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
