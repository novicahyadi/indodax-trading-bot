package com.indodaxbot.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Every table carries `mode` (PAPER / LIVE); queries always filter on it so the two never mix. */
@Entity(tableName = "trading_position", indices = [Index("mode", "status")])
data class Position(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mode: String,
    val pair: String,
    val createdAtMs: Long,
    val closedAtMs: Long? = null,
    val status: String,              // OPEN or CLOSED (derived from quantities, see PositionState)
    val targetQtySats: Long,
    val buyQtySats: Long,
    val buyNotionalIdr: Long,
    val buyFeesIdr: Long,
    val soldQtySats: Long,
    val remainingCostIdr: Long,
    val realizedPnlIdr: Long,
    val tpPrice: Long? = null,       // set by Market Maker mode (Phase 5/6)
    val slPrice: Long? = null
)

@Entity(
    tableName = "trading_order",
    foreignKeys = [ForeignKey(
        entity = Position::class, parentColumns = ["id"], childColumns = ["positionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("positionId"), Index("mode", "status")]
)
data class TradingOrder(
    @PrimaryKey val clientOrderId: String,   // our unique id, also sent to the exchange as newClientOrderId
    val positionId: Long,
    val mode: String,
    val exchangeOrderId: String? = null,     // LIVE only
    val side: String,                        // BUY or SELL
    val type: String,                        // MARKET or LIMIT
    val priceIdr: Long,                      // limit price, or the reference price for market orders
    val qtySats: Long,
    val filledSats: Long,
    val reservedIdr: Long = 0,               // paper limit buys: IDR still held back for this order
    val status: String,                      // OrderStatus name
    val createdAtMs: Long,
    val executedAtMs: Long? = null,          // time of the latest fill
    val updatedAtMs: Long
)

@Entity(
    tableName = "trade_execution",
    foreignKeys = [ForeignKey(
        entity = TradingOrder::class, parentColumns = ["clientOrderId"], childColumns = ["orderId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("orderId"), Index("positionId")]
)
data class TradeExecution(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val orderId: String,
    val positionId: Long,
    val mode: String,
    val side: String,
    val priceIdr: Long,
    val qtySats: Long,
    val feeIdr: Long,
    val timestampMs: Long
)

/** The single paper account (id = 1). Everything is integers: rupiah and satoshis. */
@Entity(tableName = "paper_balance")
data class PaperBalance(
    @PrimaryKey val id: Int = 1,
    val idrBalance: Long,
    val lockedIdr: Long,        // held back by open paper limit buys
    val btcSats: Long,
    val lockedBtcSats: Long,    // held back by open paper limit sells
    val initialIdr: Long,
    val updatedAtMs: Long
) {
    val availableIdr: Long get() = idrBalance - lockedIdr
    val availableBtcSats: Long get() = btcSats - lockedBtcSats
}

@Dao
interface TradingDao {
    @Insert suspend fun insertPosition(p: Position): Long
    @Update suspend fun updatePosition(p: Position)

    @Query("SELECT * FROM trading_position WHERE id = :id")
    suspend fun position(id: Long): Position?

    @Query("SELECT * FROM trading_position WHERE mode = :mode")
    fun observePositions(mode: String): Flow<List<Position>>

    @Query("DELETE FROM trading_position WHERE mode = :mode")
    suspend fun deletePositions(mode: String)

    @Query("SELECT COALESCE(SUM(realizedPnlIdr), 0) FROM trading_position WHERE mode = :mode")
    suspend fun allTimeRealizedPnl(mode: String): Long

    @Query("SELECT * FROM trading_position WHERE mode = :mode AND status = 'OPEN' ORDER BY createdAtMs DESC")
    suspend fun openPositions(mode: String): List<Position>

    @Query("SELECT * FROM trading_order WHERE positionId = :positionId ORDER BY createdAtMs ASC")
    suspend fun ordersOf(positionId: Long): List<TradingOrder>

    @Insert suspend fun insertOrder(o: TradingOrder)
    @Update suspend fun updateOrder(o: TradingOrder)

    @Query("SELECT * FROM trading_order WHERE clientOrderId = :id")
    suspend fun order(id: String): TradingOrder?

    @Query("SELECT * FROM trading_order WHERE mode = :mode ORDER BY createdAtMs DESC")
    fun observeOrders(mode: String): Flow<List<TradingOrder>>

    @Query("SELECT * FROM trading_order WHERE mode = :mode AND status IN ('OPEN', 'PARTIALLY_FILLED') ORDER BY createdAtMs ASC")
    suspend fun activeOrders(mode: String): List<TradingOrder>

    /** BTC already promised to open sell orders of this position (prevents conflicting sells). */
    @Query("SELECT COALESCE(SUM(qtySats - filledSats), 0) FROM trading_order WHERE positionId = :positionId AND side = 'SELL' AND status IN ('OPEN', 'PARTIALLY_FILLED')")
    suspend fun lockedSellSats(positionId: Long): Long

    @Insert suspend fun insertExecution(e: TradeExecution): Long

    @Query("SELECT * FROM paper_balance WHERE id = 1")
    suspend fun balance(): PaperBalance?

    @Query("SELECT * FROM paper_balance WHERE id = 1")
    fun observeBalance(): Flow<PaperBalance?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBalance(b: PaperBalance)
}
