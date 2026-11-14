package com.duplikatumkereso.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Egy videó ujjlenyomata: [intervalMs] időközönként vett képkockák dHash értékei.
 * Az i. kocka időpontja: startMs + i * intervalMs.
 */
class VideoFingerprint(
    val id: Long,
    val durationMs: Long,
    val startMs: Long,
    val intervalMs: Long,
    val hashes: LongArray,
    val valid: BooleanArray,
    val width: Int = 0,
    val height: Int = 0,
) {
    init {
        require(hashes.size == valid.size)
    }

    fun timeAt(i: Int): Long = startMs + i * intervalMs
    val validCount: Int get() = valid.count { it }
}

data class MatchParams(
    /** Két képkocka akkor "egyezik", ha legfeljebb ennyi bitben tér el (0..64). */
    val frameThreshold: Int = 10,
    /** Részletnél: ennél TÖBB egyező kocka esetén jelez. */
    val minMatches: Int = 10,
    /** Ekkora hosszeltérésen belül a két videót azonos hosszúnak tekintjük. */
    val durationToleranceMs: Long = 2_000,
    val durationToleranceFraction: Double = 0.01,
    /** Azonos hosszúságnál a rövidebb videó kockáinak legalább ekkora része egyezzen. */
    val sameLengthRatio: Double = 0.5,
    /** Ennél több videóban előforduló hash-sávot nem használunk jelöltkeresésre (pl. sötét jelenetek). */
    val bucketCap: Int = 64,
)

sealed class VideoMatch {
    abstract val matchedFrames: Int
    abstract val ratio: Double

    /** Azonos hosszúságú, tartalmilag egyező videók (pl. más felbontás). */
    data class SameContent(
        val a: Long,
        val b: Long,
        override val matchedFrames: Int,
        override val ratio: Double,
    ) : VideoMatch()

    /** A rövidebb videó a hosszabb [startMs]..[endMs] szakaszából készült. */
    data class Subclip(
        val longId: Long,
        val shortId: Long,
        val startMs: Long,
        val endMs: Long,
        override val matchedFrames: Int,
        override val ratio: Double,
    ) : VideoMatch()
}

object Matcher {

    /**
     * Jelölt videópárok keresése. A 64 bites hash-t 4 db 16 bites sávra bontjuk;
     * ha két videóban van olyan kocka, amelynek valamelyik sávja pontosan egyezik,
     * a pár jelölt lesz. Így nem kell minden videót mindennel összevetni.
     */
    fun candidatePairs(fps: List<VideoFingerprint>, bucketCap: Int): List<Pair<Int, Int>> {
        val buckets = HashMap<Long, MutableList<Int>>()
        for ((v, fp) in fps.withIndex()) {
            for (i in fp.hashes.indices) {
                if (!fp.valid[i]) continue
                val h = fp.hashes[i]
                for (band in 0 until 4) {
                    val key = (band.toLong() shl 16) or ((h ushr (16 * band)) and 0xFFFF)
                    val list = buckets.getOrPut(key) { ArrayList(2) }
                    if (list.isEmpty() || list[list.size - 1] != v) list.add(v)
                }
            }
        }
        val pairs = HashSet<Long>()
        val n = fps.size.toLong()
        for (list in buckets.values) {
            if (list.size < 2) continue
            val distinct = list.distinct()
            if (distinct.size < 2 || distinct.size > bucketCap) continue
            for (x in distinct.indices) {
                for (y in x + 1 until distinct.size) {
                    val a = min(distinct[x], distinct[y])
                    val b = max(distinct[x], distinct[y])
                    pairs.add(a * n + b)
                }
            }
        }
        return pairs.map { (it / n).toInt() to (it % n).toInt() }
    }

