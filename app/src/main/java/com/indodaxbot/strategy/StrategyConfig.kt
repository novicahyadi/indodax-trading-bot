package com.indodaxbot.strategy

object Timeframes {
    /** Timeframe codes accepted by Indodax's history endpoint. */
    val all = listOf("1", "5", "15", "30", "60", "240", "1D", "3D", "1W")

    fun label(tf: String): String = when (tf) {
        "1" -> "1m"
        "5" -> "5m"
        "15" -> "15m"
        "30" -> "30m"
        "60" -> "1h"
        "240" -> "4h"
        else -> tf
    }
}

/** How the two strategies are combined when both are switched on. */
enum class LogicMode { AND, OR }

/**
 * How the SELL side of the reversal strategy fires.
 * ONCE_TOUCHING: on the candle where the signal line first reaches the sell zone.
 * WAIT_REVERSAL: only after the line has reversed downward inside the zone (needs a threshold).
 */
enum class SellTrigger { ONCE_TOUCHING, WAIT_REVERSAL }

enum class ExecutionMode { MARKET_MAKER, MARKET_TAKER }

/**
 * Everything the signal engine needs. The indicator is normalized to 0-100, so the zones are
 * percentages of the recent MACD range and work for any coin.
 */
data class StrategyConfig(
    val timeframe: String = "15",
    val macd: MacdParams = MacdParams(),
    val normLookback: Int = 50,
    val buyZoneMin: Double = 0.0,
    val buyZoneMax: Double = 20.0,
    val sellZoneMin: Double = 80.0,
    val sellZoneMax: Double = 100.0,
    val enableReversal: Boolean = true,
    val buyReversalThreshold: Double = 0.0,
    val sellTrigger: SellTrigger = SellTrigger.WAIT_REVERSAL,
    val sellReversalThreshold: Double = 0.0,
    val enableCrossover: Boolean = true,
    val minCrossoverAngle: Double = 15.0,
    val logicMode: LogicMode = LogicMode.OR
) {
    /** Identifies the configuration so the "last processed candle" is tracked per configuration. */
    fun key(): String =
        "$timeframe|${macd.fast}-${macd.slow}-${macd.signal}|$normLookback|" +
            "$buyZoneMin-$buyZoneMax|$sellZoneMin-$sellZoneMax|" +
            "$enableReversal:$buyReversalThreshold:$sellTrigger:$sellReversalThreshold|$enableCrossover:$minCrossoverAngle|$logicMode"

    val anyEnabled: Boolean get() = enableReversal || enableCrossover

    /** Candles needed before the indicator is trusted: macd_slow + macd_signal + norm_lookback. */
    val requiredCandles: Int get() = macd.slow + macd.signal + normLookback
}

/** Order execution and risk settings. Stored now; used by the trading engine in a later phase. */
data class ExecutionConfig(
    val mode: ExecutionMode = ExecutionMode.MARKET_MAKER,
    val makerChaseCancelPct: Double = 5.0,
    val makerStopLossPct: Double = 5.0,
    val makerTakeProfitPct: Double = 10.0,
    val slLevel1: Int = 30,
    val slLevel2: Int = 50,
    val slLevel3: Int = 80,
    val takerUseSlTp: Boolean = false,
    val takerStopLossPct: Double = 5.0,
    val takerTakeProfitPct: Double = 10.0,
    /** IDR spent per BUY (both modes). */
    val orderSizeIdr: Long = 1_000_000L,
    /**
     * Taker only, mutually exclusive with [takerUseSlTp]. A SELL signal is only obeyed when the position's
     * net P/L (after fees, as shown on the Positions screen) is at least +[takerMinTakeProfitPct] or at
     * most -[takerMaxStopLossPct]; in between, the SELL signal is ignored and the position is held.
     */
    val takerUseMinMax: Boolean = false,
    val takerMinTakeProfitPct: Double = 3.0,
    val takerMaxStopLossPct: Double = 2.0
)

