package com.indodaxbot.strategy

import com.indodaxbot.api.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class SignalEvaluatorTest {
    private val tf = 900L
    private val t0 = 1_700_000_000L

    private fun candle(i: Int, close: Double) =
        Candle(t0 + i * tf, close, close, close, close, 1.0)

    private val wave = List(300) { candle(it, 100.0 + 10.0 * sin(it / 8.0)) }

    /** Index in [wave] whose candle produces the given crossover (after warm-up). */
    private fun crossIndex(type: SignalType): Int {
        val pts = Macd.compute(wave)
        val j = (1 until pts.size).first {
            Crossover.detect(pts[it - 1], pts[it]) == type &&
                wave.indexOfFirst { c -> c.timeSec == pts[it].timeSec } + 1 >= MacdParams().warmupCandles
        }
        return wave.indexOfFirst { it.timeSec == pts[j].timeSec }
    }

    private fun nowRightAfterClose(c: Candle) = c.timeSec + tf + 30

    @Test
    fun buyCrossover_isActionableWhenNewAndFresh() {
        val k = crossIndex(SignalType.BUY)
        val closed = wave.subList(0, k + 1)
        val e = SignalEvaluator.evaluate(closed, tf, nowRightAfterClose(closed.last()), null)
        assertEquals(EvalStatus.OK, e.status)
        assertEquals(SignalType.BUY, e.type)
        assertTrue(e.isNewCandle)
        assertTrue(e.actionable)
    }

    @Test
    fun sellCrossover_isDetected() {
        val k = crossIndex(SignalType.SELL)
        val closed = wave.subList(0, k + 1)
        val e = SignalEvaluator.evaluate(closed, tf, nowRightAfterClose(closed.last()), null)
        assertEquals(SignalType.SELL, e.type)
        assertTrue(e.actionable)
    }

    @Test
    fun sameCandleTwice_isNotActionableSecondTime() {
        val k = crossIndex(SignalType.BUY)
        val closed = wave.subList(0, k + 1)
        val now = nowRightAfterClose(closed.last())
        val first = SignalEvaluator.evaluate(closed, tf, now, null)
        val second = SignalEvaluator.evaluate(closed, tf, now + 5, first.latest!!.timeSec)
        assertTrue(first.actionable)
        assertFalse(second.isNewCandle)
        assertFalse(second.actionable)
        assertEquals(SignalType.BUY, second.type) // still displayed, but never traded again
    }

    @Test
    fun staleSignal_isShownButNotActionable() {
        val k = crossIndex(SignalType.BUY)
        val closed = wave.subList(0, k + 1)
        val now = closed.last().timeSec + tf + 3 * tf // app was closed for 3 candles
        val e = SignalEvaluator.evaluate(closed, tf, now, null)
        assertEquals(SignalType.BUY, e.type)
        assertTrue(e.isNewCandle)
        assertFalse(e.actionable)
        assertTrue(e.note!!.contains("stale"))
    }

    @Test
    fun tooFewCandles_isInsufficient() {
        val closed = wave.subList(0, 50)
        val e = SignalEvaluator.evaluate(closed, tf, nowRightAfterClose(closed.last()), null)
        assertEquals(EvalStatus.INSUFFICIENT_DATA, e.status)
        assertFalse(e.actionable)
        assertNull(e.latest)
    }

    @Test
    fun gapInRecentCandles_blocksSignals() {
        val k = crossIndex(SignalType.BUY)
        val closed = wave.subList(0, k + 1).toMutableList()
        closed.removeAt(closed.size - 5) // a missing candle near the end
        val e = SignalEvaluator.evaluate(closed, tf, nowRightAfterClose(closed.last()), null)
        assertEquals(EvalStatus.MISSING_CANDLES, e.status)
        assertFalse(e.actionable)
    }

    @Test
    fun invalidPrice_isRejected() {
        val closed = wave.subList(0, 120).toMutableList()
        closed[100] = closed[100].copy(close = 0.0)
        val e = SignalEvaluator.evaluate(closed, tf, nowRightAfterClose(closed.last()), null)
        assertEquals(EvalStatus.INVALID_DATA, e.status)
    }

    @Test
    fun holdIsNeverActionable() {
        // Find a candle with no crossover.
        val pts = Macd.compute(wave)
        val j = (1 until pts.size).first {
            Crossover.detect(pts[it - 1], pts[it]) == SignalType.HOLD &&
                wave.indexOfFirst { c -> c.timeSec == pts[it].timeSec } + 1 >= MacdParams().warmupCandles
        }
        val k = wave.indexOfFirst { it.timeSec == pts[j].timeSec }
        val closed = wave.subList(0, k + 1)
        val e = SignalEvaluator.evaluate(closed, tf, nowRightAfterClose(closed.last()), null)
        assertEquals(SignalType.HOLD, e.type)
        assertFalse(e.actionable)
    }
}
