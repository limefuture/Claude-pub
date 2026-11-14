package com.duplikatumkereso.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MatcherTest {

    private val interval = 10_000L
    private val params = MatchParams(frameThreshold = 10, minMatches = 10)

    /**
     * Valósághű modell: a "film" 30 mp-es jelenetekből áll, egy jeleneten belül a kép
     * alig változik. Egy videó a film [fromMs, toMs) szakasza, 10 mp-enként mintavételezve,
     * a mintavétel a videó saját 5. másodpercénél kezdődik.
     */
    private class Film(seed: Int, lengthMs: Long) {
        private val rnd = Random(seed)
        private val scenes = LongArray((lengthMs / 30_000 + 2).toInt()) { rnd.nextLong() }

        fun frameAt(ms: Long, noise: Random): Long {
            var h = scenes[(ms / 30_000).toInt()]
            repeat(noise.nextInt(0, 4)) { h = h xor (1L shl noise.nextInt(64)) }
            return h
        }
    }

    private fun clip(id: Long, film: Film, fromMs: Long, toMs: Long, noiseSeed: Int): VideoFingerprint {
        val noise = Random(noiseSeed)
        val duration = toMs - fromMs
        val start = minOf(interval / 2, duration / 2)
        val times = generateSequence(start) { it + interval }.takeWhile { it < duration }.toList()
        val hashes = LongArray(times.size) { film.frameAt(fromMs + times[it], noise) }
        return VideoFingerprint(id, duration, start, interval, hashes, BooleanArray(times.size) { true })
    }

    @Test
    fun subclipIsFoundWithMisalignedSampling() {
        val film = Film(1, 40 * 60_000L)
        val long = clip(1, film, 0, 30 * 60_000L, 11)
        // 7:23-tól 12:41-ig kivágott rész: a mintavétel nem esik egybe a hosszú videóéval
        val short = clip(2, film, 443_000L, 761_000L, 12)
        val m = Matcher.compare(long, short, params)
        assertTrue("részletet kellett találni, kapott: $m", m is VideoMatch.Subclip)
        m as VideoMatch.Subclip
        assertEquals(1L, m.longId)
        assertEquals(2L, m.shortId)
        assertTrue("kezdet ~443 mp, kapott ${m.startMs}", kotlin.math.abs(m.startMs - 443_000L) <= 10_000L)
        assertTrue(m.matchedFrames > 10)
    }

    @Test
    fun shortOverlapBelowThresholdIsIgnored() {
        val film = Film(2, 40 * 60_000L)
        val long = clip(1, film, 0, 30 * 60_000L, 21)
        // 90 mp-es részlet: legfeljebb 9 kocka -> nem éri el a 10-nél több egyezést
        val short = clip(2, film, 300_000L, 390_000L, 22)
        assertNull(Matcher.compare(long, short, params))
    }

    @Test
    fun sameLengthReencodedIsSameContent() {
        val film = Film(3, 20 * 60_000L)
        val a = clip(1, film, 0, 12 * 60_000L, 31)
        val b = clip(2, film, 0, 12 * 60_000L - 700, 32)
        val m = Matcher.compare(a, b, params)
        assertTrue("azonos tartalom kellett, kapott: $m", m is VideoMatch.SameContent)
    }

    @Test
    fun shortSameLengthVideosMatchToo() {
        val film = Film(4, 60_000L)
        val a = clip(1, film, 0, 25_000L, 41)
        val b = clip(2, film, 0, 25_400L, 42)
        assertTrue(Matcher.compare(a, b, params) is VideoMatch.SameContent)
    }

    @Test
    fun unrelatedVideosDoNotMatch() {
        val a = clip(1, Film(5, 20 * 60_000L), 0, 15 * 60_000L, 51)
        val b = clip(2, Film(6, 20 * 60_000L), 0, 6 * 60_000L, 52)
        val c = clip(3, Film(7, 20 * 60_000L), 0, 15 * 60_000L, 53)
        assertNull(Matcher.compare(a, b, params))
        assertNull(Matcher.compare(a, c, params))
        assertTrue(Matcher.findMatches(listOf(a, b, c), params).isEmpty())
    }

    @Test
    fun findMatchesUsesCandidateIndex() {
        val film = Film(8, 40 * 60_000L)
        val long = clip(10, film, 0, 30 * 60_000L, 81)
        val third = clip(11, film, 600_000L, 1_200_000L, 82)
        val other = clip(12, Film(9, 10 * 60_000L), 0, 9 * 60_000L, 83)
        val matches = Matcher.findMatches(listOf(other, long, third), params)
        assertEquals(1, matches.size)
        val m = matches[0] as VideoMatch.Subclip
        assertEquals(10L, m.longId)
        assertEquals(11L, m.shortId)
    }

    @Test
    fun invalidFramesAreIgnored() {
        val n = 40
        val black = 0L
        val a = VideoFingerprint(1, n * interval, 5000, interval, LongArray(n) { black }, BooleanArray(n) { false })
        val b = VideoFingerprint(2, 20 * interval, 5000, interval, LongArray(20) { black }, BooleanArray(20) { false })
        assertNull(Matcher.compare(a, b, params))
    }

    @Test
    fun similarImagesAreGrouped() {
        val rnd = Random(9)
        val base = rnd.nextLong()
        val near = base xor 0b1011L // 3 bit eltérés
        val far = rnd.nextLong()
        val groups = Matcher.groupSimilarImages(longArrayOf(1, 2, 3), longArrayOf(base, near, far), threshold = 6)
        assertEquals(1, groups.size)
        assertEquals(setOf(1L, 2L), groups[0].toSet())
    }

    @Test
    fun dHashAndInformative() {
        val gradient = IntArray(72) { (it % 9) * 20 } // balról jobbra világosodik
        assertEquals(0L, ImageHash.dHash(gradient))
        val reversed = IntArray(72) { (8 - it % 9) * 20 }
        assertEquals(64, java.lang.Long.bitCount(ImageHash.dHash(reversed)))
        assertTrue(ImageHash.isInformative(gradient))
        assertTrue(!ImageHash.isInformative(IntArray(72) { 3 }))
    }

    @Test
    fun folderRefParsing() {
        val f = FolderRef.fromDocumentId("primary:DCIM/Camera")
        assertNotNull(f)
        f!!
        assertEquals("external_primary", f.volume)
        assertEquals("DCIM/Camera/", f.relativePath)
        assertEquals("Belső tárhely › DCIM › Camera", f.label)
        val sd = FolderRef.fromDocumentId("1A2B-3C4D:Movies")!!
        assertEquals("1a2b-3c4d", sd.volume)
        assertEquals("Movies/", sd.relativePath)
        assertEquals("", FolderRef.fromDocumentId("primary:")!!.relativePath)
        assertNull(FolderRef.fromDocumentId("nincs-kettospont"))
        assertEquals("My\\_Videos/%", FolderRef.likePattern("My_Videos/", true))
        assertEquals("100\\%/", FolderRef.likePattern("100%/", false))
    }

    @Test
    fun formatting() {
        assertEquals("612 MB", Format.bytes(612L * 1024 * 1024))
        assertEquals("2,9 GB", Format.bytes((2.9 * 1024 * 1024 * 1024).toLong()))
        assertEquals("3:24", Format.duration(204_000))
        assertEquals("1:02:03", Format.duration(3_723_000))
    }
}