object StrategyValidator {
    /** Returns an error message, or null when the values are acceptable. */
    @Suppress("LongParameterList")
    fun validate(
        fast: Int?, slow: Int?, signal: Int?, lookback: Int?,
        buyMin: Double?, buyMax: Double?, sellMin: Double?, sellMax: Double?,
        buyThreshold: Double?, sellThreshold: Double?, sellTrigger: SellTrigger, minAngle: Double?,
        enableReversal: Boolean, enableCrossover: Boolean
    ): String? {
        if (fast == null || fast !in 2..99) return "Fast EMA must be a whole number from 2 to 99"
        if (slow == null || slow !in 3..100) return "Slow EMA must be a whole number from 3 to 100"
        if (fast >= slow) return "Fast EMA must be smaller than Slow EMA"
        if (signal == null || signal !in 2..50) return "Signal period must be a whole number from 2 to 50"
        if (lookback == null || lookback !in 10..500) return "Normalization lookback must be from 10 to 500"
        // Zone and threshold values only matter (and are only checked) while strategy 1 is on.
        if (enableReversal) {
            if (buyMin == null || buyMax == null || sellMin == null || sellMax == null) {
                return "Fill all four zone values with valid numbers"
            }
            if (listOf(buyMin, buyMax, sellMin, sellMax).any { !it.isFinite() || it < 0.0 || it > 100.0 }) {
                return "Zone values must be between 0 and 100"
            }
            if (buyMin >= buyMax) return "Buy zone: minimum must be lower than maximum"
            if (sellMin >= sellMax) return "Sell zone: minimum must be lower than maximum"
            if (buyMax >= sellMin) return "Buy zone must stay below the sell zone"
            if (buyThreshold == null || !buyThreshold.isFinite() || buyThreshold < 0.0 || buyThreshold > 100.0) {
                return "Buy reversal threshold must be from 0 to 100"
            }
            if (sellTrigger == SellTrigger.WAIT_REVERSAL &&
                (sellThreshold == null || !sellThreshold.isFinite() || sellThreshold < 0.0 || sellThreshold > 100.0)
            ) {
                return "Sell reversal threshold must be from 0 to 100"
            }
        }
        if (enableCrossover) {
            if (minAngle == null || !minAngle.isFinite() || minAngle < 0.0 || minAngle > 90.0) {
                return "Minimum crossover angle must be from 0 to 90 degrees"
            }
        }
        // Both strategies off is allowed: the Dashboard then shows the indicator only (status OFF).
        return null
    }

    /** Fee values in percent (0.51 = 0.51 %). Each must be from 0 to 5. */
    fun validateFees(makerBuy: Double?, makerSell: Double?, takerBuy: Double?, takerSell: Double?): String? {
        val all = listOf(makerBuy, makerSell, takerBuy, takerSell)
        if (all.any { it == null || !it.isFinite() || it < 0.0 || it > 5.0 }) {
            return "Fees must be numbers from 0 to 5 (%)"
        }
        return null
    }

    @Suppress("LongParameterList")
    fun validateExecution(
        chase: Double?, makerSl: Double?, makerTp: Double?,
        l1: Int?, l2: Int?, l3: Int?,
        takerUseSlTp: Boolean, takerSl: Double?, takerTp: Double?,
        orderSizeIdr: Long? = 1_000_000L,
        takerUseMinMax: Boolean = false, takerMinTp: Double? = 3.0, takerMaxSl: Double? = 2.0
    ): String? {
        fun badPct(v: Double?, max: Double) = v == null || !v.isFinite() || v <= 0.0 || v > max
        if (orderSizeIdr == null || orderSizeIdr < 50_000L || orderSizeIdr > 1_000_000_000L) {
            return "Order size must be from Rp50,000 to Rp1,000,000,000"
        }
        if (badPct(chase, 100.0)) return "Chase-cancel must be above 0 and at most 100 %"
        if (badPct(makerSl, 99.0)) return "Maker stop loss must be above 0 and below 100 %"
        if (badPct(makerTp, 1000.0)) return "Maker take profit must be above 0 %"
        if (l1 == null || l2 == null || l3 == null) return "Fill all three stop-loss levels"
        if (l1 < 1 || l1 >= l2 || l2 >= l3 || l3 >= 100) {
            return "Stop-loss levels must increase and stay below 100 (level 4 is 100 % automatically)"
        }
        if (takerUseSlTp && takerUseMinMax) {
            return "Use stop loss and take profit and Minimum TP / Maximum SL cannot be on together"
        }
        if (takerUseMinMax) {
            if (badPct(takerMinTp, 1000.0)) return "Minimum take profit must be above 0 %"
            if (badPct(takerMaxSl, 99.0)) return "Maximum stop loss must be above 0 and below 100 %"
        }
        if (takerUseSlTp) {
            if (badPct(takerSl, 99.0)) return "Taker stop loss must be above 0 and below 100 %"
            if (badPct(takerTp, 1000.0)) return "Taker take profit must be above 0 %"
        }
        return null
    }
}
