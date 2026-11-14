package com.indodaxbot.strategy

import androidx.room.withTransaction
import com.indodaxbot.api.ApiResult
import com.indodaxbot.api.Candles
import com.indodaxbot.api.IndodaxClient
import com.indodaxbot.data.AppDatabase
import com.indodaxbot.data.SignalCursor
import com.indodaxbot.data.TradingSignal
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SignalSnapshot(
    val timeframe: String,
    val evaluation: Evaluation,
    val lastSignal: TradingSignal?,
    val evaluatedAtMs: Long
)

/**
 * Fetches candles, evaluates MACD on CLOSED candles only, and records each new candle exactly once.
 * Signals are never produced from the still-forming candle. Calls are serialized by a mutex.
 */
class SignalService(
    private val client: IndodaxClient,
    private val db: AppDatabase,
    val pair: String = "BTCIDR",
    val timeframe: String = "15",
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val mutex = Mutex()

    val timeframeMs: Long = (Candles.timeframeSeconds(timeframe) ?: 900L) * 1000L

    suspend fun refresh(): ApiResult<SignalSnapshot> = mutex.withLock { doRefresh() }

    private suspend fun doRefresh(): ApiResult<SignalSnapshot> {
        val tfSec = Candles.timeframeSeconds(timeframe)
            ?: return ApiResult.Failure(com.indodaxbot.api.Kind.MALFORMED, "Unsupported timeframe")
        val raw = client.candles(pair, timeframe, FETCH_COUNT)
        val candles = when (raw) {
            is ApiResult.Success -> raw.value
            is ApiResult.Failure -> return raw
        }
        val nowMs = clock()
        val closed = Candles.closedOnly(candles, tfSec, nowMs / 1000)
        val cursorId = "$pair|$timeframe"
        val dao = db.signalDao()

        val eval = SignalEvaluator.evaluate(closed, tfSec, nowMs / 1000, dao.cursor(cursorId))

        val latest = eval.latest
        if (eval.status == EvalStatus.OK && eval.isNewCandle && latest != null) {
            // Signal row and cursor move together or not at all.
            db.withTransaction {
                if (eval.type != SignalType.HOLD) {
                    dao.insert(
                        TradingSignal(
                            pair = pair,
                            timeframe = timeframe,
                            type = eval.type.name,
                            candleTimeSec = latest.timeSec,
                            macd = latest.macd,
                            signalLine = latest.signal,
                            histogram = latest.histogram,
                            closePrice = eval.latestClose ?: 0.0,
                            actionable = eval.actionable,
                            createdAtMs = nowMs
                        )
                    )
                }
                dao.setCursor(SignalCursor(cursorId, latest.timeSec))
            }
        }
        val last = dao.latest(pair, timeframe, 1).firstOrNull()
        return ApiResult.Success(SignalSnapshot(timeframe, eval, last, nowMs))
    }

    private companion object {
        const val FETCH_COUNT = 200
    }
}
