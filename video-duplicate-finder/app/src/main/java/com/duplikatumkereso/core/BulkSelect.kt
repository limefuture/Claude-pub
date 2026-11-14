package com.duplikatumkereso.core

/** Egy fájl tömeges kijelöléshez szükséges adatai. */
data class Candidate(
    val id: Long,
    /** másodperc */
    val date: Long,
    val pixels: Long,
    val size: Long,
    val durationMs: Long,
    val isVideo: Boolean,
)

enum class BulkRule(val label: String) {
    OLDER("Régebbi"),
    NEWER("Újabb"),
    ORIGINAL("Eredeti"),
    CUT("Vágott"),
    LOWER_RES("Alacsonyabb felbontású"),
    WORSE_QUALITY("Rosszabb minőségű"),
    NONE("Semmi"),
}

/**
 * Tömeges kijelölés: egy csoporton (vagy részlet-páron) belül mely fájlok kerüljenek törlésre.
 * Ha a szabály szerint nincs különbség a fájlok között, nem jelöl ki semmit.
 */
object BulkSelect {

    /** Minőségi pontszám: elsősorban felbontás, azonos felbontásnál videón bitráta, képen fájlméret. */
    private fun quality(c: Candidate): Pair<Long, Long> {
        val second = if (c.isVideo && c.durationMs > 0) c.size * 1000 / c.durationMs else c.size
        return c.pixels to second
    }

    private val qualityOrder = compareBy<Candidate>({ quality(it).first }, { quality(it).second })

    /** Mindet, kivéve a [better] szerint legjobbakat (holtversenyben mindegyik legjobb megmarad). */
    private fun allButBest(group: List<Candidate>, better: Comparator<Candidate>): Set<Long> {
        val best = group.maxWithOrNull(better) ?: return emptySet()
        val keep = group.filter { better.compare(it, best) == 0 }
        if (keep.size == group.size) return emptySet()
        return group.filter { it !in keep }.map { it.id }.toSet()
    }

    /** Csoport (Pontos / Felbontás / Képek fül). */
    fun pick(rule: BulkRule, group: List<Candidate>): Set<Long> = when (rule) {
        BulkRule.OLDER -> allButBest(group, compareBy { it.date })
        BulkRule.NEWER -> allButBest(group, compareByDescending { it.date })
        BulkRule.LOWER_RES -> if (group.any { it.pixels <= 0 }) emptySet() else allButBest(group, compareBy { it.pixels })
        BulkRule.WORSE_QUALITY -> allButBest(group, qualityOrder)
        BulkRule.ORIGINAL, BulkRule.CUT, BulkRule.NONE -> emptySet()
    }

    /** Részlet-pár: [long] az eredeti, [short] a vágott. */
    fun pickPair(rule: BulkRule, long: Candidate, short: Candidate): Set<Long> = when (rule) {
        BulkRule.ORIGINAL -> setOf(long.id)
        BulkRule.CUT -> setOf(short.id)
        BulkRule.NONE -> emptySet()
        else -> pick(rule, listOf(long, short))
    }

    fun rulesFor(isSubclipTab: Boolean, isExactTab: Boolean): List<BulkRule> = when {
        isSubclipTab -> listOf(
            BulkRule.ORIGINAL, BulkRule.CUT, BulkRule.OLDER, BulkRule.NEWER,
            BulkRule.LOWER_RES, BulkRule.WORSE_QUALITY, BulkRule.NONE,
        )
        // bájtra azonos fájloknál csak a dátum különbözhet
        isExactTab -> listOf(BulkRule.OLDER, BulkRule.NEWER, BulkRule.NONE)
        else -> listOf(BulkRule.OLDER, BulkRule.NEWER, BulkRule.LOWER_RES, BulkRule.WORSE_QUALITY, BulkRule.NONE)
    }
}
