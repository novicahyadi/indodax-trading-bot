package com.indodaxbot.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        BotEvent::class, TradingSignal::class, SignalCursor::class,
        Position::class, TradingOrder::class, TradeExecution::class, PaperBalance::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun botEventDao(): BotEventDao
    abstract fun signalDao(): SignalDao
    abstract fun tradingDao(): TradingDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, AppDatabase::class.java, "indodax_bot.db"
            )
                // Versions 1 and 2 held no financial data, so they may be wiped. From version 3 on
                // financial records exist: any further schema change MUST ship an explicit Migration.
                // Without one Room refuses to open the database instead of silently deleting it.
                .fallbackToDestructiveMigrationFrom(1, 2)
                .build()
                .also { instance = it }
        }
    }
}