    /**
     * Két videó összevetése. A rövidebb videó minden kockáját összehasonlítjuk a
     * hosszabb minden kockájával, és az egyezéseket időeltolás szerint csoportosítjuk.
     * Valódi egyezésnél a kockák (nagyjából) ugyanazzal az eltolással egyeznek,
     * véletlen egyezéseknél szétszórva.
     */
    fun compare(a: VideoFingerprint, b: VideoFingerprint, p: MatchParams): VideoMatch? {
        val (s, l) = if (a.durationMs <= b.durationMs) a to b else b to a
        val interval = max(1L, max(s.intervalMs, l.intervalMs))

        // eltolás-sáv -> (rövid videó kockaindexe, pontos eltolás)
        val bins = HashMap<Long, MutableList<LongArray>>()
        for (i in s.hashes.indices) {
            if (!s.valid[i]) continue
            val hs = s.hashes[i]
            for (j in l.hashes.indices) {
                if (!l.valid[j]) continue
                if (ImageHash.distance(hs, l.hashes[j]) <= p.frameThreshold) {
                    val offset = l.timeAt(j) - s.timeAt(i)
                    val bin = Math.floorDiv(offset, interval)
                    bins.getOrPut(bin) { ArrayList() }.add(longArrayOf(i.toLong(), offset))
                }
            }
        }
        if (bins.isEmpty()) return null

        // A mintavétel miatt a valódi eltolás két szomszédos sávba eshet: ablak = [bin, bin+1]
        var bestCount = 0
        var bestOffsets: List<Long> = emptyList()
        for (bin in bins.keys) {
            val entries = bins[bin].orEmpty() + bins[bin + 1].orEmpty()
            val frames = HashSet<Long>()
            for (e in entries) frames.add(e[0])
            if (frames.size > bestCount) {
                bestCount = frames.size
                bestOffsets = entries.map { it[1] }
            }
        }
        val ratio = bestCount.toDouble() / max(1, s.validCount)

        val tolerance = max(p.durationToleranceMs, (l.durationMs * p.durationToleranceFraction).toLong())
        val sameLength = abs(l.durationMs - s.durationMs) <= tolerance

        return if (sameLength) {
            if (bestCount > 0 && (ratio >= p.sameLengthRatio || bestCount > p.minMatches)) {
                VideoMatch.SameContent(a.id, b.id, bestCount, ratio)
            } else {
                null
            }
        } else if (bestCount > p.minMatches) {
            val sorted = bestOffsets.sorted()
            val median = sorted[sorted.size / 2]
            val start = median.coerceIn(0, l.durationMs)
            val end = min(start + s.durationMs, l.durationMs)
            VideoMatch.Subclip(l.id, s.id, start, end, bestCount, ratio)
        } else {
            null
        }
    }

    fun findMatches(fps: List<VideoFingerprint>, p: MatchParams, isCancelled: () -> Boolean = { false }): List<VideoMatch> {
        val result = ArrayList<VideoMatch>()
        for ((x, y) in candidatePairs(fps, p.bucketCap)) {
            if (isCancelled()) break
            compare(fps[x], fps[y], p)?.let { result.add(it) }
        }
        return result
    }

    /**
     * Hasonló képek csoportosítása. 8 db 8 bites sáv: ha két hash legfeljebb 7 bitben tér el,
     * biztosan van közös sávjuk, így egyetlen hasonló párt sem hagyunk ki.
     */
    fun groupSimilarImages(
        ids: LongArray,
        hashes: LongArray,
        threshold: Int,
        bucketCap: Int = 400,
    ): List<List<Long>> {
        val n = ids.size
        val uf = UnionFind(n)
        val buckets = HashMap<Int, MutableList<Int>>()
        for (i in 0 until n) {
            for (band in 0 until 8) {
                val key = (band shl 8) or ((hashes[i] ushr (8 * band)) and 0xFF).toInt()
                buckets.getOrPut(key) { ArrayList() }.add(i)
            }
        }
        for (list in buckets.values) {
            if (list.size < 2 || list.size > bucketCap) continue
            for (x in list.indices) {
                for (y in x + 1 until list.size) {
                    val i = list[x]
                    val j = list[y]
                    if (uf.find(i) == uf.find(j)) continue
                    if (ImageHash.distance(hashes[i], hashes[j]) <= threshold) uf.union(i, j)
                }
            }
        }
        return uf.groups().filter { it.size > 1 }.map { g -> g.map { ids[it] } }
    }
}

class UnionFind(n: Int) {
    private val parent = IntArray(n) { it }

    fun find(x: Int): Int {
        var r = x
        while (parent[r] != r) r = parent[r]
        var c = x
        while (parent[c] != r) {
            val next = parent[c]
            parent[c] = r
            c = next
        }
        return r
    }

    fun union(a: Int, b: Int) {
        val ra = find(a)
        val rb = find(b)
        if (ra != rb) parent[rb] = ra
    }

    fun groups(): Collection<List<Int>> = parent.indices.groupBy { find(it) }.values
}
