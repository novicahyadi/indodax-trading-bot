package com.indodaxbot.strategy

import androidx.room.withTransaction
import com.indodaxbot.api.ApiResult
import com.indodaxbot.api.Candle
import com.indodaxbot.api.Candles
import com.indodaxbot.api.IndodaxClient
import com.indodaxbot.api.Kind
import com.indodaxbot.data.AppDatabase
import com.indodaxbot.data.SignalCursor
import com.indodaxbot.data.TradingSignal
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SignalSnapshot(
    val config: StrategyConfig,
    val evaluation: Evaluation,
    val lastSignal: TradingSignal?,
    val evaluatedAtMs: Long,
    /** Last closed candles, for the chart. */
    val chartCandles: List<Candle>
)

/**
 * Fetches candles, evaluates the active strategy on CLOSED candles only, and records each new candle
 * exactly once per configuration. Signals are never produced from the still-forming candle.
 * Calls are serialized by a mutex.
 */
class SignalService(
    private val client: IndodaxClient,
    private val db: AppDatabase,
    private val config: () -> StrategyConfig,
    val pair: String = "BTCIDR",
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val mutex = Mutex()

    suspend fun refresh(): ApiResult<SignalSnapshot> = mutex.withLock { doRefresh() }

    private suspend fun doRefresh(): ApiResult<SignalSnapshot> {
        val cfg = config()   // read once: a config change mid-refresh applies from the next refresh
        val tfSec = Candles.timeframeSeconds(cfg.timeframe)
            ?: return ApiResult.Failure(Kind.MALFORMED, "Unsupported timeframe")
        val fetchCount = maxOf(200, cfg.requiredCandles + CHART_CANDLES + 20)
        val raw = client.candles(pair, cfg.timeframe, fetchCount)
        val candles = when (raw) {
            is ApiResult.Success -> raw.value
            is ApiResult.Failure -> return raw
        }
        val nowMs = clock()
        val closed = Candles.closedOnly(candles, tfSec, nowMs / 1000)
        val cursorId = "$pair|${cfg.key()}"
        val dao = db.signalDao()

        val eval = SignalEvaluator.evaluate(closed, tfSec, nowMs / 1000, dao.cursor(cursorId), cfg)

        val latest = eval.latest
        if (eval.status == EvalStatus.OK && eval.isNewCandle && latest != null) {
            // Signal row and cursor move together or not at all.
            db.withTransaction {
                if (eval.type != SignalType.HOLD) {
                    dao.insert(
                        TradingSignal(
                            pair = pair,
                            timeframe = cfg.timeframe,
                            type = eval.type.name,
                            candleTimeSec = latest.timeSec,
                            // Stored on the normalized 0-100 scale: macd, signal, and their difference.
                            macd = latest.macdNorm,
                            signalLine = latest.signalNorm,
                            histogram = latest.macdNorm - latest.signalNorm,
                            closePrice = eval.signalCandle?.close ?: 0.0,
                            actionable = eval.actionable,
                            createdAtMs = nowMs
                        )
                    )
                }
                dao.setCursor(SignalCursor(cursorId, latest.timeSec))
            }
        }
        val last = dao.latest(pair, cfg.timeframe, 1).firstOrNull()
        return ApiResult.Success(SignalSnapshot(cfg, eval, last, nowMs, closed.takeLast(CHART_CANDLES)))
    }

    companion object {
        const val CHART_CANDLES = 80
    }
}