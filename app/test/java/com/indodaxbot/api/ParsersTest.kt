package com.indodaxbot.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class ParsersTest {

    @Test
    fun ticker_parses() {
        val body = """{"ticker":{"high":"120009000","low":"116735000","last":"117136000","buy":"116938000","sell":"117136000"}}"""
        val r = Parsers.parseTicker(body) as ApiResult.Success
        assertEquals(BigDecimal("117136000"), r.value.last)
        assertEquals(BigDecimal("116938000"), r.value.buy)
    }

    @Test
    fun candles_parseAndSortByTime() {
        val body = """[
            {"Time":1699329600,"Open":2.0,"High":3.0,"Low":1.0,"Close":2.5,"Volume":"12359.00000000"},
            {"Time":1699328700,"Open":1.0,"High":1.5,"Low":0.5,"Close":1.2,"Volume":"0"}
        ]"""
        val r = Parsers.parseCandles(body) as ApiResult.Success
        assertEquals(2, r.value.size)
        assertEquals(1699328700L, r.value[0].timeSec)
        assertEquals(2.5, r.value[1].close, 0.0)
        assertEquals(12359.0, r.value[1].volume, 0.0)
        assertEquals(0.0, r.value[0].volume, 0.0)
    }

    @Test
    fun account_parsesBalancesAndPermissions() {
        val body = """{"canTrade":true,"canWithdraw":false,"accountType":"Regular","uid":1,
            "balances":[{"asset":"IDR","free":"1500000","locked":"0"},{"asset":"btc","free":"0.00120000","locked":"0.00010000"}]}"""
        val r = Parsers.parseAccount(body) as ApiResult.Success
        assertTrue(r.value.canTrade)
        assertTrue(!r.value.canWithdraw)
        assertEquals(BigDecimal("1500000"), r.value.free("IDR"))
        assertEquals(BigDecimal("0.00120000"), r.value.free("BTC"))
        assertEquals(BigDecimal.ZERO, r.value.free("ETH"))
    }

    @Test
    fun account_apiErrorIsReported() {
        val body = """{"code":-1002,"msg":"Invalid credentials."}"""
        val r = Parsers.parseAccount(body) as ApiResult.Failure
        assertEquals(Kind.API, r.kind)
        assertEquals(-1002, r.apiCode)
    }

    @Test
    fun openOrders_emptyAndFilled() {
        assertEquals(0, (Parsers.parseOpenOrders("[]") as ApiResult.Success).value.size)
        val body = """[{"symbol":"BTCIDR","orderId":6423,"clientOrderId":"c1","side":"SELL","price":"421004000",
            "stopPrice":"0","origQty":"0.02000000","executedQty":"0.00500000","status":"NEW","type":"LIMIT","time":1723442692520}]"""
        val o = (Parsers.parseOpenOrders(body) as ApiResult.Success).value.single()
        assertEquals(6423L, o.orderId)
        assertEquals(BigDecimal("0.01500000"), o.remainingQty)
        assertEquals("NEW", o.status)
    }

    @Test
    fun order_notFoundError() {
        val r = Parsers.parseOrder("""{"code":-2013,"msg":"Order not found or not yet available."}""") as ApiResult.Failure
        assertEquals(-2013, r.apiCode)
    }

    @Test
    fun garbage_isMalformed() {
        assertTrue(Parsers.parseTicker("<html>") is ApiResult.Failure)
        assertTrue(Parsers.parseCandles("nope") is ApiResult.Failure)
        assertTrue(Parsers.parseAccount("[]") is ApiResult.Failure)
    }
}
