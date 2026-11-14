package com.duplikatumkereso.core

import org.junit.Assert.assertEquals
import org.junit.Test

class BulkSelectTest {
    private fun c(id: Long, date: Long, pixels: Long, size: Long, dur: Long = 60_000, video: Boolean = true) =
        Candidate(id, date, pixels, size, dur, video)

    private val hd = 1920L * 1080
    private val sd = 854L * 480

    @Test
    fun olderAndNewer() {
        val g = listOf(c(1, 100, hd, 10), c(2, 300, hd, 10), c(3, 200, hd, 10))
        assertEquals(setOf(1L, 3L), BulkSelect.pick(BulkRule.OLDER, g))
        assertEquals(setOf(2L, 3L), BulkSelect.pick(BulkRule.NEWER, g))
    }

    @Test
    fun sameDateSelectsNothing() {
        val g = listOf(c(1, 100, hd, 10), c(2, 100, hd, 10))
        assertEquals(emptySet<Long>(), BulkSelect.pick(BulkRule.OLDER, g))
        assertEquals(emptySet<Long>(), BulkSelect.pick(BulkRule.NEWER, g))
    }

    @Test
    fun lowerResolution() {
        val g = listOf(c(1, 1, hd, 900), c(2, 2, sd, 100), c(3, 3, sd, 120))
        assertEquals(setOf(2L, 3L), BulkSelect.pick(BulkRule.LOWER_RES, g))
        // ismeretlen felbontásnál nem találgatunk
        assertEquals(emptySet<Long>(), BulkSelect.pick(BulkRule.LOWER_RES, listOf(c(1, 1, hd, 9), c(2, 2, 0, 9))))
        assertEquals(emptySet<Long>(), BulkSelect.pick(BulkRule.LOWER_RES, listOf(c(1, 1, hd, 9), c(2, 2, hd, 5))))
    }

    @Test
    fun worseQualityUsesBitrateAtSameResolution() {
        val g = listOf(c(1, 1, hd, 500, dur = 100_000), c(2, 2, hd, 900, dur = 100_000))
        assertEquals(setOf(1L), BulkSelect.pick(BulkRule.WORSE_QUALITY, g))
        // nagyobb felbontás előbbre való, mint a nagyobb bitráta
        val g2 = listOf(c(1, 1, hd, 100, dur = 100_000), c(2, 2, sd, 900, dur = 100_000))
        assertEquals(setOf(2L), BulkSelect.pick(BulkRule.WORSE_QUALITY, g2))
    }

    @Test
    fun subclipPair() {
        val long = c(10, 100, hd, 800, dur = 811_000)
        val short = c(11, 200, sd, 300, dur = 340_000)
        assertEquals(setOf(10L), BulkSelect.pickPair(BulkRule.ORIGINAL, long, short))
        assertEquals(setOf(11L), BulkSelect.pickPair(BulkRule.CUT, long, short))
        assertEquals(setOf(10L), BulkSelect.pickPair(BulkRule.OLDER, long, short))
        assertEquals(setOf(11L), BulkSelect.pickPair(BulkRule.LOWER_RES, long, short))
        assertEquals(emptySet<Long>(), BulkSelect.pickPair(BulkRule.NONE, long, short))
    }
}
