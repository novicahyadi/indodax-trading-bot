package com.indodaxbot.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/**
 * Read-only Indodax client for Phase 2: market data, account balances, order lookup.
 * Order placement and cancellation are intentionally NOT here yet (Phase 6).
 * Never logs request headers, keys or secrets.
 */
class IndodaxClient(
    private val credentials: () -> Credentials?,
    private val http: OkHttpClient = defaultHttp(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val privateBase: String = "https://api.indodax.com",
    private val publicBase: String = "https://indodax.com"
) {
    init {
        require(privateBase.startsWith("https://") && publicBase.startsWith("https://")) { "HTTPS only" }
    }

    private val publicGate = RateGate(minIntervalMs = 400)   // public limit: 180 req/min
    private val privateGate = RateGate(minIntervalMs = 250)  // private reads: 300 req/min

    // ---------- Public market data ----------

    suspend fun ticker(pairId: String = "btcidr"): ApiResult<Ticker> =
        getPublic("/api/ticker/$pairId").then { Parsers.parseTicker(it) }

    /** Candles for [symbol] (e.g. "BTCIDR") covering roughly the last [count] candles; may include the still-forming one. */
    suspend fun candles(symbol: String, tf: String, count: Int): ApiResult<List<Candle>> {
        val tfSec = Candles.timeframeSeconds(tf)
            ?: return ApiResult.Failure(Kind.MALFORMED, "Unsupported timeframe: $tf")
        val nowSec = clock() / 1000
        val from = nowSec - tfSec * count
        val path = "/tradingview/history_v2?symbol=$symbol&tf=$tf&from=$from&to=$nowSec"
        return getPublic(path).then { Parsers.parseCandles(it) }
    }

    // ---------- Private (signed) reads ----------

    suspend fun account(): ApiResult<AccountInfo> =
        getPrivate("/api/v2/account", linkedMapOf()).then { Parsers.parseAccount(it) }

    suspend fun openOrders(symbol: String? = null): ApiResult<List<OrderInfo>> {
        val p = linkedMapOf<String, String>()
        if (symbol != null) p["symbol"] = symbol
        return getPrivate("/api/v2/openOrders", p).then { Parsers.parseOpenOrders(it) }
    }

    /** Look up by exchange order id. */
    suspend fun order(symbol: String, orderId: Long): ApiResult<OrderInfo> =
        getPrivate("/api/v2/order", linkedMapOf("symbol" to symbol, "orderId" to orderId.toString()))
            .then { Parsers.parseOrder(it) }

    /** Look up by our own client id (used to resolve "unknown outcome" timeouts safely). */
    suspend fun orderByClientId(symbol: String, clientOrderId: String): ApiResult<OrderInfo> =
        getPrivate("/api/v2/order", linkedMapOf("symbol" to symbol, "origClientOrderId" to clientOrderId))
            .then { Parsers.parseOrder(it) }

    suspend fun testConnection(): ApiResult<ConnectionReport> =
        account().then {
            ApiResult.Success(
                ConnectionReport(it.canTrade, it.canWithdraw, it.free("IDR"), it.free("BTC"))
            )
        }

    // ---------- Plumbing ----------

    private suspend fun getPublic(path: String): ApiResult<String> {
        publicGate.await()
        val req = Request.Builder().url(publicBase + path).header("Accept", "application/json").get().build()
        return execute(req)
    }

    private suspend fun getPrivate(path: String, params: LinkedHashMap<String, String>): ApiResult<String> {
        val c = credentials() ?: return ApiResult.Failure(Kind.NO_CREDENTIALS, "API key not set")
        privateGate.await()
        params["timestamp"] = clock().toString()
        params["recvWindow"] = "5000"
        val query = Signer.buildQuery(params)
        val sign = Signer.hmacSha256Hex(c.apiSecret, query)
        val req = Request.Builder()
            .url("$privateBase$path?$query")
            .header("Accept", "application/json")
            .header("X-APIKEY", c.apiKey)
            .header("Sign", sign)
            .get()
            .build()
        return execute(req)
    }

    private suspend fun execute(req: Request): ApiResult<String> = withContext(Dispatchers.IO) {
        try {
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                // TAPI v2 sends JSON error bodies with 4xx; let the parsers surface the API code/message.
                if (resp.isSuccessful || body.trimStart().startsWith("{")) {
                    if (!resp.isSuccessful && !body.contains("\"code\"")) {
                        ApiResult.Failure(Kind.HTTP, "HTTP ${resp.code}", httpStatus = resp.code)
                    } else {
                        ApiResult.Success(body)
                    }
                } else {
                    ApiResult.Failure(Kind.HTTP, "HTTP ${resp.code}", httpStatus = resp.code)
                }
            }
        } catch (e: InterruptedIOException) {
            ApiResult.Failure(Kind.TIMEOUT, "Request timed out (outcome unknown)")
        } catch (e: IOException) {
            ApiResult.Failure(Kind.NETWORK, "Network error")
        }
    }

    companion object {
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}

/** Chains a parser onto a raw-body result, propagating failures. */
inline fun <T, R> ApiResult<T>.then(f: (T) -> ApiResult<R>): ApiResult<R> = when (this) {
    is ApiResult.Success -> f(value)
    is ApiResult.Failure -> this
}

/** Minimal spacing limiter: at most one request per [minIntervalMs]. */
class RateGate(private val minIntervalMs: Long) {
    private val mutex = Mutex()
    private var lastAt = 0L

    suspend fun await() = mutex.withLock {
        val wait = lastAt + minIntervalMs - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        lastAt = System.currentTimeMillis()
    }
}
