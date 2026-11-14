package com.duplikatumkereso.data

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.provider.DocumentsContract
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import com.duplikatumkereso.core.FolderRef
import com.duplikatumkereso.core.ImageHash
import com.duplikatumkereso.core.VideoFingerprint
import android.os.ParcelFileDescriptor
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

    /**
     * A kiválasztott mappa tényleges tartalma a mappaválasztótól kapott hozzáférésen át.
     * A rendszer médiatárában (MediaStore) nem szereplő fájlokat is látja (pl. egyes letöltők
     * mappái); ahol a fájl a médiatárban is megvan, annak adatait (hossz, felbontás) használjuk.
     */
    fun listFolder(o: ScanOptions): ListResult {
        val fromMediaStore = runCatching { query(o) }.getOrDefault(emptyList())
        val treeUri = o.treeUri?.let { Uri.parse(it) }
            ?: return ListResult(fromMediaStore, 0)
        val byPath = fromMediaStore.associateBy { (it.relativePath + it.name).lowercase() }

        val out = ArrayList<MediaItem>()
        var notIndexed = 0
        val stack = ArrayDeque<String>()
        stack.add(o.documentId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        while (stack.isNotEmpty()) {
            val parent = stack.removeLast()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parent)
            val cursor = try {
                resolver.query(children, cols, null, null, null)
            } catch (e: Exception) {
                null // pl. elveszett hozzáférés: ezt a mappát kihagyjuk
            }
            cursor?.use { c ->
                while (c.moveToNext()) {
                    val docId = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2)
                    // A rejtett (ponttal kezdődő) mappákat és fájlokat is vizsgáljuk; csak a
                    // rendszer belső fájljait hagyjuk ki: .nomedia, kukában lévő és félkész fájlok.
                    if (name.equals(".nomedia", ignoreCase = true) ||
                        name.startsWith(".trashed-") ||
                        name.startsWith(".pending-")
                    ) continue
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (o.includeSubfolders) stack.add(docId)
                        continue
                    }
                    val isVideo = FolderRef.mediaKind(mime, name) ?: continue
                    if (isVideo && !o.videos || !isVideo && !o.images) continue
                    val rel = FolderRef.parentRelativePath(docId)
                    val indexed = byPath[(rel + name).lowercase()]
                    if (indexed != null) {
                        out.add(indexed)
                        continue
                    }
                    notIndexed++
                    val size = if (c.isNull(3)) 0L else c.getLong(3)
                    out.add(
                        MediaItem(
                            id = documentItemId(docId),
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                            name = name,
                            relativePath = rel,
                            size = size,
                            mime = mime?.takeIf { it.contains('/') && it != "application/octet-stream" }
                                ?: if (isVideo) "video/*" else "image/*",
                            isVideo = isVideo,
                            durationMs = 0,
                            width = 0,
                            height = 0,
                            dateModified = if (c.isNull(4)) 0 else c.getLong(4) / 1000,
                            volume = o.volume,
                        ),
                    )
                }
            }
        }
        // Ha a mappahozzáférés valamiért nem adott vissza semmit, a médiatár eredménye marad.
        return if (out.isEmpty()) ListResult(fromMediaStore, 0) else ListResult(out, notIndexed)
    }

    data class ListResult(val items: List<MediaItem>, val notIndexed: Int)

    /** Stabil, negatív azonosító a médiatárban nem szereplő fájloknak (nem ütközik a MediaStore id-kkel). */
    private fun documentItemId(docId: String): Long {
        val bits = java.util.UUID.nameUUIDFromBytes(docId.toByteArray()).mostSignificantBits
        return -(bits ushr 1) - 1
    }

    /** Előnézeti kép: rendszer-bélyegkép, ha nincs, a videó egy kockája vagy a kép kicsinyítve. */
    fun loadPreview(uri: Uri, isVideo: Boolean, size: Int): Bitmap? {
        runCatching { return resolver.loadThumbnail(uri, Size(size, size), null) }
        return if (isVideo) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, uri)
                r.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, size, size)
            } catch (e: Exception) {
                null
            } finally {
                runCatching { r.release() }
            }
        } else {
            runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                    val w = info.size.width
                    val h = info.size.height
                    val scale = max(1, max(w, h) / size)
                    decoder.setTargetSize(max(1, w / scale), max(1, h / scale))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }.getOrNull()
        }
    }

    /** Médiatárbeli megfelelő (kukázáshoz), ha van; null, ha a fájl nincs a médiatárban. */
    fun mediaStoreUriFor(item: MediaItem): Uri? {
        if (item.uri.authority == MediaStore.AUTHORITY) return item.uri
        // A getMediaUri általános "fájl" bejegyzést ad vissza; a kukázás viszont csak a
        // Képek/Videók gyűjtemény elemeit fogadja el. A .nomedia mappák fájljai nem ilyenek.
        val fileUri = runCatching { MediaStore.getMediaUri(context, item.uri) }.getOrNull() ?: return null
        val id = runCatching { ContentUris.parseId(fileUri) }.getOrNull()?.takeIf { it >= 0 } ?: return null
        val volume = runCatching { MediaStore.getVolumeName(fileUri) }.getOrDefault(item.volume)
        val type = runCatching {
            resolver.query(
                MediaStore.Files.getContentUri(volume),
                arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE),
                "${MediaStore.MediaColumns._ID}=?",
                arrayOf(id.toString()),
                null,
            )?.use { c -> if (c.moveToFirst()) c.getInt(0) else null }
        }.getOrNull()
        return when (type) {
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> itemUri(volume, id, isVideo = true)
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> itemUri(volume, id, isVideo = false)
            else -> null
        }
    }

    /** Kukázható-e a rendszerkukába (próba-kérés; nem módosít semmit). */
    fun canTrash(uri: Uri): Boolean = runCatching { trashRequest(listOf(uri)) }.isSuccess

    /** Végleges törlés a mappahozzáférésen át (médiatárban nem szereplő fájloknál). */
    fun deleteDocument(uri: Uri): Boolean =
        runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)

    /** Méret + 3 × 1 MB (eleje, közepe, vége): gyors előszűrés azonos méretű fájlokhoz. */
    fun partialHash(item: MediaItem): String {
        val chunk = 1 shl 20
        val md = MessageDigest.getInstance("SHA-256")
        val pfd = resolver.openFileDescriptor(item.uri, "r") ?: error("nem olvasható: ${item.name}")
        // Az AutoCloseInputStream a ParcelFileDescriptort zárja le (egyszer). Ha egy sima
        // FileInputStream zárná le a leírót, az Android fdsan védelme azonnal leállítaná az appot.
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { stream ->
            stream.channel.let { ch ->
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
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) w = h.also { h = w }
            return VideoFingerprint(item.id, duration, start, intervalMs, hashes, valid, w, h)
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { r.release() }
        }
    }

    /** Kép ujjlenyomata a rendszer bélyegképéből; null, ha nem olvasható. */
    fun imageHash(item: MediaItem): Long? = try {
        loadPreview(item.uri, isVideo = false, size = 64)?.let { ImageHash.dHash(toGray9x8(it)) }
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
