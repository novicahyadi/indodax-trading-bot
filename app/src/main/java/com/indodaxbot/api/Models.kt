package com.indodaxbot.api

import java.math.BigDecimal

/** API key pair. toString is redacted so it can never leak into logs. */
class Credentials(val apiKey: String, val apiSecret: String) {
    override fun toString(): String = "Credentials(***)"
}

enum class Kind { NO_CREDENTIALS, NETWORK, TIMEOUT, HTTP, API, MALFORMED }

sealed class ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>()

    /**
     * kind == TIMEOUT means the outcome of the request is UNKNOWN. For order
     * placement (Phase 6) the caller must look the order up by clientOrderId
     * before retrying, never assume it failed.
     */
    data class Failure(
        val kind: Kind,
        val message: String,
        val httpStatus: Int? = null,
        val apiCode: Int? = null
    ) : ApiResult<Nothing>()
}

data class Ticker(
    val last: BigDecimal,
    val buy: BigDecimal,
    val sell: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal
)

/** timeSec is assumed to be the candle OPEN time (to be confirmed with live data). */
data class Candle(
    val timeSec: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)

data class Balance(val asset: String, val free: BigDecimal, val locked: BigDecimal)

data class AccountInfo(
    val canTrade: Boolean,
    val canWithdraw: Boolean,
    val balances: List<Balance>
) {
    fun free(asset: String): BigDecimal =
        balances.firstOrNull { it.asset.equals(asset, ignoreCase = true) }?.free ?: BigDecimal.ZERO
}

data class OrderInfo(
    val symbol: String,
    val orderId: Long,
    val clientOrderId: String,
    val side: String,
    val type: String,
    val price: BigDecimal,
    val origQty: BigDecimal,
    val executedQty: BigDecimal,
    val status: String,
    val timeMs: Long
) {
    val remainingQty: BigDecimal get() = (origQty - executedQty).max(BigDecimal.ZERO)
}

data class ConnectionReport(
    val canTrade: Boolean,
    val canWithdraw: Boolean,
    val idrFree: BigDecimal,
    val btcFree: BigDecimal
)
