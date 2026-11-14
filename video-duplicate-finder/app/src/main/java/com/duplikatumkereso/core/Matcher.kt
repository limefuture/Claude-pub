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

    /**
     * A rövidebb videó a hosszabb videó részleteiből készült (akár több, összevágott részletből).
     * [segments]: a felhasznált szakaszok a hosszabb videóban (kezdet, vég ms).
     */
    data class Subclip(
        val longId: Long,
        val shortId: Long,
        val startMs: Long,
        val endMs: Long,
        override val matchedFrames: Int,
        override val ratio: Double,
        val segments: List<Pair<Long, Long>> = listOf(startMs to endMs),
    ) : VideoMatch()
}

/** Két videó összevetésének részletes eredménye (diagnosztikához is). */
data class PairScore(
    val shortId: Long,
    val longId: Long,
    /** egyező kockák, amelyek egy összefüggő részlet tagjai (a szomszédjuk is ugyanott egyezik) */
    val consistent: Int,
    /** a legjobb egyetlen időeltolásnál egyező kockák */
    val bestWindow: Int,
    /** a rövidebb videó használható kockái */
    val shortValid: Int,
    val sameLength: Boolean,
    val segments: List<Pair<Long, Long>>,
)

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
     * Két videó részletes összevetése. A rövidebb videó minden kockáját összehasonlítjuk a
     * hosszabb minden kockájával. Egy egyezés akkor "következetes", ha a rövid videó
     * szomszédos kockája is (közel) ugyanazzal az időeltolással egyezik – így egy több
     * részletből összevágott videó minden részlete beszámít, a véletlen egyezések nem.
     */
    fun score(a: VideoFingerprint, b: VideoFingerprint, p: MatchParams): PairScore {
        val (s, l) = if (a.durationMs <= b.durationMs) a to b else b to a
        val interval = max(1L, max(s.intervalMs, l.intervalMs))
        val tolerance = max(p.durationToleranceMs, (l.durationMs * p.durationToleranceFraction).toLong())
        val sameLength = abs(l.durationMs - s.durationMs) <= tolerance

        // rövid kocka indexe -> egyezések (eltolás-sáv, hosszú videóbeli idő)
        val perFrame = Array(s.hashes.size) { ArrayList<LongArray>(0) }
        val bins = HashMap<Long, HashSet<Int>>()
        for (i in s.hashes.indices) {
            if (!s.valid[i]) continue
            val hs = s.hashes[i]
            for (j in l.hashes.indices) {
                if (!l.valid[j]) continue
                if (ImageHash.distance(hs, l.hashes[j]) <= p.frameThreshold) {
                    val tl = l.timeAt(j)
                    val bin = Math.floorDiv(tl - s.timeAt(i), interval)
                    perFrame[i].add(longArrayOf(bin, tl))
                    bins.getOrPut(bin) { HashSet() }.add(i)
                }
            }
        }

        // legjobb egyetlen eltolás (összefüggő részlet / azonos videó)
        var bestWindow = 0
        for (bin in bins.keys) {
            val frames = HashSet<Int>(bins[bin].orEmpty())
            frames.addAll(bins[bin + 1].orEmpty())
            bestWindow = max(bestWindow, frames.size)
        }

        // Részletek: egymás utáni kockák, amelyeknek van közös (±1 sáv) időeltolásuk.
        // A lehetséges eltolások halmazát kockáról kockára szűkítjük; ha kiürül, új részlet kezdődik
        // (pl. egy vágásnál). Csak a legalább 2 kockás részletek számítanak.
        val segments = ArrayList<Pair<Long, Long>>()
        var consistent = 0
        var runFirst = -1
        var runLast = -1
        var runBins: Set<Long> = emptySet()
        fun closeRun() {
            if (runFirst >= 0 && runLast > runFirst && runBins.isNotEmpty()) {
                consistent += runLast - runFirst + 1
                // azonos mintavételnél az eltolások az intervallum többszörösei: átlaguk a valódi eltolás becslése
                val offset = (runBins.average() * interval).toLong()
                val half = interval / 2
                val st = (s.timeAt(runFirst) - half + offset).coerceIn(0L, l.durationMs)
                val en = (s.timeAt(runLast) + half + offset).coerceIn(0L, l.durationMs)
                if (en > st) segments.add(st to en)
            }
            runFirst = -1
            runLast = -1
            runBins = emptySet()
        }
        for (i in perFrame.indices) {
            val bins = perFrame[i].map { it[0] }.toSet()
            if (bins.isEmpty()) {
                closeRun()
                continue
            }
            if (runFirst >= 0 && i == runLast + 1) {
                val narrowed = bins.filter { b -> runBins.any { abs(it - b) <= 1 } }.toSet()
                if (narrowed.isNotEmpty()) {
                    runBins = narrowed
                    runLast = i
                    continue
                }
            }
            closeRun()
            runFirst = i
            runLast = i
            runBins = bins
        }
        closeRun()
        val padded = segments

        return PairScore(s.id, l.id, consistent, bestWindow, s.validCount, sameLength, padded)
    }

    fun decide(sc: PairScore, p: MatchParams): VideoMatch? {
        if (sc.sameLength) {
            val ratio = sc.bestWindow.toDouble() / max(1, sc.shortValid)
            return if (sc.bestWindow > 0 && (ratio >= p.sameLengthRatio || sc.bestWindow > p.minMatches)) {
                VideoMatch.SameContent(sc.shortId, sc.longId, sc.bestWindow, ratio)
            } else {
                null
            }
        }
        if (sc.consistent <= p.minMatches || sc.segments.isEmpty()) return null
        val ratio = sc.consistent.toDouble() / max(1, sc.shortValid)
        return VideoMatch.Subclip(
            longId = sc.longId,
            shortId = sc.shortId,
            startMs = sc.segments.minOf { it.first },
            endMs = sc.segments.maxOf { it.second },
            matchedFrames = sc.consistent,
            ratio = ratio,
            segments = sc.segments.sortedBy { it.first },
        )
    }

    fun compare(a: VideoFingerprint, b: VideoFingerprint, p: MatchParams): VideoMatch? = decide(score(a, b, p), p)

    /**
     * Minden jelölt pár pontozása. Kevés videónál (≤ [bruteForceLimit]) minden párt
     * összevetünk, így semmi nem csúszik ki; sok videónál a sáv-index adja a jelölteket.
     */
    fun analyze(
        fps: List<VideoFingerprint>,
        p: MatchParams,
        isCancelled: () -> Boolean = { false },
        bruteForceLimit: Int = 300,
    ): List<Pair<PairScore, VideoMatch?>> {
        val pairs = if (fps.size <= bruteForceLimit) {
            buildList { for (x in fps.indices) for (y in x + 1 until fps.size) add(x to y) }
        } else {
            candidatePairs(fps, p.bucketCap)
        }
        val out = ArrayList<Pair<PairScore, VideoMatch?>>()
        for ((x, y) in pairs) {
            if (isCancelled()) break
            val sc = score(fps[x], fps[y], p)
            if (sc.bestWindow > 0 || sc.consistent > 0) out.add(sc to decide(sc, p))
        }
        return out
    }

    fun findMatches(fps: List<VideoFingerprint>, p: MatchParams, isCancelled: () -> Boolean = { false }): List<VideoMatch> =
        analyze(fps, p, isCancelled).mapNotNull { it.second }

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
