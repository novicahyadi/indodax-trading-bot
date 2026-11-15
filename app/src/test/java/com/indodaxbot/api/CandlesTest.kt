package com.indodaxbot.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CandlesTest {
    private fun c(t: Long) = Candle(t, 1.0, 1.0, 1.0, 1.0, 0.0)

    @Test
    fun timeframeSeconds_knownAndUnknown() {
        assertEquals(900L, Candles.timeframeSeconds("15"))
        assertEquals(86400L, Candles.timeframeSeconds("1D"))
        assertNull(Candles.timeframeSeconds("5"))
    }

    @Test
    fun closedOnly_dropsFormingCandle() {
        val candles = listOf(c(0), c(900), c(1800))
        // now = 2000: candle@1800 closes at 2700 (still forming); candle@900 closed at 1800.
        val closed = Candles.closedOnly(candles, 900, 2000)
        assertEquals(listOf(0L, 900L), closed.map { it.timeSec })
    }

    @Test
    fun closedOnly_candleClosingExactlyNowIsClosed() {
        val closed = Candles.closedOnly(listOf(c(900)), 900, 1800)
        assertEquals(1, closed.size)
    }
}
