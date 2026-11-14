package com.duplikatumkereso.core

import kotlin.math.sqrt

/**
 * Felbontásfüggetlen képujjlenyomat (dHash).
 *
 * A képet 9×8 pixeles szürkeárnyalatos képre kicsinyítjük, majd minden sorban
 * összehasonlítjuk a szomszédos pixeleket. Az eredmény 64 bit: két kép annál
 * hasonlóbb, minél kevesebb bitben térnek el (Hamming-távolság).
 */
object ImageHash {
    const val WIDTH = 9
    const val HEIGHT = 8

    /** Egy ARGB pixel szürke értéke (0..255). */
    fun gray(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    /** [gray]: 9×8 = 72 szürke érték soronként. */
    fun dHash(gray: IntArray): Long {
        require(gray.size == WIDTH * HEIGHT) { "9×8 pixel kell" }
        var hash = 0L
        var bit = 0
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH - 1) {
                if (gray[y * WIDTH + x] > gray[y * WIDTH + x + 1]) {
                    hash = hash or (1L shl bit)
                }
                bit++
            }
        }
        return hash
    }

    /**
     * Fekete, fehér vagy egyszínű kocka (pl. elsötétítés a videó elején)
     * minden hasonló kockára "illeszkedne", ezért ezeket kihagyjuk az összevetésből.
     */
    fun isInformative(gray: IntArray, minStdDev: Double = 6.0): Boolean {
        val mean = gray.average()
        var sum = 0.0
        for (v in gray) {
            val d = v - mean
            sum += d * d
        }
        return sqrt(sum / gray.size) >= minStdDev
    }

    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)
}
