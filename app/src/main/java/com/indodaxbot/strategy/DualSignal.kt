package com.indodaxbot.strategy

import kotlin.math.abs
import kotlin.math.atan

data class CrossResult(val type: SignalType, val angleDeg: Double)

/** Strategy 1 (reversal zone) and strategy 2 (crossover angle) on the normalized 0-100 series. */
object DualSignal {

    /** S0 = point i, S1 = i-1, S2 = i-2 (signal_norm). */
    fun reversalAt(n: List<NormPoint>, i: Int, cfg: StrategyConfig): SignalType {
        if (i < 2 || i >= n.size) return SignalType.HOLD
        val s2 = n[i - 2].signalNorm
        val s1 = n[i - 1].signalNorm
        val s0 = n[i].signalNorm
        fun inBuy(v: Double) = v >= cfg.buyZoneMin && v <= cfg.buyZoneMax
        fun inSell(v: Double) = v >= cfg.sellZoneMin && v <= cfg.sellZoneMax
        val buy = (inBuy(s1) || inBuy(s0)) && s1 <= s2 && (s0 - s1) > cfg.buyReversalThreshold
        val sell = when (cfg.sellTrigger) {
            // Fires once, on the candle where the line first reaches the sell zone from below.
            SellTrigger.ONCE_TOUCHING -> s1 < cfg.sellZoneMin && s0 >= cfg.sellZoneMin
            SellTrigger.WAIT_REVERSAL ->
                (inSell(s1) || inSell(s0)) && s1 >= s2 && (s1 - s0) > cfg.sellReversalThreshold
        }
        return when {
            buy && !sell -> SignalType.BUY
            sell && !buy -> SignalType.SELL
            else -> SignalType.HOLD
        }
    }

    fun angleDeg(m0: Double, m1: Double, s0: Double, s1: Double): Double =
        abs(atan(m0 - m1) - atan(s0 - s1)) * 180.0 / Math.PI

    fun crossoverAt(n: List<NormPoint>, i: Int, cfg: StrategyConfig): CrossResult {
        if (i < 1 || i >= n.size) return CrossResult(SignalType.HOLD, 0.0)
        val m0 = n[i].macdNorm
        val m1 = n[i - 1].macdNorm
        val s0 = n[i].signalNorm
        val s1 = n[i - 1].signalNorm
        val angle = angleDeg(m0, m1, s0, s1)
        val type = when {
            m1 <= s1 && m0 > s0 && angle >= cfg.minCrossoverAngle -> SignalType.BUY
            m1 >= s1 && m0 < s0 && angle >= cfg.minCrossoverAngle -> SignalType.SELL
            else -> SignalType.HOLD
        }
        return CrossResult(type, angle)
    }

    /**
     * [rev] / [cross] are null when that strategy is switched off. With one strategy off only the
     * other counts. AND: both must agree. OR: either is enough; if they disagree (BUY vs SELL) -> HOLD.
     */
    fun combine(rev: SignalType?, cross: SignalType?, mode: LogicMode): SignalType {
        if (rev == null && cross == null) return SignalType.HOLD
        if (rev == null) return cross!!
        if (cross == null) return rev
        return when (mode) {
            LogicMode.AND -> if (rev == cross) rev else SignalType.HOLD
            LogicMode.OR -> when {
                rev == SignalType.HOLD -> cross
                cross == SignalType.HOLD -> rev
                rev == cross -> rev
                else -> SignalType.HOLD
            }
        }
    }

    fun detectAt(n: List<NormPoint>, i: Int, cfg: StrategyConfig): SignalType = combine(
        if (cfg.enableReversal) reversalAt(n, i, cfg) else null,
        if (cfg.enableCrossover) crossoverAt(n, i, cfg).type else null,
        cfg.logicMode
    )

    fun detect(n: List<NormPoint>, cfg: StrategyConfig): SignalType =
        if (n.isEmpty()) SignalType.HOLD else detectAt(n, n.size - 1, cfg)

    /** Strategy 1 BUY/SELL points (index into [n]); empty when strategy 1 is off. */
    fun reversalMarkers(n: List<NormPoint>, cfg: StrategyConfig): List<Pair<Int, SignalType>> {
        if (!cfg.enableReversal) return emptyList()
        val out = ArrayList<Pair<Int, SignalType>>()
        for (i in 2 until n.size) {
            val t = reversalAt(n, i, cfg)
            if (t != SignalType.HOLD) out.add(i to t)
        }
        return out
    }

    /** Strategy 2 BUY/SELL points (index into [n]); empty when strategy 2 is off. */
    fun crossoverMarkers(n: List<NormPoint>, cfg: StrategyConfig): List<Pair<Int, SignalType>> {
        if (!cfg.enableCrossover) return emptyList()
        val out = ArrayList<Pair<Int, SignalType>>()
        for (i in 1 until n.size) {
            val t = crossoverAt(n, i, cfg).type
            if (t != SignalType.HOLD) out.add(i to t)
        }
        return out
    }

    /** Historical combined BUY/SELL points (index into [n]), following the AND/OR rule. */
    fun markers(n: List<NormPoint>, cfg: StrategyConfig): List<Pair<Int, SignalType>> {
        val out = ArrayList<Pair<Int, SignalType>>()
        for (i in 1 until n.size) {
            val t = detectAt(n, i, cfg)
            if (t != SignalType.HOLD) out.add(i to t)
        }
        return out
    }
}
