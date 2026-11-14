package com.indodaxbot.trading

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Pure position accounting (average-cost method). Buy fees are part of the cost basis.
 * The position lifecycle is derived from quantities and is independent of individual order states.
 */
data class PositionState(
    val targetQtySats: Long,        // quantity the original buy order asked for
    val buyQtySats: Long = 0,       // total bought so far (all executions)
    val buyNotionalIdr: Long = 0,   // sum of price*qty of buys, WITHOUT fees (for the average entry price)
    val buyFeesIdr: Long = 0,
    val soldQtySats: Long = 0,
    val remainingCostIdr: Long = 0, // cost basis (incl. buy fees) of the BTC still held
    val realizedPnlIdr: Long = 0
) {
    val holdingSats: Long get() = buyQtySats - soldQtySats
    val remainingToFillSats: Long get() = (targetQtySats - buyQtySats).coerceAtLeast(0)

    /** Weighted average entry price over all buy executions (fees excluded). */
    val avgEntryPrice: Long
        get() = if (buyQtySats == 0L) 0 else
            BigDecimal(buyNotionalIdr).multiply(BigDecimal(Money.SATS_PER_BTC))
                .divide(BigDecimal(buyQtySats), 0, RoundingMode.HALF_UP).longValueExact()

    /** FILLING = original buy not 100% filled. HOLDING = buy fully filled. */
    val isFilling: Boolean get() = buyQtySats < targetQtySats

    /** CLOSED = the buy is finished and 100% of what was bought has been sold. A partial sell stays OPEN. */
    val isClosed: Boolean get() = buyQtySats >= targetQtySats && soldQtySats >= buyQtySats

    val totalCostIdr: Long get() = buyNotionalIdr + buyFeesIdr
}

object PositionMath {

    fun applyBuy(s: PositionState, priceIdr: Long, qtySats: Long, feeIdr: Long): PositionState {
        require(qtySats > 0 && priceIdr > 0 && feeIdr >= 0)
        require(s.buyQtySats + qtySats <= s.targetQtySats) { "Buy exceeds order quantity" }
        val cost = Money.cost(priceIdr, qtySats)
        return s.copy(
            buyQtySats = s.buyQtySats + qtySats,
            buyNotionalIdr = s.buyNotionalIdr + cost,
            buyFeesIdr = s.buyFeesIdr + feeIdr,
            remainingCostIdr = s.remainingCostIdr + cost + feeIdr
        )
    }

    /** Returns the new state; realizedPnlIdr is updated with (proceeds - fee - cost basis of the sold part). */
    fun applySell(s: PositionState, priceIdr: Long, qtySats: Long, feeIdr: Long): PositionState {
        require(qtySats > 0 && priceIdr > 0 && feeIdr >= 0)
        require(qtySats <= s.holdingSats) { "Cannot sell more than held" }
        val costOfSold =
            if (qtySats == s.holdingSats) s.remainingCostIdr
            else BigDecimal(s.remainingCostIdr).multiply(BigDecimal(qtySats))
                .divide(BigDecimal(s.holdingSats), 0, RoundingMode.HALF_UP).longValueExact()
        val proceeds = Money.cost(priceIdr, qtySats) - feeIdr
        return s.copy(
            soldQtySats = s.soldQtySats + qtySats,
            remainingCostIdr = s.remainingCostIdr - costOfSold,
            realizedPnlIdr = s.realizedPnlIdr + proceeds - costOfSold
        )
    }

    /** Stop buying: the target becomes what has actually been filled. */
    fun cancelRemainingBuy(s: PositionState): PositionState = s.copy(targetQtySats = s.buyQtySats)

    /**
     * Unrealized P/L of the BTC still held, valued at the [bidPrice] (what a market sell would get)
     * minus the estimated sell fee and the remaining cost basis.
     */
    fun unrealized(s: PositionState, bidPrice: Long, sellFeeBps: Int): Long {
        if (s.holdingSats <= 0 || bidPrice <= 0) return 0
        val value = Money.cost(bidPrice, s.holdingSats)
        return value - Money.fee(value, sellFeeBps) - s.remainingCostIdr
    }

    /** Position P/L = realized + unrealized (partial buys count immediately, no waiting for 100% fill). */
    fun totalPnl(s: PositionState, bidPrice: Long, sellFeeBps: Int): Long =
        s.realizedPnlIdr + unrealized(s, bidPrice, sellFeeBps)

    fun pnlPercent(pnlIdr: Long, s: PositionState): Double =
        if (s.totalCostIdr <= 0) 0.0 else pnlIdr.toDouble() * 100.0 / s.totalCostIdr.toDouble()
}
