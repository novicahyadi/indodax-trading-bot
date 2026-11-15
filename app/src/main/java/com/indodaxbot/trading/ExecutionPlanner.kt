package com.indodaxbot.trading

import com.indodaxbot.strategy.ExecutionConfig
import com.indodaxbot.strategy.ExecutionMode
import com.indodaxbot.strategy.SignalType

/** What the bot currently has open, in broker-independent terms (prices in IDR, quantities in satoshis). */
data class CycleView(
    val positionId: Long? = null,
    val heldSats: Long = 0,
    val avgEntryPrice: Long = 0,
    /** Client id of the buy order that still has an unfilled part, or null. */
    val buyOrderId: String? = null,
    val buyLimitPrice: Long = 0,
    /** Percent of the buy order that is filled (100 when no buy order is active). */
    val buyFilledPct: Double = 100.0,
    /** True while an exit limit sell is waiting (stop loss and take profit are off in that state). */
    val sellOrderActive: Boolean = false
) {
    val hasPosition: Boolean get() = positionId != null
}

data class Market(val last: Long, val ask: Long, val bid: Long)

/** Low and High of the candle that produced the signal. */
data class SignalCandle(val low: Long, val high: Long)

sealed class Action {
    data class MarketBuy(val budgetIdr: Long) : Action()
    data class LimitBuy(val price: Long, val budgetIdr: Long) : Action()
    data class CancelBuyOrder(val reason: String) : Action()
    object CancelAllOrders : Action() {
        override fun toString() = "CancelAllOrders"
    }
    data class LimitSell(val price: Long) : Action()
    data class MarketSellAll(val reason: String) : Action()
    data class Skip(val reason: String) : Action()
}

/**
 * Pure decision logic for Market Maker and Market Taker mode. It only returns what to do; the engine
 * carries it out. The stop loss is synthetic (price monitoring), because Indodax cannot create
 * stop-limit orders.
 */
object ExecutionPlanner {
    /** Absorbs floating point noise when a price sits exactly on a trigger level. */
    private const val EPS = 1e-6

    /** 0 = no stop loss yet, 1..3 = level reached, 4 = buy 100 % filled. */
    fun slStage(filledPct: Double, ex: ExecutionConfig): Int = when {
        filledPct >= 100.0 -> 4
        filledPct >= ex.slLevel3 -> 3
        filledPct >= ex.slLevel2 -> 2
        filledPct >= ex.slLevel1 -> 1
        else -> 0
    }

    fun onSignal(
        type: SignalType, cycle: CycleView, candle: SignalCandle, m: Market, ex: ExecutionConfig
    ): List<Action> {
        if (type == SignalType.HOLD) return emptyList()
        if (type == SignalType.BUY) {
            if (cycle.hasPosition) return listOf(Action.Skip("BUY ignored: a position is already open"))
            return when (ex.mode) {
                ExecutionMode.MARKET_TAKER -> listOf(Action.MarketBuy(ex.orderSizeIdr))
                ExecutionMode.MARKET_MAKER -> when {
                    candle.low <= 0 -> listOf(Action.Skip("BUY skipped: invalid signal candle"))
                    candle.low >= m.ask ->
                        listOf(Action.Skip("BUY skipped: signal low ${candle.low} is not below the ask ${m.ask}"))
                    else -> listOf(Action.LimitBuy(candle.low, ex.orderSizeIdr))
                }
            }
        }
        // SELL
        if (!cycle.hasPosition) return listOf(Action.Skip("SELL ignored: no position"))
        if (cycle.heldSats <= 0) {
            return if (cycle.buyOrderId != null) listOf(Action.CancelBuyOrder("SELL signal before any fill"))
            else listOf(Action.Skip("SELL ignored: nothing to sell"))
        }
        return when (ex.mode) {
            ExecutionMode.MARKET_TAKER -> listOf(Action.CancelAllOrders, Action.MarketSellAll("SELL signal"))
            ExecutionMode.MARKET_MAKER ->
                if (m.bid >= candle.high) {
                    listOf(Action.CancelAllOrders, Action.MarketSellAll("SELL signal, bid already at or above signal high"))
                } else {
                    listOf(Action.CancelAllOrders, Action.LimitSell(candle.high))
                }
        }
    }

    fun onTick(cycle: CycleView, m: Market, ex: ExecutionConfig): List<Action> {
        if (!cycle.hasPosition) return emptyList()
        if (cycle.sellOrderActive) return emptyList()   // exiting on a SELL signal: SL/TP are cancelled by design
        return when (ex.mode) {
            ExecutionMode.MARKET_MAKER -> {
                if (cycle.heldSats <= 0) {
                    val id = cycle.buyOrderId
                    if (id != null && cycle.buyLimitPrice > 0) {
                        val risePct = (m.last - cycle.buyLimitPrice) * 100.0 / cycle.buyLimitPrice
                        if (risePct >= ex.makerChaseCancelPct) {
                            return listOf(Action.CancelBuyOrder("price rose ${"%.2f".format(risePct)}% above the unfilled limit buy"))
                        }
                    }
                    emptyList()
                } else {
                    val armed = slStage(cycle.buyFilledPct, ex) >= 1
                    exitChecks(cycle, m, ex.makerTakeProfitPct, ex.makerStopLossPct, armed)
                }
            }
            ExecutionMode.MARKET_TAKER ->
                if (ex.takerUseSlTp && cycle.heldSats > 0) {
                    exitChecks(cycle, m, ex.takerTakeProfitPct, ex.takerStopLossPct, true)
                } else emptyList()
        }
    }

    private fun exitChecks(c: CycleView, m: Market, tpPct: Double, slPct: Double, slArmed: Boolean): List<Action> {
        val avg = c.avgEntryPrice.toDouble()
        val bid = m.bid.toDouble()
        if (avg <= 0.0 || bid <= 0.0) return emptyList()
        val cancelBuy: (String) -> List<Action> = { r ->
            if (c.buyOrderId != null) listOf(Action.CancelBuyOrder(r)) else emptyList()
        }
        if (bid >= avg * (1 + tpPct / 100.0) - EPS) {
            return cancelBuy("take profit") + Action.MarketSellAll("TAKE_PROFIT")
        }
        if (slArmed && bid <= avg * (1 - slPct / 100.0) + EPS) {
            return cancelBuy("stop loss") + Action.MarketSellAll("STOP_LOSS")
        }
        return emptyList()
    }
}
