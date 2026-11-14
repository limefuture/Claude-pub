package com.duplikatumkereso.core

import java.util.Locale

object Format {
    private val hu = Locale.forLanguageTag("hu-HU")

    fun bytes(b: Long): String {
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            b >= gb -> String.format(hu, "%.1f GB", b / gb)
            b >= 100 * mb -> String.format(hu, "%.0f MB", b / mb)
            b >= mb -> String.format(hu, "%.1f MB", b / mb)
            b >= kb -> String.format(hu, "%.0f kB", b / kb)
            else -> "$b B"
        }
    }

    fun duration(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(hu, "%d:%02d:%02d", h, m, s) else String.format(hu, "%d:%02d", m, s)
    }

    fun count(n: Int): String = String.format(hu, "%,d", n).replace(' ', ' ')
}

/** A rendszer mappaválasztójából kapott mappa. */
data class FolderRef(
    /** pl. "primary:DCIM/Camera" */
    val documentId: String,
    /** MediaStore kötet: "external_primary" vagy az SD-kártya azonosítója */
    val volume: String,
    /** MediaStore RELATIVE_PATH formátum: "DCIM/Camera/" (gyökérnél üres) */
    val relativePath: String,
    val label: String,
    /** A mappaválasztótól kapott (tartós) hozzáférés URI-ja; régi mentésnél null. */
    val treeUri: String? = null,
) {
    companion object {
        private val videoExt = setOf("mp4", "m4v", "mkv", "mov", "avi", "webm", "3gp", "3g2", "ts", "mts", "m2ts", "wmv", "flv", "mpg", "mpeg")
        private val imageExt = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp", "avif")

        /** true = videó, false = kép, null = egyik sem (a MIME típus vagy a kiterjesztés alapján). */
        fun mediaKind(mime: String?, name: String): Boolean? {
            val m = mime.orEmpty().lowercase(Locale.ROOT)
            if (m.startsWith("video/")) return true
            if (m.startsWith("image/")) return false
            val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return when (ext) {
                in videoExt -> true
                in imageExt -> false
                else -> null
            }
        }

        /** Dokumentum-azonosítóból ("primary:Download/a/b.mp4") a szülőmappa RELATIVE_PATH formában ("Download/a/"). */
        fun parentRelativePath(documentId: String): String {
            val path = documentId.substringAfter(':').trim('/')
            val parent = path.substringBeforeLast('/', "")
            return if (parent.isEmpty()) "" else "$parent/"
        }

        fun fromDocumentId(documentId: String, treeUri: String? = null): FolderRef? =
            fromDocumentIdInternal(documentId)?.copy(treeUri = treeUri)

        private fun fromDocumentIdInternal(documentId: String): FolderRef? {
            val idx = documentId.indexOf(':')
            if (idx <= 0) return null
            val vol = documentId.substring(0, idx)
            val path = documentId.substring(idx + 1).trim('/')
            val primary = vol.equals("primary", ignoreCase = true)
            val volume = if (primary) "external_primary" else vol.lowercase(Locale.ROOT)
            val rel = if (path.isEmpty()) "" else "$path/"
            val root = if (primary) "Belső tárhely" else "SD-kártya"
            val label = (listOf(root) + path.split('/').filter { it.isNotEmpty() }).joinToString(" › ")
            return FolderRef(documentId, volume, rel, label)
        }

        /** SQL LIKE minta a RELATIVE_PATH oszlopra (ESCAPE '\'). */
        fun likePattern(relativePath: String, includeSubfolders: Boolean): String {
            val escaped = relativePath
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_")
            return if (includeSubfolders) "$escaped%" else escaped
        }
    }
}
