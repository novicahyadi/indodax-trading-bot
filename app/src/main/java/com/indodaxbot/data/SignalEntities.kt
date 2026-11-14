package com.indodaxbot.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * One row per confirmed BUY/SELL crossover. The unique index (pair, timeframe, candleTimeSec)
 * makes it impossible to store two signals for the same candle, even if code is called twice.
 */
@Entity(
    tableName = "trading_signal",
    indices = [Index(value = ["pair", "timeframe", "candleTimeSec"], unique = true)]
)
data class TradingSignal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pair: String,
    val timeframe: String,
    val type: String,            // BUY or SELL
    val candleTimeSec: Long,     // open time of the candle that produced the signal
    val macd: Double,
    val signalLine: Double,
    val histogram: Double,
    val closePrice: Double,
    val actionable: Boolean,
    val createdAtMs: Long
)

/** Last candle already evaluated for a pair+timeframe ("BTCIDR|15"). Survives restarts. */
@Entity(tableName = "signal_cursor")
data class SignalCursor(
    @PrimaryKey val cursorId: String,
    val lastCandleSec: Long
)

@Dao
interface SignalDao {
    /** Returns -1 if a signal for that candle already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(signal: TradingSignal): Long

    @Query("SELECT * FROM trading_signal WHERE pair = :pair AND timeframe = :tf ORDER BY candleTimeSec DESC LIMIT :limit")
    suspend fun latest(pair: String, tf: String, limit: Int): List<TradingSignal>

    @Query("SELECT lastCandleSec FROM signal_cursor WHERE cursorId = :id")
    suspend fun cursor(id: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setCursor(cursor: SignalCursor)
}
