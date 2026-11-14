package com.indodaxbot.trading

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * All money is exact integer math: IDR as whole rupiah (Long), BTC as satoshis (1 BTC = 100,000,000 sats).
 * No floating point is ever used for balances, costs, fees or P/L.
 */
object Money {
    const val SATS_PER_BTC = 100_000_000L

    /** IDR value of [sats] at [priceIdr] per BTC, rounded half-up to whole rupiah. */
    fun cost(priceIdr: Long, sats: Long): Long =
        BigDecimal(priceIdr).multiply(BigDecimal(sats))
            .divide(BigDecimal(SATS_PER_BTC), 0, RoundingMode.HALF_UP).longValueExact()

    /** Fee in IDR for [amountIdr] at [bps] basis points (51 bps = 0.51%), rounded half-up. */
    fun fee(amountIdr: Long, bps: Int): Long =
        BigDecimal(amountIdr).multiply(BigDecimal(bps))
            .divide(BigDecimal(10_000), 0, RoundingMode.HALF_UP).longValueExact()

    /**
     * Largest quantity (sats) whose cost plus fee does not exceed [budgetIdr].
     * Never spends more than the budget.
     */
    fun satsForBudget(budgetIdr: Long, priceIdr: Long, feeBps: Int): Long {
        if (budgetIdr <= 0 || priceIdr <= 0) return 0
        val net = budgetIdr * 10_000 / (10_000 + feeBps)
        var s = BigDecimal(net).multiply(BigDecimal(SATS_PER_BTC))
            .divide(BigDecimal(priceIdr), 0, RoundingMode.DOWN).longValueExact()
        while (s > 0) {
            val c = cost(priceIdr, s)
            if (c + fee(c, feeBps) <= budgetIdr) break
            s--
        }
        return s
    }

    /** Whole-rupiah value of a BigDecimal price from the API. */
    fun idr(v: BigDecimal): Long = v.setScale(0, RoundingMode.HALF_UP).longValueExact()

    fun btcString(sats: Long): String =
        BigDecimal(sats).movePointLeft(8).setScale(8, RoundingMode.DOWN).toPlainString()
}
