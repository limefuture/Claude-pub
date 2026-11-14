package com.duplikatumkereso.data

import android.net.Uri

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val relativePath: String,
    val size: Long,
    val mime: String,
    val isVideo: Boolean,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    /** másodperc */
    val dateModified: Long,
    val volume: String,
) {
    val pixels: Long get() = width.toLong() * height
    val cacheKey: String get() = "$volume/$id/$size/$dateModified"
}

enum class GroupKind { EXACT_VIDEO, SAME_CONTENT, IMAGE_EXACT, IMAGE_SIMILAR }

data class DupGroup(
    val kind: GroupKind,
    val items: List<MediaItem>,
    val keepId: Long,
    /** egyező kockák aránya százalékban (hasonlóságnál) */
    val similarity: Int? = null,
) {
    val reclaimable: Long get() = items.filter { it.id != keepId }.sumOf { it.size }
}

data class SubclipMatch(
    val long: MediaItem,
    val short: MediaItem,
    val startMs: Long,
    val endMs: Long,
    val matchedFrames: Int,
    val ratio: Double,
)

data class ScanResult(
    val createdAt: Long,
    val folderLabel: String,
    val fileCount: Int,
    val exact: List<DupGroup>,
    val sameContent: List<DupGroup>,
    val subclips: List<SubclipMatch>,
    val images: List<DupGroup>,
    /** Korábban összeomlást okozó, ezért kihagyott fájlok */
    val skippedFiles: List<String> = emptyList(),
    /** A rendszer médiatárában nem szereplő (közvetlenül a mappából olvasott) fájlok száma */
    val notIndexed: Int = 0,
) {
    val totalReclaimable: Long
        get() = (exact + sameContent + images).sumOf { it.reclaimable }
    val groupCount: Int get() = exact.size + sameContent.size + subclips.size + images.size

    /** A törölt fájlok kivétele az eredményből. */
    fun without(removed: Set<Long>): ScanResult {
        fun clean(groups: List<DupGroup>) = groups.mapNotNull { g ->
            val rest = g.items.filter { it.id !in removed }
            if (rest.size < 2) {
                null
            } else {
                val keep = if (rest.any { it.id == g.keepId }) g.keepId else bestOf(rest).id
                g.copy(items = rest, keepId = keep)
            }
        }
        return copy(
            exact = clean(exact),
            sameContent = clean(sameContent),
            images = clean(images),
            subclips = subclips.filter { it.long.id !in removed && it.short.id !in removed },
        )
    }

    companion object {
        /** A megtartandó fájl: legnagyobb felbontás, azon belül a nagyobb, majd a régebbi. */
        fun bestOf(items: List<MediaItem>): MediaItem =
            items.sortedWith(
                compareByDescending<MediaItem> { it.pixels }
                    .thenByDescending { it.size }
                    .thenBy { it.dateModified }
                    .thenBy { it.name.length },
            ).first()
    }
}

data class ScanOptions(
    val documentId: String,
    val volume: String,
    val relativePath: String,
    val label: String,
    val treeUri: String?,
    val includeSubfolders: Boolean,
    val videos: Boolean,
    val images: Boolean,
    val intervalSec: Int,
    val minMatches: Int,
    val frameThreshold: Int,
    val imageThreshold: Int,
    val durationToleranceSec: Int,
    val accurateFrames: Boolean,
)

sealed class ScanProgress {
    data object Idle : ScanProgress()
    data class Running(
        val phase: String,
        val done: Int,
        val total: Int,
        val detail: String = "",
    ) : ScanProgress()
    data object Done : ScanProgress()
    data object Cancelled : ScanProgress()
    data class Failed(val message: String) : ScanProgress()
}
