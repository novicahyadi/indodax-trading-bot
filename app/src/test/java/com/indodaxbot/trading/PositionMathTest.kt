package com.indodaxbot.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionMathTest {
    private val fee = 51

    private fun buy(s: PositionState, price: Long, qty: Long): PositionState {
        val c = Money.cost(price, qty)
        return PositionMath.applyBuy(s, price, qty, Money.fee(c, fee))
    }

    private fun sell(s: PositionState, price: Long, qty: Long): PositionState {
        val c = Money.cost(price, qty)
        return PositionMath.applySell(s, price, qty, Money.fee(c, fee))
    }

    private fun twoBuys(): PositionState {
        var s = PositionState(targetQtySats = 10_000_000L)
        s = buy(s, 1_000_000_000L, 5_000_000L)
        s = buy(s, 1_100_000_000L, 5_000_000L)
        return s
    }

    @Test
    fun weightedAverageEntry_overPartialFills() {
        val s = twoBuys()
        assertEquals(1_050_000_000L, s.avgEntryPrice)
        assertEquals(10_000_000L, s.holdingSats)
        assertEquals(535_500L, s.buyFeesIdr)              // 255,000 + 280,500
        assertEquals(105_535_500L, s.remainingCostIdr)    // notional + fees
    }

    @Test
    fun partialBuy_hasPnlImmediately_andIsFilling() {
        var s = PositionState(targetQtySats = 10_000_000L)
        s = buy(s, 1_000_000_000L, 2_000_000L)
        assertTrue(s.isFilling)
        assertFalse(s.isClosed)
        // price unchanged: only fees are lost, so P/L is negative right away
        val pnl = PositionMath.totalPnl(s, 1_000_000_000L, fee)
        assertTrue(pnl < 0)
        // buy fee 102,000 + estimated sell fee 102,000
        assertEquals(-204_000L, pnl)
    }

    @Test
    fun buyCannotExceedTarget() {
        val s = PositionState(targetQtySats = 1_000_000L)
        try {
            buy(s, 1_000_000_000L, 2_000_000L)
            throw AssertionError("expected failure")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun fullyFilledBuy_isHolding() {
        val s = twoBuys()
        assertFalse(s.isFilling)
        assertFalse(s.isClosed)
    }

    @Test
    fun partialSell_realizesProportionalCost_andStaysOpen() {
        var s = twoBuys()
        s = sell(s, 1_200_000_000L, 4_000_000L)
        assertEquals(5_541_000L, s.realizedPnlIdr)
        assertEquals(63_321_300L, s.remainingCostIdr)
        assertEquals(6_000_000L, s.holdingSats)
        assertFalse(s.isClosed)
        assertEquals(8_311_500L, PositionMath.unrealized(s, 1_200_000_000L, fee))
    }

    @Test
    fun sellingEverything_closesPosition_andRealizedAddsUp() {
        var s = twoBuys()
        s = sell(s, 1_200_000_000L, 4_000_000L)
        s = sell(s, 1_000_000_000L, 6_000_000L)
        assertTrue(s.isClosed)
        assertEquals(0L, s.holdingSats)
        assertEquals(0L, s.remainingCostIdr)
        assertEquals(1_913_700L, s.realizedPnlIdr)
        assertEquals(0L, PositionMath.unrealized(s, 1_200_000_000L, fee))
    }

    @Test
    fun cannotSellMoreThanHeld() {
        val s = twoBuys()
        try {
            sell(s, 1_000_000_000L, 10_000_001L)
            throw AssertionError("expected failure")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun cancelRemainingBuy_closesEmptyPosition() {
        val s = PositionMath.cancelRemainingBuy(PositionState(targetQtySats = 5_000_000L))
        assertTrue(s.isClosed)
        assertFalse(s.isFilling)
    }

    @Test
    fun cancelRemainingBuy_keepsFilledPartOpen() {
        var s = PositionState(targetQtySats = 10_000_000L)
        s = buy(s, 1_000_000_000L, 3_000_000L)
        s = PositionMath.cancelRemainingBuy(s)
        assertFalse(s.isFilling)
        assertFalse(s.isClosed)
        assertEquals(3_000_000L, s.holdingSats)
    }

    @Test
    fun pnlPercent_isRelativeToTotalCost() {
        val s = twoBuys()
        assertEquals(10.0, PositionMath.pnlPercent(10_553_550L, s), 0.01)
    }
}
