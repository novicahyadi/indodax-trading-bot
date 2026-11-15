package com.indodaxbot.strategy

import org.junit.Assert.assertEquals
import org.junit.Test

class NormalizerTest {
    private fun p(i: Int, macd: Double, signal: Double) = MacdPoint(i.toLong(), macd, signal, macd - signal)

    @Test
    fun scalesToZeroHundredOverRollingWindow() {
        val pts = listOf(p(0, 0.0, 5.0), p(1, 10.0, 5.0), p(2, 20.0, 5.0), p(3, 30.0, 5.0))
        val n = Normalizer.normalize(pts, 3)
        assertEquals(2, n.size)
        // window 0..2: lowest 0, highest 20
        assertEquals(100.0, n[0].macdNorm, 1e-9)
        assertEquals(25.0, n[0].signalNorm, 1e-9)
        // window 1..3: lowest 5 (signal), highest 30
        assertEquals(100.0, n[1].macdNorm, 1e-9)
        assertEquals(0.0, n[1].signalNorm, 1e-9)
    }

    @Test
    fun zeroRange_givesFifty() {
        val pts = List(5) { p(it, 3.0, 3.0) }
        val n = Normalizer.normalize(pts, 3)
        assertEquals(3, n.size)
        n.forEach {
            assertEquals(50.0, it.macdNorm, 0.0)
            assertEquals(50.0, it.signalNorm, 0.0)
        }
    }

    @Test
    fun tooFewPoints_givesEmpty() {
        assertEquals(0, Normalizer.normalize(listOf(p(0, 1.0, 2.0)), 3).size)
    }

    @Test
    fun valuesStayInsideZeroHundred() {
        val pts = List(80) { p(it, Math.sin(it / 5.0) * 1e6, Math.cos(it / 7.0) * 1e6) }
        Normalizer.normalize(pts, 20).forEach {
            assert(it.macdNorm in 0.0..100.0 && it.signalNorm in 0.0..100.0)
        }
    }
}
