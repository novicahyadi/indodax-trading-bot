package com.indodaxbot.strategy

import com.indodaxbot.api.Candle

enum class EvalStatus { OK, INSUFFICIENT_DATA, MISSING_CANDLES, INVALID_DATA, NOT_CONFIGURED }

data class Evaluation(
    val status: EvalStatus,
    val type: SignalType = SignalType.HOLD,
    val latest: MacdPoint? = null,
    val latestClose: Double? = null,
    /** True if the latest closed candle has not been processed before. */
    val isNewCandle: Boolean = false,
    /** True only for a BUY/SELL on a NEW, FRESH, gap-free candle. Execution must check this. */
    val actionable: Boolean = false,
    val note: String? = null,
    /** All MACD points (oldest first), used for the chart. */
    val points: List<MacdPoint> = emptyList()
)

object SignalEvaluator {
    /** Candles that must be contiguous at the end of the series. */
    const val GAP_CHECK_CANDLES = 60

    /** Convenience overload: original MACD/signal-line crossover rule. */
    fun evaluate(
        closed: List<Candle>,
        tfSeconds: Long,
        nowSec: Long,
        lastProcessedSec: Long?,
        params: MacdParams = MacdParams()
    ): Evaluation = evaluate(
        closed, tfSeconds, nowSec, lastProcessedSec,
        StrategyConfig(timeframe = "", macd = params, mode = StrategyMode.CROSSOVER)
    )

    /**
     * [closed] must contain CLOSED candles only, oldest first (use Candles.closedOnly).
     * [lastProcessedSec] is the open time of the last candle already evaluated (persisted by the caller).
     */
    fun evaluate(
        closed: List<Candle>,
        tfSeconds: Long,
        nowSec: Long,
        lastProcessedSec: Long?,
        cfg: StrategyConfig
    ): Evaluation {
        val params = cfg.macd
        if (closed.any { !it.close.isFinite() || it.close <= 0.0 }) {
            return Evaluation(EvalStatus.INVALID_DATA, note = "Invalid candle data")
        }
        if (closed.size < params.warmupCandles) {
            return Evaluation(
                EvalStatus.INSUFFICIENT_DATA,
                note = "Need ${params.warmupCandles} candles, have ${closed.size}"
            )
        }
        if (hasGap(closed, tfSeconds, GAP_CHECK_CANDLES)) {
            return Evaluation(EvalStatus.MISSING_CANDLES, note = "Missing candles in recent data")
        }
        val points = Macd.compute(closed, params)
        if (points.size < 3) {
            return Evaluation(EvalStatus.INSUFFICIENT_DATA, note = "Not enough MACD points")
        }
        val latest = points.last()
        if (cfg.mode == StrategyMode.ZONE_REVERSAL && !cfg.zonesSet) {
            return Evaluation(
                status = EvalStatus.NOT_CONFIGURED,
                latest = latest,
                latestClose = closed.last().close,
                note = "Set the buy and sell zone for this timeframe in Settings",
                points = points
            )
        }
        val type = Strategy.detect(points, cfg)
        val isNew = lastProcessedSec == null || latest.timeSec > lastProcessedSec
        // Fresh = the signal candle closed less than one timeframe ago. Stale signals are shown, never traded.
        val closedAtSec = latest.timeSec + tfSeconds
        val fresh = nowSec - closedAtSec < tfSeconds
        return Evaluation(
            status = EvalStatus.OK,
            type = type,
            latest = latest,
            latestClose = closed.last().close,
            isNewCandle = isNew,
            actionable = isNew && fresh && type != SignalType.HOLD,
            note = if (type != SignalType.HOLD && isNew && !fresh) "Signal is stale, not actionable" else null,
            points = points
        )
    }

    internal fun hasGap(candles: List<Candle>, tfSeconds: Long, lastN: Int): Boolean {
        val from = maxOf(1, candles.size - lastN + 1)
        for (i in from until candles.size) {
            if (candles[i].timeSec - candles[i - 1].timeSec != tfSeconds) return true
        }
        return false
    }
}
