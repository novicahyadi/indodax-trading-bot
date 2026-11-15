package com.indodaxbot.strategy

import com.indodaxbot.api.Candle

enum class EvalStatus { OK, WARMING_UP, MISSING_CANDLES, INVALID_DATA, NOT_CONFIGURED }

data class Evaluation(
    val status: EvalStatus,
    val type: SignalType = SignalType.HOLD,
    /** Latest raw MACD values (IDR scale), for information only. */
    val latestRaw: MacdPoint? = null,
    /** Latest normalized values (0-100); the signals are computed from these. */
    val latest: NormPoint? = null,
    /** The latest closed candle (its Low/High are used for maker limit prices later). */
    val signalCandle: Candle? = null,
    /** Result of each strategy, null when switched off. */
    val reversal: SignalType? = null,
    val crossover: SignalType? = null,
    val crossAngleDeg: Double? = null,
    /** True if the latest closed candle has not been processed before. */
    val isNewCandle: Boolean = false,
    /** True only for a BUY/SELL on a NEW, FRESH, gap-free candle. Execution must check this. */
    val actionable: Boolean = false,
    val note: String? = null,
    /** All normalized points (oldest first), used for the chart. */
    val norm: List<NormPoint> = emptyList()
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
        cfg: StrategyConfig
    ): Evaluation {
        if (!cfg.anyEnabled) {
            return Evaluation(EvalStatus.NOT_CONFIGURED, note = "Switch on at least one strategy in Settings")
        }
        if (closed.any { !it.close.isFinite() || it.close <= 0.0 }) {
            return Evaluation(EvalStatus.INVALID_DATA, note = "Invalid candle data")
        }
        if (closed.size < cfg.requiredCandles) {
            return Evaluation(
                EvalStatus.WARMING_UP,
                note = "WARMING_UP: need ${cfg.requiredCandles} candles, have ${closed.size}"
            )
        }
        if (hasGap(closed, tfSeconds, GAP_CHECK_CANDLES)) {
            return Evaluation(EvalStatus.MISSING_CANDLES, note = "Missing candles in recent data")
        }
        val points = Macd.compute(closed, cfg.macd)
        val norm = Normalizer.normalize(points, cfg.normLookback)
        if (norm.size < 3) {
            return Evaluation(EvalStatus.WARMING_UP, note = "WARMING_UP: not enough indicator points")
        }
        val i = norm.size - 1
        val latest = norm[i]
        val rev = if (cfg.enableReversal) DualSignal.reversalAt(norm, i, cfg) else null
        val cross = if (cfg.enableCrossover) DualSignal.crossoverAt(norm, i, cfg) else null
        val type = DualSignal.combine(rev, cross?.type, cfg.logicMode)

        val isNew = lastProcessedSec == null || latest.timeSec > lastProcessedSec
        // Fresh = the signal candle closed less than one timeframe ago. Stale signals are shown, never traded.
        val closedAtSec = latest.timeSec + tfSeconds
        val fresh = nowSec - closedAtSec < tfSeconds
        return Evaluation(
            status = EvalStatus.OK,
            type = type,
            latestRaw = points.last(),
            latest = latest,
            signalCandle = closed.last(),
            reversal = rev,
            crossover = cross?.type,
            crossAngleDeg = cross?.angleDeg,
            isNewCandle = isNew,
            actionable = isNew && fresh && type != SignalType.HOLD,
            note = if (type != SignalType.HOLD && isNew && !fresh) "Signal is stale, not actionable" else null,
            norm = norm
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
