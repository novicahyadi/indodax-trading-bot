package com.indodaxbot.strategy

import com.indodaxbot.api.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class StrategyTest {
    private val cfg = StrategyConfig(
        timeframe = "15", mode = StrategyMode.ZONE_REVERSAL, buyZone = -1.0, sellZone = 1.0
    )

    /** Points whose signal line follows [signals]; MACD itself is irrelevant to the zone rule. */
    private fun pts(vararg signals: Double) =
        signals.mapIndexed { i, s -> MacdPoint(i * 900L, s, s, 0.0) }

    @Test
    fun buy_whenSignalTurnsUpInsideBuyZone() {
        assertEquals(SignalType.BUY, Strategy.detect(pts(-0.8, -1.2, -1.0), cfg))
    }

    @Test
    fun buy_zoneBoundaryCountsAsTouching() {
        assertEquals(SignalType.BUY, Strategy.detect(pts(-0.5, -1.0, -0.9), cfg))
    }

    @Test
    fun buy_afterFlatBottom() {
        assertEquals(SignalType.BUY, Strategy.detect(pts(-1.2, -1.2, -1.0), cfg))
    }

    @Test
    fun noBuy_whenTroughOutsideZone() {
        assertEquals(SignalType.HOLD, Strategy.detect(pts(-0.2, -0.5, -0.3), cfg))
    }

    @Test
    fun noBuy_whileStillFalling() {
        assertEquals(SignalType.HOLD, Strategy.detect(pts(-0.8, -1.2, -1.5), cfg))
    }

    @Test
    fun noBuy_secondCandleAfterTurn() {
        // The turn was already confirmed one candle ago; this candle just continues up.
        assertEquals(SignalType.HOLD, Strategy.detect(pts(-1.2, -1.0, -0.8), cfg))
    }

    @Test
    fun sell_whenSignalTurnsDownInsideSellZone() {
        assertEquals(SignalType.SELL, Strategy.detect(pts(0.8, 1.2, 1.1), cfg))
        assertEquals(SignalType.SELL, Strategy.detect(pts(1.2, 1.2, 1.0), cfg))
    }

    @Test
    fun noSell_whenPeakOutsideZone_orStillRising() {
        assertEquals(SignalType.HOLD, Strategy.detect(pts(0.2, 0.5, 0.3), cfg))
        assertEquals(SignalType.HOLD, Strategy.detect(pts(0.8, 1.2, 1.5), cfg))
    }

    @Test
    fun zones_missingMeansHold() {
        val noZones = cfg.copy(buyZone = null, sellZone = null)
        assertEquals(SignalType.HOLD, Strategy.detect(pts(-0.8, -1.2, -1.0), noZones))
    }

    @Test
    fun crossoverMode_stillWorks() {
        val c = cfg.copy(mode = StrategyMode.CROSSOVER)
        val points = listOf(MacdPoint(0, -1.0, 0.0, -1.0), MacdPoint(900, 1.0, 0.0, 1.0))
        assertEquals(SignalType.BUY, Strategy.detect(points, c))
    }

    @Test
    fun markers_onlyAtValidReversals() {
        val points = pts(-0.5, -1.2, -1.0, -0.5, 0.5, 1.2, 1.0, 0.0)
        val m = Strategy.markers(points, cfg)
        assertEquals(listOf(2 to SignalType.BUY, 6 to SignalType.SELL), m)
    }

    // ----- integration with real MACD math on a synthetic wave (signal line swings about +-3.2) -----

    private val tf = 900L
    private val t0 = 1_700_000_000L
    private val wave = List(300) {
        val c = 100.0 + 10.0 * sin(it / 8.0)
        Candle(t0 + it * tf, c, c, c, c, 1.0)
    }

    @Test
    fun wave_withZonesNearExtremes_givesBuysAndSellsOnlyInsideZones() {
        val c = StrategyConfig("15", MacdParams(), StrategyMode.ZONE_REVERSAL, -1.6, 1.6)
        val points = Macd.compute(wave)
        val m = Strategy.markers(points, c)
        assertTrue(m.any { it.second == SignalType.BUY })
        assertTrue(m.any { it.second == SignalType.SELL })
        for ((i, t) in m) {
            val trough = points[i - 1].signal
            if (t == SignalType.BUY) assertTrue(trough <= -1.6) else assertTrue(trough >= 1.6)
        }
    }

    @Test
    fun wave_withZonesFarAway_givesNothing() {
        val c = StrategyConfig("15", MacdParams(), StrategyMode.ZONE_REVERSAL, -1000.0, 1000.0)
        assertTrue(Strategy.markers(Macd.compute(wave), c).isEmpty())
    }

    @Test
    fun evaluator_notConfiguredWithoutZones_butStillReturnsChartData() {
        val c = StrategyConfig("15", MacdParams(), StrategyMode.ZONE_REVERSAL, null, null)
        val now = wave.last().timeSec + tf + 30
        val e = SignalEvaluator.evaluate(wave, tf, now, null, c)
        assertEquals(EvalStatus.NOT_CONFIGURED, e.status)
        assertFalse(e.actionable)
        assertTrue(e.points.isNotEmpty())
        assertNotNull(e.latest)
    }

    @Test
    fun evaluator_zoneBuy_isActionableOnceThenNot() {
        val c = StrategyConfig("15", MacdParams(), StrategyMode.ZONE_REVERSAL, -1.6, 1.6)
        val points = Macd.compute(wave)
        val m = Strategy.markers(points, c).first {
            it.second == SignalType.BUY && wave.indexOfFirst { w -> w.timeSec == points[it.first].timeSec } + 1 >= MacdParams().warmupCandles
        }
        val k = wave.indexOfFirst { it.timeSec == points[m.first].timeSec }
        val closed = wave.subList(0, k + 1)
        val now = closed.last().timeSec + tf + 30
        val first = SignalEvaluator.evaluate(closed, tf, now, null, c)
        assertEquals(SignalType.BUY, first.type)
        assertTrue(first.actionable)
        val second = SignalEvaluator.evaluate(closed, tf, now + 5, first.latest!!.timeSec, c)
        assertFalse(second.actionable)
    }

    // ----- validation and config -----

    @Test
    fun validator_acceptsGoodValues() {
        assertNull(StrategyValidator.validate(12, 26, 9, -1.0, 1.0, true))
        assertNull(StrategyValidator.validate(5, 35, 5, null, null, false))
    }

    @Test
    fun validator_rejectsBadValues() {
        assertNotNull(StrategyValidator.validate(26, 12, 9, null, null, false))   // fast >= slow
        assertNotNull(StrategyValidator.validate(null, 26, 9, null, null, false))
        assertNotNull(StrategyValidator.validate(12, 26, 1, null, null, false))
        assertNotNull(StrategyValidator.validate(12, 500, 9, null, null, false))
        assertNotNull(StrategyValidator.validate(12, 26, 9, 1.0, -1.0, true))     // buy >= sell
        assertNotNull(StrategyValidator.validate(12, 26, 9, 1.0, null, true))     // only one zone
    }

    @Test
    fun configKey_changesWithAnySetting() {
        val a = StrategyConfig("15", MacdParams(), StrategyMode.ZONE_REVERSAL, -1.0, 1.0)
        assertTrue(a.key() != a.copy(timeframe = "60").key())
        assertTrue(a.key() != a.copy(macd = MacdParams(8, 21, 5)).key())
        assertTrue(a.key() != a.copy(buyZone = -2.0).key())
        assertTrue(a.key() != a.copy(mode = StrategyMode.CROSSOVER).key())
    }

    @Test
    fun timeframeLabels() {
        assertEquals("1h", Timeframes.label("60"))
        assertEquals("15m", Timeframes.label("15"))
        assertEquals("1D", Timeframes.label("1D"))
    }
}
