package com.indodaxbot.trading

import com.indodaxbot.data.PaperBalance
import com.indodaxbot.data.Position

data class PortfolioSummary(
    val equityIdr: Long,            // IDR + BTC valued at the bid
    val idrBalance: Long,
    val btcSats: Long,
    val initialIdr: Long,
    val totalPnlIdr: Long,          // equity - initial balance
    val realizedAllTimeIdr: Long,
    val realizedLast24hIdr: Long,   // positions CLOSED in the last 24 h
    val realizedTodayIdr: Long,     // positions CLOSED since local midnight
    val unrealizedIdr: Long,
    val closedTrades: Int,
    val winningTrades: Int
) {
    val winRatePct: Double? get() = if (closedTrades == 0) null else winningTrades * 100.0 / closedTrades
}

object PortfolioCalc {
    fun compute(
        balance: PaperBalance?, positions: List<Position>, bid: Long?, nowMs: Long, startOfDayMs: Long, sellFeeBps: Int
    ): PortfolioSummary {
        val idr = balance?.idrBalance ?: 0L
        val sats = balance?.btcSats ?: 0L
        val initial = balance?.initialIdr ?: 0L
        val price = bid ?: 0L
        val equity = idr + (if (price > 0) Money.cost(price, sats) else 0L)
        val closed = positions.filter { it.status == "CLOSED" }
        val dayAgo = nowMs - 24L * 3600 * 1000
        val unrealized = if (price > 0) positions.filter { it.status == "OPEN" }
            .sumOf { PositionMath.unrealized(it.toState(), price, sellFeeBps) } else 0L
        return PortfolioSummary(
            equityIdr = equity, idrBalance = idr, btcSats = sats, initialIdr = initial,
            totalPnlIdr = if (balance == null) 0L else equity - initial,
            realizedAllTimeIdr = positions.sumOf { it.realizedPnlIdr },
            realizedLast24hIdr = closed.filter { (it.closedAtMs ?: 0L) >= dayAgo }.sumOf { it.realizedPnlIdr },
            realizedTodayIdr = closed.filter { (it.closedAtMs ?: 0L) >= startOfDayMs }.sumOf { it.realizedPnlIdr },
            unrealizedIdr = unrealized,
            closedTrades = closed.size,
            winningTrades = closed.count { it.realizedPnlIdr > 0 }
        )
    }
}
