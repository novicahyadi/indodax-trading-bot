package com.indodaxbot.trading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class MoneyTest {
    @Test
    fun cost_isExactAndRounded() {
        assertEquals(100_000_000L, Money.cost(1_000_000_000L, 10_000_000L))
        assertEquals(12L, Money.cost(1_223_542_000L, 1L)) // 12.23542 -> 12
    }

    @Test
    fun fee_inBasisPoints() {
        assertEquals(510_000L, Money.fee(100_000_000L, 51))
        assertEquals(0L, Money.fee(100_000_000L, 0))
    }

    @Test
    fun satsForBudget_neverExceedsBudget() {
        val s = Money.satsForBudget(1_000_000L, 1_000_000_000L, 51)
        assertEquals(99_492L, s)
        val c = Money.cost(1_000_000_000L, s)
        assertTrue(c + Money.fee(c, 51) <= 1_000_000L)
        // one more satoshi would exceed it
        val c2 = Money.cost(1_000_000_000L, s + 1)
        assertTrue(c2 + Money.fee(c2, 51) > 1_000_000L)
    }

    @Test
    fun satsForBudget_noFee() {
        assertEquals(100_000L, Money.satsForBudget(1_000_000L, 1_000_000_000L, 0))
    }

    @Test
    fun satsForBudget_invalidInputs() {
        assertEquals(0L, Money.satsForBudget(0, 1_000_000_000L, 51))
        assertEquals(0L, Money.satsForBudget(1_000_000L, 0, 51))
    }

    @Test
    fun btcString_andIdr() {
        assertEquals("0.00099492", Money.btcString(99_492L))
        assertEquals("1.00000000", Money.btcString(100_000_000L))
        assertEquals(1_223_542_000L, Money.idr(BigDecimal("1223542000.4")))
    }
}
