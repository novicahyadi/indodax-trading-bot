package com.indodaxbot.strategy

import com.indodaxbot.api.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class SignalEvaluatorTest {
    private val tf = 900L
    private val t0 = 1_700_000_000L

    private fun candle(i: Int, close: Double) =
        Candle(t0 + i * tf, close, close + 1.0, close - 1.0, close, 1.0)

    private val wave = List(300) { candle(it, 100.0 + 10.0 * sin(it / 8.0)) }

    /** Reversal only, so the expected candles are deterministic. */
    private val cfg = StrategyConfig(enableCrossover = false)

    private fun nowRightAfterClose(c: Candle) = c.timeSec + tf + 30

    /** Index into [wave] of the first candle (after warm-up) where [cfg] gives [type]. */
    private fun firstIndex(type: SignalType): Int {
        val norm = Normalizer.normalize(Macd.compute(wave, cfg.macd), cfg.normLookback)
        val k = DualSignal.markers(norm, cfg).first { it.second == type }.first
        return wave.indexOfFirst { it.timeSec == norm[k].timeSec }
    }

    @Test
    fun firstMarkers_matchIndependentPythonCalculation() {
        assertEquals(94, firstIndex(SignalType.BUY))
        assertEquals(118, firstIndex(SignalType.SELL))
    }

    @Test
    fun buy_isActionableWhenNewAndFresh() {
        val closed = wave.subList(0, firstIndex(SignalType.BUY) + 1)
        val e = SignalEvaluator.evaluate(closed, tf, nowRightAfterClose(closed.last()), null, cfg)
        assertEquals(EvalStatus.OK, e.status)
        assertEquals(SignalType.BUY, e.type)
        assertEquals(SignalType.BUY, e.reversal)
        assertEquals(null, e.crossover)
        assertTrue(e.isNewCandle)
        assertTrue(e.actionable)
        assertEquals(closed.last(), e.signalCandle)
    }

    @Test
    fun sell_isDetected() {
        val closed = wave.subList(0, firstIndex(SignalType.SELL) + 1)
        val e = SignalEvaluator.evaluate(closed, tf, nowRightAfterClose(closed.last()), null, cfg)
        assertEquals(SignalType.SELL, e.type)
        assertTrue(e.actionable)
    }

    @Test
    fun sameCandleTwice_isNotActionableSecondTime() {
        val closed = wave.subList(0, firstIndex(SignalType.BUY) + 1)
        val now = nowRightAfterClose(closed.last())
        val first = SignalEvaluator.evaluate(closed, tf, now, null, cfg)
        val second = SignalEvaluator.evaluate(closed, tf, now + 5, first.latest!!.timeSec, cfg)
        assertTrue(first.actionable)
        assertFalse(second.isNewCandle)
        assertFalse(second.actionable)
        assertEquals(SignalType.BUY, second.type)
    }

    @Test
    fun staleSignal_isShownButNotActionable() {
        val closed = wave.subList(0, firstIndex(SignalType.BUY) + 1)
        val e = SignalEvaluator.evaluate(closed, tf, closed.last().timeSec + tf * 3, null, cfg)
        assertEquals(SignalType.BUY, e.type)
        assertFalse(e.actionable)
        assertNotNull(e.note)
    }

    @Test
    fun warmingUp_belowRequiredCandles() {
        val need = cfg.requiredCandles
        assertEquals(85, need)
        val few = wave.subList(0, need - 1)
        val e = SignalEvaluator.evaluate(few, tf, nowRightAfterClose(few.last()), null, cfg)
        assertEquals(EvalStatus.WARMING_UP, e.status)
        assertFalse(e.actionable)
        val enough = wave.subList(0, need)
        assertEquals(EvalStatus.OK, SignalEvaluator.evaluate(enough, tf, nowRightAfterClose(enough.last()), null, cfg).status)
    }

    @Test
    fun noStrategyEnabled_isNotConfigured() {
        val c = cfg.copy(enableReversal = false, enableCrossover = false)
        val e = SignalEvaluator.evaluate(wave.subList(0, 120), tf, t0 + 200 * tf, null, c)
        assertEquals(EvalStatus.NOT_CONFIGURED, e.status)
        assertTrue(e.norm.isNotEmpty())      // indicator still drawn
        assertFalse(e.actionable)
        assertEquals(SignalType.HOLD, e.type)
    }

    @Test
    fun gapInRecentCandles_blocksSignals() {
        val closed = wave.subList(0, 130).toMutableList()
        closed.removeAt(110)
        val e = SignalEvaluator.evaluate(closed, tf, nowRightAfterClose(closed.last()), null, cfg)
        assertEquals(EvalStatus.MISSING_CANDLES, e.status)
        assertFalse(e.actionable)
    }

    @Test
    fun invalidPrice_blocksSignals() {
        val closed = wave.subList(0, 130).toMutableList()
        closed[5] = candle(5, 0.0)
        val e = SignalEvaluator.evaluate(closed, tf, nowRightAfterClose(closed.last()), null, cfg)
        assertEquals(EvalStatus.INVALID_DATA, e.status)
    }

    @Test
    fun normalizedValuesAreWithinZeroHundred() {
        val e = SignalEvaluator.evaluate(wave, tf, nowRightAfterClose(wave.last()), null, cfg)
        assertEquals(EvalStatus.OK, e.status)
        assertTrue(e.norm.all { it.macdNorm in 0.0..100.0 && it.signalNorm in 0.0..100.0 })
        assertEquals(wave.last().timeSec, e.latest!!.timeSec)
    }
}
