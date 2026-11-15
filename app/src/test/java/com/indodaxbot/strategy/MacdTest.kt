package com.indodaxbot.strategy

import com.indodaxbot.api.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class MacdTest {
    private fun candle(i: Int, close: Double, tf: Long = 900) =
        Candle(1_700_000_000L + i * tf, close, close, close, close, 1.0)

    private fun wave(n: Int) = List(n) { candle(it, 100.0 + 10.0 * sin(it / 8.0)) }

    @Test
    fun ema_knownValues() {
        val e = Ema.series(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0), 3)
        assertTrue(e[0].isNaN() && e[1].isNaN())
        assertEquals(2.0, e[2], 1e-12) // seed = simple average
        assertEquals(3.0, e[3], 1e-12)
        assertEquals(4.0, e[4], 1e-12)
    }

    @Test
    fun ema_tooFewValuesIsAllNaN() {
        assertTrue(Ema.series(doubleArrayOf(1.0, 2.0), 3).all { it.isNaN() })
    }

    @Test
    fun macd_constantPriceIsZero() {
        val pts = Macd.compute(List(100) { candle(it, 50.0) })
        assertTrue(pts.isNotEmpty())
        assertTrue(pts.all { Math.abs(it.macd) < 1e-9 && Math.abs(it.histogram) < 1e-9 })
    }

    @Test
    fun macd_pointCountAndHistogramIdentity() {
        val c = wave(120)
        val p = MacdParams()
        val pts = Macd.compute(c, p)
        assertEquals(c.size - p.minCandles + 1, pts.size)
        assertEquals(c.last().timeSec, pts.last().timeSec)
        assertTrue(pts.all { Math.abs(it.histogram - (it.macd - it.signal)) < 1e-12 })
    }

    @Test
    fun macd_tooFewCandlesIsEmpty() {
        assertTrue(Macd.compute(wave(33)).isEmpty()) // needs 26 + 9 - 1 = 34
        assertEquals(1, Macd.compute(wave(34)).size)
    }

    @Test
    fun macd_risingPriceHasPositiveMacd() {
        val pts = Macd.compute(List(100) { candle(it, 100.0 + it) })
        assertTrue(pts.last().macd > 0)
    }

    private fun pt(m: Double, s: Double) = MacdPoint(0, m, s, m - s)

    @Test
    fun crossover_buy() {
        assertEquals(SignalType.BUY, Crossover.detect(pt(-1.0, 0.0), pt(1.0, 0.0)))
        assertEquals(SignalType.BUY, Crossover.detect(pt(0.0, 0.0), pt(0.1, 0.0))) // equal -> above counts
    }

    @Test
    fun crossover_sell() {
        assertEquals(SignalType.SELL, Crossover.detect(pt(1.0, 0.0), pt(-1.0, 0.0)))
        assertEquals(SignalType.SELL, Crossover.detect(pt(0.0, 0.0), pt(-0.1, 0.0)))
    }

    @Test
    fun crossover_holdWhenNoCross() {
        assertEquals(SignalType.HOLD, Crossover.detect(pt(1.0, 0.0), pt(2.0, 0.0)))
        assertEquals(SignalType.HOLD, Crossover.detect(pt(-1.0, 0.0), pt(-2.0, 0.0)))
        assertEquals(SignalType.HOLD, Crossover.detect(pt(1.0, 0.0), pt(0.0, 0.0))) // touches, not below
    }

    @Test
    fun wave_producesBothBuyAndSell() {
        val pts = Macd.compute(wave(300))
        val types = (1 until pts.size).map { Crossover.detect(pts[it - 1], pts[it]) }
        assertTrue(types.contains(SignalType.BUY))
        assertTrue(types.contains(SignalType.SELL))
    }
}
