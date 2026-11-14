package com.indodaxbot.api

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/** Pure JSON -> model functions (no network), so they can be unit tested. */
object Parsers {

    private fun malformed(what: String) = ApiResult.Failure(Kind.MALFORMED, "Unexpected response for $what")

    private fun obj(body: String): JSONObject? = try { JSONObject(body) } catch (e: Exception) { null }
    private fun arr(body: String): JSONArray? = try { JSONArray(body) } catch (e: Exception) { null }

    /** TAPI v2 errors look like {"code":-1121,"msg":"Invalid symbol."}. */
    private fun apiError(o: JSONObject): ApiResult.Failure? =
        if (o.has("code") && o.has("msg"))
            ApiResult.Failure(Kind.API, o.optString("msg"), apiCode = o.optInt("code"))
        else null

    private fun dec(o: JSONObject, key: String): BigDecimal {
        val s = o.optString(key, "").trim()
        return s.toBigDecimalOrNull() ?: BigDecimal.ZERO
    }

    fun parseTicker(body: String): ApiResult<Ticker> {
        val t = obj(body)?.optJSONObject("ticker") ?: return malformed("ticker")
        return ApiResult.Success(
            Ticker(dec(t, "last"), dec(t, "buy"), dec(t, "sell"), dec(t, "high"), dec(t, "low"))
        )
    }

    fun parseCandles(body: String): ApiResult<List<Candle>> {
        val a = arr(body)
        if (a == null) {
            val o = obj(body)
            return if (o != null && apiError(o) != null) apiError(o)!! else malformed("candles")
        }
        val out = ArrayList<Candle>(a.length())
        for (i in 0 until a.length()) {
            val c = a.optJSONObject(i) ?: return malformed("candle")
            if (!c.has("Time")) return malformed("candle")
            out.add(
                Candle(
                    timeSec = c.optLong("Time"),
                    open = c.optDouble("Open"),
                    high = c.optDouble("High"),
                    low = c.optDouble("Low"),
                    close = c.optDouble("Close"),
                    volume = c.optDouble("Volume", 0.0)
                )
            )
        }
        return ApiResult.Success(out.sortedBy { it.timeSec })
    }

    fun parseAccount(body: String): ApiResult<AccountInfo> {
        val o = obj(body) ?: return malformed("account")
        apiError(o)?.let { return it }
        val balances = o.optJSONArray("balances") ?: return malformed("account")
        val list = ArrayList<Balance>()
        for (i in 0 until balances.length()) {
            val b = balances.optJSONObject(i) ?: continue
            list.add(Balance(b.optString("asset").uppercase(), dec(b, "free"), dec(b, "locked")))
        }
        return ApiResult.Success(
            AccountInfo(o.optBoolean("canTrade", false), o.optBoolean("canWithdraw", false), list)
        )
    }

    private fun orderFrom(o: JSONObject) = OrderInfo(
        symbol = o.optString("symbol"),
        orderId = o.optLong("orderId"),
        clientOrderId = o.optString("clientOrderId"),
        side = o.optString("side"),
        type = o.optString("type"),
        price = dec(o, "price"),
        origQty = dec(o, "origQty"),
        executedQty = dec(o, "executedQty"),
        status = o.optString("status"),
        timeMs = o.optLong("time")
    )

    fun parseOrder(body: String): ApiResult<OrderInfo> {
        val o = obj(body) ?: return malformed("order")
        apiError(o)?.let { return it }
        if (!o.has("orderId")) return malformed("order")
        return ApiResult.Success(orderFrom(o))
    }

    fun parseOpenOrders(body: String): ApiResult<List<OrderInfo>> {
        val a = arr(body)
        if (a == null) {
            val o = obj(body) ?: return malformed("openOrders")
            return apiError(o) ?: malformed("openOrders")
        }
        val out = ArrayList<OrderInfo>()
        for (i in 0 until a.length()) {
            out.add(orderFrom(a.optJSONObject(i) ?: return malformed("openOrders")))
        }
        return ApiResult.Success(out)
    }
}
