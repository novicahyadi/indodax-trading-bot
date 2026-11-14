package com.indodaxbot.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [BotEvent::class, TradingSignal::class, SignalCursor::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun botEventDao(): BotEventDao
    abstract fun signalDao(): SignalDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, AppDatabase::class.java, "indodax_bot.db"
            )
                // TEMPORARY: the database holds no financial data yet. Before Phase 4 stores
                // positions/orders this MUST be replaced with explicit migrations.
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
