package com.indodaxbot.trading

import com.indodaxbot.data.PaperBalance
import com.indodaxbot.data.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PortfolioCalcTest {
    private val now = 1_700_000_000_000L
    private val hour = 3_600_000L

    private fun pos(status: String, pnl: Long, closedAt: Long?) = Position(
        mode = "PAPER", pair = "BTCIDR", createdAtMs = now - 100 * hour, closedAtMs = closedAt, status = status,
        targetQtySats = 0, buyQtySats = 0, buyNotionalIdr = 0, buyFeesIdr = 0, soldQtySats = 0,
        remainingCostIdr = 0, realizedPnlIdr = pnl
    )

    @Test
    fun realizedWindows_andWinRate() {
        val positions = listOf(
            pos("CLOSED", 100_000, now - 2 * hour),     // today and 24h
            pos("CLOSED", -40_000, now - 30 * hour),    // older than 24h, and before midnight
            pos("CLOSED", 10_000, now - 10 * hour),     // 24h, before 'today' start below
            pos("OPEN", 5_000, null)                    // partial realized, not closed
        )
        val bal = PaperBalance(idrBalance = 10_000_000, lockedIdr = 0, btcSats = 0, lockedBtcSats = 0, initialIdr = 9_000_000, updatedAtMs = now)
        val s = PortfolioCalc.compute(bal, positions, 1_000_000_000, now, now - 5 * hour, 51)
        assertEquals(75_000L, s.realizedAllTimeIdr)
        assertEquals(110_000L, s.realizedLast24hIdr)
        assertEquals(100_000L, s.realizedTodayIdr)
        assertEquals(3, s.closedTrades)
        assertEquals(2, s.winningTrades)
        assertEquals(1_000_000L, s.totalPnlIdr)
        assertEquals(10_000_000L, s.equityIdr)
    }

    @Test
    fun equityValuesBtcAtBid() {
        val bal = PaperBalance(idrBalance = 1_000_000, lockedIdr = 0, btcSats = 50_000_000, lockedBtcSats = 0, initialIdr = 1_000_000, updatedAtMs = now)
        val s = PortfolioCalc.compute(bal, emptyList(), 1_000_000_000, now, now, 51)
        // 500,000,000 of BTC minus the 0.51% estimated sell fee (2,550,000)
        assertEquals(1_000_000L + 500_000_000L - 2_550_000L, s.equityIdr)
        assertNull(s.winRatePct)
    }

    @Test
    fun noBalanceYet_isZero() {
        val s = PortfolioCalc.compute(null, emptyList(), null, now, now, 51)
        assertEquals(0L, s.equityIdr)
        assertEquals(0L, s.totalPnlIdr)
    }

    @Test
    fun totalPnl_equalsRealizedPlusUnrealized_forAnOpenPosition() {
        // Buy 0.00084088 BTC at 1,183,182,000 (taker 51 bps), still held. Cash left = 10,000,000 - cost - fee.
        val sats = 84_088L
        val cost = Money.cost(1_183_182_000, sats)
        val fee = Money.fee(cost, 51)
        val state = PositionMath.applyBuy(PositionState(targetQtySats = sats), 1_183_182_000, sats, fee)
        val p = pos("OPEN", 0, null).copy(
            targetQtySats = state.targetQtySats, buyQtySats = state.buyQtySats, buyNotionalIdr = state.buyNotionalIdr,
            buyFeesIdr = state.buyFeesIdr, remainingCostIdr = state.remainingCostIdr
        )
        val bal = PaperBalance(
            idrBalance = 10_000_000 - cost - fee, lockedIdr = 0, btcSats = sats, lockedBtcSats = 0,
            initialIdr = 10_000_000, updatedAtMs = now
        )
        val s = PortfolioCalc.compute(bal, listOf(p), 1_189_170_000, now, now, 51)
        assertEquals(s.realizedAllTimeIdr + s.unrealizedIdr, s.totalPnlIdr)
        assertEquals(true, s.totalPnlIdr < 0)   // price is above the entry, but two 0.51% fees are larger
    }
}
