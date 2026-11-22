package com.indodaxbot.trading

import androidx.room.withTransaction
import com.indodaxbot.data.AppDatabase
import com.indodaxbot.data.PaperBalance
import com.indodaxbot.data.Position
import com.indodaxbot.data.TradeExecution
import com.indodaxbot.data.TradingOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Fee rates in basis points (51 = 0.51%), set by the user in Settings > Execution.
 * PLACEHOLDERS: the real Indodax maker/taker rates have not been verified yet. Compare them with a
 * real trade history before relying on paper P/L.
 */
data class FeeRates(
    val makerBuyBps: Int = 30, val makerSellBps: Int = 30,
    val takerBuyBps: Int = 51, val takerSellBps: Int = 51
)

sealed class PaperResult {
    data class Ok(val message: String) : PaperResult()
    data class Rejected(val reason: String) : PaperResult()
}

/**
 * Paper trading simulator. Uses real market prices but never contacts the exchange's trading API.
 * Every operation runs in one database transaction, so balances, positions, orders and executions
 * always change together or not at all. Only PAPER rows are ever read or written.
 *
 * Simplifications (documented on purpose): fees are charged in IDR on both sides; market orders fill
 * completely at the best ask/bid; limit orders fill at their limit price when the market crosses
 * them, optionally in partial steps.
 */
