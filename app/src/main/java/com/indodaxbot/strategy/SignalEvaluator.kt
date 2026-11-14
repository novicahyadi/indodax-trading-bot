package com.indodaxbot.strategy

import com.indodaxbot.api.Candle

enum class EvalStatus { OK, INSUFFICIENT_DATA, MISSING_CANDLES, INVALID_DATA }

data class Evaluation(
    val status: EvalStatus,
    val type: SignalType = SignalType.HOLD,
    val latest: MacdPoint? = null,
    val latestClose: Double? = null,
    /** True if the latest closed candle has not been processed before. */
    val isNewCandle: Boolean = false,
    /** True only for a BUY/SELL on a NEW, FRESH, gap-free candle. Execution must check this. */
    val actionable: Boolean = false,
    val note: String? = null
)

object SignalEvaluator {
    /** Candles that must be contiguous at the end of the series. */
    const val GAP_CHECK_CANDLES = 60

    /**
     * [closed] must contain CLOSED candles only, oldest first (use Candles.closedOnly).
     * [lastProcessedSec] is the open time of the last candle already evaluated (persisted by the caller).
     */
    fun evaluate(
        closed: List<Candle>,
        tfSeconds: Long,
        nowSec: Long,
        lastProcessedSec: Long?,
        params: MacdParams = MacdParams()
    ): Evaluation {
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
        if (points.size < 2) {
            return Evaluation(EvalStatus.INSUFFICIENT_DATA, note = "Not enough MACD points")
        }
        val latest = points.last()
        val prev = points[points.size - 2]
        val type = Crossover.detect(prev, latest)
        val isNew = lastProcessedSec == null || latest.timeSec > lastProcessedSec
        // Fresh = the signal candle closed less than one timeframe ago. Stale crossovers are shown, never traded.
        val closedAtSec = latest.timeSec + tfSeconds
        val fresh = nowSec - closedAtSec < tfSeconds
        return Evaluation(
            status = EvalStatus.OK,
            type = type,
            latest = latest,
            latestClose = closed.last().close,
            isNewCandle = isNew,
            actionable = isNew && fresh && type != SignalType.HOLD,
            note = if (type != SignalType.HOLD && isNew && !fresh) "Signal is stale, not actionable" else null
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
