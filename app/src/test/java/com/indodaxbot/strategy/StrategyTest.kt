package com.indodaxbot.strategy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyTest {
    private val cfg = StrategyConfig()

    private fun sigSeries(vararg s: Double) = s.mapIndexed { i, v -> NormPoint(i.toLong(), 50.0, v) }
    private fun pair(m1: Double, s1: Double, m0: Double, s0: Double) =
        listOf(NormPoint(0, m1, s1), NormPoint(1, m0, s0))

    // ---- Reversal ----
    @Test fun reversal_buyInZone() =
        assertEquals(SignalType.BUY, DualSignal.reversalAt(sigSeries(10.0, 5.0, 8.0), 2, cfg))

    @Test fun reversal_noBuyOutsideZone() =
        assertEquals(SignalType.HOLD, DualSignal.reversalAt(sigSeries(50.0, 45.0, 48.0), 2, cfg))

    @Test fun reversal_thresholdIsStrict() {
        val c = cfg.copy(reversalThreshold = 3.0)
        assertEquals(SignalType.HOLD, DualSignal.reversalAt(sigSeries(10.0, 5.0, 8.0), 2, c))
        assertEquals(SignalType.BUY, DualSignal.reversalAt(sigSeries(10.0, 5.0, 8.1), 2, c))
    }

    @Test fun reversal_sellInZone() =
        assertEquals(SignalType.SELL, DualSignal.reversalAt(sigSeries(90.0, 95.0, 92.0), 2, cfg))

    @Test fun reversal_stillFallingInZone_isHold() =
        assertEquals(SignalType.HOLD, DualSignal.reversalAt(sigSeries(30.0, 25.0, 15.0), 2, cfg))

    @Test fun reversal_currentValueInZoneIsEnough() {
        val c = cfg.copy(buyZoneMin = 5.0)
        // S1 = 3 is below the zone, S0 = 6 is inside it
        assertEquals(SignalType.BUY, DualSignal.reversalAt(sigSeries(10.0, 3.0, 6.0), 2, c))
    }

    @Test fun reversal_needsThreePoints() =
        assertEquals(SignalType.HOLD, DualSignal.reversalAt(sigSeries(5.0, 8.0), 1, cfg))

    // ---- Crossover angle ----
    @Test fun angle_matchesFormula() {
        assertEquals(23.7026, DualSignal.angleDeg(60.0, 40.0, 44.0, 42.0), 1e-3)
        assertEquals(1.4144, DualSignal.angleDeg(50.0, 40.0, 49.0, 41.0), 1e-3)
    }

    @Test fun crossover_buyWhenAngleLargeEnough() {
        val r = DualSignal.crossoverAt(pair(40.0, 42.0, 60.0, 44.0), 1, cfg)
        assertEquals(SignalType.BUY, r.type)
        assertEquals(23.7026, r.angleDeg, 1e-3)
    }

    @Test fun crossover_shallowAngleIsRejected() =
        assertEquals(SignalType.HOLD, DualSignal.crossoverAt(pair(40.0, 41.0, 50.0, 49.0), 1, cfg).type)

    @Test fun crossover_sell() =
        assertEquals(SignalType.SELL, DualSignal.crossoverAt(pair(60.0, 58.0, 40.0, 56.0), 1, cfg).type)

    @Test fun crossover_noCrossIsHold() =
        assertEquals(SignalType.HOLD, DualSignal.crossoverAt(pair(40.0, 42.0, 41.0, 43.0), 1, cfg).type)

    // ---- Combination ----
    @Test fun combine_and() {
        val b = SignalType.BUY; val h = SignalType.HOLD; val s = SignalType.SELL
        assertEquals(b, DualSignal.combine(b, b, LogicMode.AND))
        assertEquals(h, DualSignal.combine(b, h, LogicMode.AND))
        assertEquals(h, DualSignal.combine(b, s, LogicMode.AND))
    }

    @Test fun combine_or() {
        val b = SignalType.BUY; val h = SignalType.HOLD; val s = SignalType.SELL
        assertEquals(b, DualSignal.combine(b, h, LogicMode.OR))
        assertEquals(s, DualSignal.combine(h, s, LogicMode.OR))
        assertEquals(h, DualSignal.combine(b, s, LogicMode.OR))   // conflict -> no trade
    }

    @Test fun combine_offStrategyUsesTheActiveOneOnly() {
        assertEquals(SignalType.BUY, DualSignal.combine(null, SignalType.BUY, LogicMode.AND))
        assertEquals(SignalType.SELL, DualSignal.combine(SignalType.SELL, null, LogicMode.AND))
        assertEquals(SignalType.HOLD, DualSignal.combine(null, null, LogicMode.OR))
    }

    @Test fun detect_respectsToggles() {
        // A series where reversal says BUY at the last point but there is no crossover.
        val n = listOf(NormPoint(0, 60.0, 10.0), NormPoint(1, 60.0, 5.0), NormPoint(2, 60.0, 8.0))
        assertEquals(SignalType.BUY, DualSignal.detect(n, cfg.copy(enableCrossover = false)))
        assertEquals(SignalType.HOLD, DualSignal.detect(n, cfg.copy(enableReversal = false)))
        assertEquals(SignalType.HOLD, DualSignal.detect(n, cfg.copy(logicMode = LogicMode.AND)))
        assertEquals(SignalType.BUY, DualSignal.detect(n, cfg.copy(logicMode = LogicMode.OR)))
    }

    // ---- Config and validation ----
    @Test fun defaults_matchTheSpec() {
        assertEquals(50, cfg.normLookback)
        assertEquals(0.0, cfg.buyZoneMin, 0.0); assertEquals(20.0, cfg.buyZoneMax, 0.0)
        assertEquals(80.0, cfg.sellZoneMin, 0.0); assertEquals(100.0, cfg.sellZoneMax, 0.0)
        assertEquals(15.0, cfg.minCrossoverAngle, 0.0)
        assertEquals(26 + 9 + 50, cfg.requiredCandles)
        assertEquals(ExecutionMode.MARKET_MAKER, ExecutionConfig().mode)
        assertEquals(false, ExecutionConfig().takerUseSlTp)
    }

    @Test fun key_changesWithSettings_butNotWithExecution() {
        assertNotEquals(cfg.key(), cfg.copy(timeframe = "60").key())
        assertNotEquals(cfg.key(), cfg.copy(sellZoneMin = 75.0).key())
        assertNotEquals(cfg.key(), cfg.copy(logicMode = LogicMode.AND).key())
        assertNotEquals(cfg.key(), cfg.copy(normLookback = 40).key())
    }

    private fun v(
        fast: Int? = 12, slow: Int? = 26, sig: Int? = 9, lb: Int? = 50,
        bMin: Double? = 0.0, bMax: Double? = 20.0, sMin: Double? = 80.0, sMax: Double? = 100.0,
        thr: Double? = 0.0, ang: Double? = 15.0, rev: Boolean = true, cross: Boolean = true
    ) = StrategyValidator.validate(fast, slow, sig, lb, bMin, bMax, sMin, sMax, thr, ang, rev, cross)

    @Test fun validator_acceptsDefaults() = assertNull(v())

    @Test fun validator_rejectsBadValues() {
        assertNotNull(v(fast = 26, slow = 12))
        assertNotNull(v(fast = null))
        assertNotNull(v(lb = 5))
        assertNotNull(v(bMax = 120.0))
        assertNotNull(v(bMin = 30.0, bMax = 20.0))
        assertNotNull(v(bMax = 85.0))                // overlaps the sell zone
        assertNotNull(v(sMin = null))
        assertNotNull(v(thr = -1.0))
        assertNotNull(v(ang = 91.0))
        assertNotNull(v(ang = Double.NaN))
        assertNull(v(rev = false, cross = false))        // both OFF is allowed
        assertNull(v(bMax = 85.0, thr = null, rev = false))   // zones ignored while strategy 1 is OFF
        assertNull(v(ang = 91.0, cross = false))              // angle ignored while strategy 2 is OFF
    }

    private fun ve(
        chase: Double? = 5.0, sl: Double? = 5.0, tp: Double? = 10.0,
        l1: Int? = 30, l2: Int? = 50, l3: Int? = 80,
        use: Boolean = false, tsl: Double? = 5.0, ttp: Double? = 10.0
    ) = StrategyValidator.validateExecution(chase, sl, tp, l1, l2, l3, use, tsl, ttp)

    @Test fun executionValidator() {
        assertNull(ve())
        assertNotNull(ve(chase = 0.0))
        assertNotNull(ve(sl = 100.0))
        assertNotNull(ve(l1 = 50, l2 = 50))
        assertNotNull(ve(l3 = 100))
        assertNotNull(ve(l1 = null))
        assertNull(ve(use = false, tsl = null, ttp = null))     // taker values ignored while OFF
        assertNotNull(ve(use = true, tsl = null))
        assertNotNull(ve(use = true, ttp = 0.0))
    }

    @Test fun markers_followToggles() {
        val n = listOf(NormPoint(0, 60.0, 10.0), NormPoint(1, 60.0, 5.0), NormPoint(2, 60.0, 8.0))
        assertEquals(listOf(2 to SignalType.BUY), DualSignal.reversalMarkers(n, cfg))
        assertTrue(DualSignal.reversalMarkers(n, cfg.copy(enableReversal = false)).isEmpty())
        assertTrue(DualSignal.crossoverMarkers(n, cfg.copy(enableCrossover = false)).isEmpty())
    }

    @Test fun timeframes_labels() {
        assertEquals("5m", Timeframes.label("5"))
        assertEquals("1h", Timeframes.label("60"))
        assertTrue("1D" in Timeframes.all)
    }
}