class PaperBroker(
    private val db: AppDatabase,
    private val fees: () -> FeeRates = { FeeRates() },
    private val pair: String = "BTCIDR",
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val dao get() = db.tradingDao()
    private val mutex = Mutex()

    val feeRates: FeeRates get() = fees()

    fun positions(): Flow<List<Position>> = dao.observePositions(MODE)
    fun orders(): Flow<List<TradingOrder>> = dao.observeOrders(MODE)
    fun balance(): Flow<PaperBalance?> = dao.observeBalance()

    private suspend fun account(): PaperBalance =
        dao.balance() ?: PaperBalance(
            idrBalance = DEFAULT_IDR, lockedIdr = 0, btcSats = 0, lockedBtcSats = 0,
            initialIdr = DEFAULT_IDR, updatedAtMs = clock()
        ).also { dao.upsertBalance(it) }

    suspend fun ensureAccount() { mutex.withLock { db.withTransaction { account() } } }

    /** Deletes ALL paper positions, orders and executions and restarts with a fresh balance. */
    suspend fun resetAccount(initialIdr: Long): PaperResult = mutex.withLock {
        db.withTransaction {
            if (initialIdr < MIN_ORDER_IDR) return@withTransaction reject("Balance must be at least Rp$MIN_ORDER_IDR")
            dao.deletePositions(MODE)
            dao.upsertBalance(
                PaperBalance(
                    idrBalance = initialIdr, lockedIdr = 0, btcSats = 0, lockedBtcSats = 0,
                    initialIdr = initialIdr, updatedAtMs = clock()
                )
            )
            PaperResult.Ok("Paper account reset to Rp$initialIdr")
        }
    }

    // ---------------------------------------------------------------- buying

    suspend fun marketBuy(budgetIdr: Long, askPrice: Long): PaperResult = mutex.withLock {
        db.withTransaction {
            if (askPrice <= 0) return@withTransaction reject("Invalid price")
            if (budgetIdr < MIN_ORDER_IDR) return@withTransaction reject("Minimum order is Rp$MIN_ORDER_IDR")
            val bal = account()
            if (bal.availableIdr < budgetIdr) return@withTransaction reject("Insufficient paper IDR")
            val rates = fees()
            val sats = Money.satsForBudget(budgetIdr, askPrice, rates.takerBuyBps)
            if (sats <= 0) return@withTransaction reject("Amount too small")
            val now = clock()
            val cost = Money.cost(askPrice, sats)
            val fee = Money.fee(cost, rates.takerBuyBps)

            val state = PositionMath.applyBuy(PositionState(targetQtySats = sats), askPrice, sats, fee)
            val posId = dao.insertPosition(newPosition(state, now))
            val order = newOrder(posId, "BUY", "MARKET", askPrice, sats, now)
            dao.insertOrder(order.copy(filledSats = sats, status = OrderStatus.FILLED.name, executedAtMs = now))
            dao.insertExecution(execution(order.clientOrderId, posId, "BUY", askPrice, sats, fee, now))
            dao.upsertBalance(
                bal.copy(idrBalance = bal.idrBalance - cost - fee, btcSats = bal.btcSats + sats, updatedAtMs = now)
            )
            PaperResult.Ok("Bought ${Money.btcString(sats)} BTC")
        }
    }

    suspend fun placeLimitBuy(budgetIdr: Long, limitPrice: Long, askPrice: Long): PaperResult = mutex.withLock {
        db.withTransaction {
            if (limitPrice <= 0 || askPrice <= 0) return@withTransaction reject("Invalid price")
            if (limitPrice >= askPrice) return@withTransaction reject("Limit price must be below the ask (use a market buy)")
            if (budgetIdr < MIN_ORDER_IDR) return@withTransaction reject("Minimum order is Rp$MIN_ORDER_IDR")
            val bal = account()
            if (bal.availableIdr < budgetIdr) return@withTransaction reject("Insufficient paper IDR")
            val rates = fees()
            val sats = Money.satsForBudget(budgetIdr, limitPrice, rates.makerBuyBps)
            if (sats <= 0) return@withTransaction reject("Amount too small")
            val now = clock()
            val cost = Money.cost(limitPrice, sats)
            val reserve = cost + Money.fee(cost, rates.makerBuyBps)

            val posId = dao.insertPosition(newPosition(PositionState(targetQtySats = sats), now))
            dao.insertOrder(
                newOrder(posId, "BUY", "LIMIT", limitPrice, sats, now)
                    .copy(status = OrderStatus.OPEN.name, reservedIdr = reserve)
            )
            dao.upsertBalance(bal.copy(lockedIdr = bal.lockedIdr + reserve, updatedAtMs = now))
            PaperResult.Ok("Limit buy placed for ${Money.btcString(sats)} BTC")
        }
    }

    // --------------------------------------------------------------- selling

    /** BTC of this position not yet promised to an open sell order. */
    private suspend fun availableToSell(p: Position): Long =
        p.buyQtySats - p.soldQtySats - dao.lockedSellSats(p.id)

    suspend fun marketSell(positionId: Long, qtySats: Long?, bidPrice: Long): PaperResult = mutex.withLock {
        db.withTransaction {
            val pos = dao.position(positionId) ?: return@withTransaction reject("Position not found")
            if (pos.mode != MODE) return@withTransaction reject("Not a paper position")
            if (bidPrice <= 0) return@withTransaction reject("Invalid price")
            val avail = availableToSell(pos)
            val qty = qtySats ?: avail
            if (qty <= 0) return@withTransaction reject("Nothing available to sell")
            if (qty > avail) return@withTransaction reject("Only ${Money.btcString(avail)} BTC is available (rest is in open orders)")
            if (Money.cost(bidPrice, qty) < MIN_ORDER_IDR) return@withTransaction reject("Minimum order is Rp$MIN_ORDER_IDR")
            val bal = account()
            if (bal.availableBtcSats < qty) return@withTransaction reject("Insufficient paper BTC")
            val now = clock()
            val value = Money.cost(bidPrice, qty)
            val fee = Money.fee(value, fees().takerSellBps)

            val newState = PositionMath.applySell(pos.toState(), bidPrice, qty, fee)
            dao.updatePosition(pos.withState(newState, now))
            val order = newOrder(pos.id, "SELL", "MARKET", bidPrice, qty, now)
            dao.insertOrder(order.copy(filledSats = qty, status = OrderStatus.FILLED.name, executedAtMs = now))
            dao.insertExecution(execution(order.clientOrderId, pos.id, "SELL", bidPrice, qty, fee, now))
            dao.upsertBalance(
                bal.copy(idrBalance = bal.idrBalance + value - fee, btcSats = bal.btcSats - qty, updatedAtMs = now)
            )
            PaperResult.Ok("Sold ${Money.btcString(qty)} BTC")
        }
    }

    suspend fun placeLimitSell(positionId: Long, qtySats: Long?, limitPrice: Long, bidPrice: Long): PaperResult =
        mutex.withLock {
            db.withTransaction {
                val pos = dao.position(positionId) ?: return@withTransaction reject("Position not found")
                if (pos.mode != MODE) return@withTransaction reject("Not a paper position")
                if (limitPrice <= 0 || bidPrice <= 0) return@withTransaction reject("Invalid price")
                if (limitPrice <= bidPrice) return@withTransaction reject("Limit price must be above the bid (use a market sell)")
                val avail = availableToSell(pos)
                val qty = qtySats ?: avail
                if (qty <= 0) return@withTransaction reject("Nothing available to sell")
                if (qty > avail) return@withTransaction reject("Only ${Money.btcString(avail)} BTC is available (rest is in open orders)")
                if (Money.cost(limitPrice, qty) < MIN_ORDER_IDR) return@withTransaction reject("Minimum order is Rp$MIN_ORDER_IDR")
                val bal = account()
                if (bal.availableBtcSats < qty) return@withTransaction reject("Insufficient paper BTC")
                val now = clock()

                dao.insertOrder(
                    newOrder(pos.id, "SELL", "LIMIT", limitPrice, qty, now).copy(status = OrderStatus.OPEN.name)
                )
                dao.upsertBalance(bal.copy(lockedBtcSats = bal.lockedBtcSats + qty, updatedAtMs = now))
                PaperResult.Ok("Limit sell placed for ${Money.btcString(qty)} BTC")
            }
        }

    // ------------------------------------------------------------ cancelling

    suspend fun cancelOrder(clientOrderId: String): PaperResult = mutex.withLock {
        db.withTransaction {
            val o = dao.order(clientOrderId) ?: return@withTransaction reject("Order not found")
            if (o.mode != MODE) return@withTransaction reject("Not a paper order")
            val from = OrderStatus.valueOf(o.status)
            if (!from.isActive) return@withTransaction reject("Order is already ${o.status}")
            OrderStateMachine.require(from, OrderStatus.CANCELLED)
            val now = clock()
            val bal = account()
            val pos = dao.position(o.positionId) ?: return@withTransaction reject("Position not found")
            val remaining = o.qtySats - o.filledSats

            if (o.side == "BUY") {
                dao.upsertBalance(bal.copy(lockedIdr = (bal.lockedIdr - o.reservedIdr).coerceAtLeast(0), updatedAtMs = now))
                val ns = PositionMath.cancelRemainingBuy(pos.toState())
                dao.updatePosition(pos.withState(ns, now))
            } else {
                dao.upsertBalance(bal.copy(lockedBtcSats = (bal.lockedBtcSats - remaining).coerceAtLeast(0), updatedAtMs = now))
            }
            dao.updateOrder(o.copy(status = OrderStatus.CANCELLED.name, reservedIdr = 0, updatedAtMs = now))
            PaperResult.Ok("Order cancelled")
        }
    }

    // ----------------------------------------------------------- limit fills

    /**
     * Call with fresh market prices. Fills open paper limit orders the market has crossed
     * (buy: ask <= limit, sell: bid >= limit) at the limit price. [partialPct] < 100 fills at most that
     * percentage of the ORIGINAL quantity per call, to simulate partial fills. Returns the number of fills.
     */
    suspend fun onTick(askPrice: Long, bidPrice: Long, partialPct: Int = 100): Int = mutex.withLock {
        var fills = 0
        val active = dao.activeOrders(MODE)
        for (o in active) {
            val crossed = if (o.side == "BUY") askPrice in 1..o.priceIdr else bidPrice >= o.priceIdr && bidPrice > 0
            if (!crossed) continue
            db.withTransaction {
                // Re-read inside the transaction: the order may have been cancelled meanwhile.
                val cur = dao.order(o.clientOrderId) ?: return@withTransaction
                val from = OrderStatus.valueOf(cur.status)
                if (!from.isActive) return@withTransaction
                val remaining = cur.qtySats - cur.filledSats
                val step = if (partialPct >= 100) remaining
                else (cur.qtySats * partialPct / 100).coerceAtLeast(1).coerceAtMost(remaining)
                if (step <= 0) return@withTransaction
                fillLimit(cur, from, step)
                fills++
                Unit
            }
        }
        fills
    }

    private suspend fun fillLimit(o: TradingOrder, from: OrderStatus, qty: Long) {
        val now = clock()
        val bal = account()
        val pos = dao.position(o.positionId) ?: error("Position missing for order ${o.clientOrderId}")
        val cost = Money.cost(o.priceIdr, qty)
        val rates = fees()
        val fee = Money.fee(cost, if (o.side == "BUY") rates.makerBuyBps else rates.makerSellBps)
        val filled = o.filledSats + qty
        val to = if (filled >= o.qtySats) OrderStatus.FILLED else OrderStatus.PARTIALLY_FILLED
        OrderStateMachine.require(from, to)

        var updatedOrder = o.copy(filledSats = filled, status = to.name, executedAtMs = now, updatedAtMs = now)
        if (o.side == "BUY") {
            val ns = PositionMath.applyBuy(pos.toState(), o.priceIdr, qty, fee)
            dao.updatePosition(pos.withState(ns, now))
            val spend = cost + fee
            var release = minOf(o.reservedIdr, spend)
            var reservedLeft = o.reservedIdr - release
            if (to == OrderStatus.FILLED) { release += reservedLeft; reservedLeft = 0 } // rounding leftovers
            updatedOrder = updatedOrder.copy(reservedIdr = reservedLeft)
            dao.upsertBalance(
                bal.copy(
                    idrBalance = bal.idrBalance - spend,
                    lockedIdr = (bal.lockedIdr - release).coerceAtLeast(0),
                    btcSats = bal.btcSats + qty,
                    updatedAtMs = now
                )
            )
        } else {
            val ns = PositionMath.applySell(pos.toState(), o.priceIdr, qty, fee)
            dao.updatePosition(pos.withState(ns, now))
            dao.upsertBalance(
                bal.copy(
                    idrBalance = bal.idrBalance + cost - fee,
                    btcSats = bal.btcSats - qty,
                    lockedBtcSats = (bal.lockedBtcSats - qty).coerceAtLeast(0),
                    updatedAtMs = now
                )
            )
        }
        dao.updateOrder(updatedOrder)
        dao.insertExecution(execution(o.clientOrderId, o.positionId, o.side, o.priceIdr, qty, fee, now))
    }

    // --------------------------------------------------------------- helpers

    private fun reject(reason: String): PaperResult = PaperResult.Rejected(reason)

    private fun newPosition(s: PositionState, now: Long) = Position(
        mode = MODE, pair = pair, createdAtMs = now, status = "OPEN",
        targetQtySats = s.targetQtySats, buyQtySats = s.buyQtySats, buyNotionalIdr = s.buyNotionalIdr,
        buyFeesIdr = s.buyFeesIdr, soldQtySats = s.soldQtySats, remainingCostIdr = s.remainingCostIdr,
        realizedPnlIdr = s.realizedPnlIdr
    )

    private fun newOrder(posId: Long, side: String, type: String, price: Long, qty: Long, now: Long) = TradingOrder(
        clientOrderId = UUID.randomUUID().toString(), positionId = posId, mode = MODE,
        side = side, type = type, priceIdr = price, qtySats = qty, filledSats = 0,
        status = OrderStatus.SUBMITTED.name, createdAtMs = now, updatedAtMs = now
    )

    private fun execution(orderId: String, posId: Long, side: String, price: Long, qty: Long, fee: Long, now: Long) =
        TradeExecution(
            orderId = orderId, positionId = posId, mode = MODE, side = side,
            priceIdr = price, qtySats = qty, feeIdr = fee, timestampMs = now
        )

    companion object {
        const val MODE = "PAPER"
        /** From the Indodax docs example for BTC/IDR; verify against /api/pairs before live trading. */
        const val MIN_ORDER_IDR = 50_000L
        const val DEFAULT_IDR = 10_000_000L
    }
}

fun Position.toState() = PositionState(
    targetQtySats = targetQtySats, buyQtySats = buyQtySats, buyNotionalIdr = buyNotionalIdr,
    buyFeesIdr = buyFeesIdr, soldQtySats = soldQtySats, remainingCostIdr = remainingCostIdr,
    realizedPnlIdr = realizedPnlIdr
)

fun Position.withState(s: PositionState, nowMs: Long): Position = copy(
    targetQtySats = s.targetQtySats, buyQtySats = s.buyQtySats, buyNotionalIdr = s.buyNotionalIdr,
    buyFeesIdr = s.buyFeesIdr, soldQtySats = s.soldQtySats, remainingCostIdr = s.remainingCostIdr,
    realizedPnlIdr = s.realizedPnlIdr,
    status = if (s.isClosed) "CLOSED" else "OPEN",
    closedAtMs = if (s.isClosed) (closedAtMs ?: nowMs) else null
)
