package com.indodaxbot.strategy

enum class StrategyMode {
    /** Signal line reverses inside the BUY zone (buy) or the SELL zone (sell). */
    ZONE_REVERSAL,
    /** Original rule: MACD crosses the signal line. */
    CROSSOVER
}

object Timeframes {
    /** Timeframe codes accepted by Indodax's history endpoint. */
    val all = listOf("1", "15", "30", "60", "240", "1D", "3D", "1W")

    fun label(tf: String): String = when (tf) {
        "1" -> "1m"
        "15" -> "15m"
        "30" -> "30m"
        "60" -> "1h"
        "240" -> "4h"
        else -> tf
    }
}

/**
 * Everything the signal engine needs. Zones are expressed in IDR in the same units as the MACD and
 * signal values shown on screen. They depend on the timeframe (a 1D MACD swings far wider than a
 * 15m one), so they are stored per timeframe and are null until the user sets them.
 */
data class StrategyConfig(
    val timeframe: String = "15",
    val macd: MacdParams = MacdParams(),
    val mode: StrategyMode = StrategyMode.ZONE_REVERSAL,
    val buyZone: Double? = null,    // signal line at or below this value = inside the BUY zone
    val sellZone: Double? = null    // signal line at or above this value = inside the SELL zone
) {
    /** Identifies the configuration so the "last processed candle" is tracked per configuration. */
    fun key(): String = "$timeframe|${macd.fast}-${macd.slow}-${macd.signal}|$mode|$buyZone|$sellZone"

    val zonesSet: Boolean get() = buyZone != null && sellZone != null
}

object Strategy {

    /**
     * Signal for the point at [i] (the latest closed candle when i = last index).
     *
     * ZONE_REVERSAL
     *  BUY : the previous signal-line value was a low (<= the one before it) inside the buy zone
     *        (<= buyZone) and the signal line now turns up.
     *  SELL: the previous signal-line value was a high (>= the one before it) inside the sell zone
     *        (>= sellZone) and the signal line now turns down.
     * CROSSOVER: see [Crossover].
     */
    fun detectAt(points: List<MacdPoint>, i: Int, cfg: StrategyConfig): SignalType {
        return when (cfg.mode) {
            StrategyMode.CROSSOVER ->
                if (i >= 1) Crossover.detect(points[i - 1], points[i]) else SignalType.HOLD
            StrategyMode.ZONE_REVERSAL -> {
                val bz = cfg.buyZone
                val sz = cfg.sellZone
                if (i < 2 || bz == null || sz == null) return SignalType.HOLD
                val s2 = points[i - 2].signal
                val s1 = points[i - 1].signal
                val s0 = points[i].signal
                when {
                    s1 <= bz && s1 <= s2 && s0 > s1 -> SignalType.BUY
                    s1 >= sz && s1 >= s2 && s0 < s1 -> SignalType.SELL
                    else -> SignalType.HOLD
                }
            }
        }
    }

    fun detect(points: List<MacdPoint>, cfg: StrategyConfig): SignalType =
        if (points.isEmpty()) SignalType.HOLD else detectAt(points, points.size - 1, cfg)

    /** Historical BUY/SELL points (index into [points]) for drawing on the chart. */
    fun markers(points: List<MacdPoint>, cfg: StrategyConfig): List<Pair<Int, SignalType>> {
        val out = ArrayList<Pair<Int, SignalType>>()
        for (i in 1 until points.size) {
            val t = detectAt(points, i, cfg)
            if (t != SignalType.HOLD) out.add(i to t)
        }
        return out
    }
}

object StrategyValidator {
    /** Returns an error message, or null when the values are acceptable. */
    fun validate(
        fast: Int?, slow: Int?, signal: Int?,
        buyZone: Double?, sellZone: Double?, zonesText: Boolean
    ): String? {
        if (fast == null || fast !in 2..99) return "Fast EMA must be a whole number from 2 to 99"
        if (slow == null || slow !in 3..100) return "Slow EMA must be a whole number from 3 to 100"
        if (fast >= slow) return "Fast EMA must be smaller than Slow EMA"
        if (signal == null || signal !in 2..50) return "Signal period must be a whole number from 2 to 50"
        if (zonesText) {
            if (buyZone == null || sellZone == null) return "Fill both zones with valid numbers, or leave both empty"
            if (!buyZone.isFinite() || !sellZone.isFinite()) return "Zones must be valid numbers"
            if (buyZone >= sellZone) return "Buy zone must be lower than the sell zone"
        }
        return null
    }
}
