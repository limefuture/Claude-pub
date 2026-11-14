package com.duplikatumkereso.data

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Védelem a rendszer médiadekóderében bekövetkező (nem elkapható) összeomlások ellen.
 * Minden fájl feldolgozása előtt feljegyezzük, mit dolgozunk fel. Ha az app közben leáll,
 * a következő vizsgálat ezeket a fájlokat kihagyja, és szól róluk.
 */
class CrashGuard(context: Context) {
    private val inProgressFile = File(context.filesDir, "folyamatban.txt")
    private val skipFile = File(context.filesDir, "kihagyott.txt")
    private val current = ConcurrentHashMap<String, String>()

    /** Az előző, félbeszakadt futásban épp feldolgozott fájlok; ezeket feketelistára tesszük. */
    fun recoverFromPreviousRun(): List<String> {
        if (!inProgressFile.exists()) return emptyList()
        val lines = runCatching { inProgressFile.readLines() }.getOrDefault(emptyList()).filter { it.isNotBlank() }
        inProgressFile.delete()
        if (lines.isNotEmpty()) skipFile.appendText(lines.joinToString("\n", postfix = "\n"))
        return lines.map { it.substringAfter('\t') }
    }

    /** kulcs -> fájl elérési útja */
    fun skipped(): Map<String, String> =
        runCatching { skipFile.readLines() }.getOrDefault(emptyList())
            .filter { it.isNotBlank() }
            .associate { it.substringBefore('\t') to it.substringAfter('\t') }

    fun clearSkipped() {
        skipFile.delete()
    }

    inline fun <T> guard(item: MediaItem, block: () -> T): T {
        begin(item)
        try {
            return block()
        } finally {
            end(item)
        }
    }

    fun begin(item: MediaItem) {
        current[item.cacheKey] = "${item.relativePath}${item.name}"
        flush()
    }

    fun end(item: MediaItem) {
        current.remove(item.cacheKey)
        flush()
    }

    /** Sikeres/megszakított vizsgálat végén. */
    fun finish() {
        current.clear()
        inProgressFile.delete()
    }

    @Synchronized
    private fun flush() {
        runCatching {
            inProgressFile.writeText(current.entries.joinToString("\n") { "${it.key}\t${it.value}" })
        }
    }
}
