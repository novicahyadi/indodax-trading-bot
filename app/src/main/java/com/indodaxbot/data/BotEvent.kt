package com.indodaxbot.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

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

    @Query("SELECT * FROM bot_event ORDER BY timestampMs DESC, id DESC LIMIT :limit")
    fun observeLatest(limit: Int): kotlinx.coroutines.flow.Flow<List<BotEvent>>
}
