package com.indodaxbot.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class SummaryParserTest {
    private fun body(prev: String?) = """{
        "tickers":{"btc_idr":{"high":"1280929000","low":"1222233000","last":"1230107000","buy":"1230106000","sell":"1230107000"}},
        "prices_24h":{${if (prev != null) "\"btcidr\":\"$prev\"" else ""}}
    }"""

    @Test
    fun change24h_isComputedFromPrice24hAgo() {
        val r = Parsers.parseSummary(body("1254825000")) as ApiResult.Success
        assertEquals(BigDecimal("1230107000"), r.value.ticker.last)
        assertEquals(-1.97, r.value.change24hPct!!, 0.01)
    }

    @Test
    fun change24h_positive() {
        val r = Parsers.parseSummary(body("1000000000")) as ApiResult.Success
        assertEquals(23.0107, r.value.change24hPct!!, 0.001)
    }

    @Test
    fun change24h_nullWhenPriceMissing() {
        val r = Parsers.parseSummary(body(null)) as ApiResult.Success
        assertNull(r.value.change24hPct)
    }

    @Test
    fun missingPair_isMalformed() {
        assertTrue(Parsers.parseSummary("""{"tickers":{}}""") is ApiResult.Failure)
    }
}
