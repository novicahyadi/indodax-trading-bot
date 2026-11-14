package com.indodaxbot.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity(tableName = "bot_event", indices = [Index("timestampMs")])
data class BotEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val mode: String,      // LIVE or PAPER, never mixed
    val level: String,     // INFO, WARN, ERROR
    val message: String    // must never contain API secrets
)

@Dao
interface BotEventDao {
    @Insert suspend fun insert(event: BotEvent): Long
    @Query("SELECT * FROM bot_event ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<BotEvent>
}

// Phase 1 skeleton: remaining entities (Position, TradingOrder, TradeExecution,
// PortfolioSnapshot, TradingSignal, Paper*, configs) are added in later phases.
@Database(entities = [BotEvent::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun botEventDao(): BotEventDao
}
