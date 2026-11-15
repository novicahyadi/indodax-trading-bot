package com.indodaxbot.bot

import com.indodaxbot.api.ApiResult
import com.indodaxbot.api.IndodaxClient
import com.indodaxbot.data.AppDatabase
import com.indodaxbot.data.BotEvent
import com.indodaxbot.api.Candles
import com.indodaxbot.strategy.SignalEvent
import com.indodaxbot.strategy.SignalService
import com.indodaxbot.strategy.StrategyStore
import com.indodaxbot.trading.Action
import com.indodaxbot.trading.CycleView
import com.indodaxbot.trading.ExecutionPlanner
import com.indodaxbot.trading.Market
import com.indodaxbot.trading.Money
import com.indodaxbot.trading.OrderStatus
import com.indodaxbot.trading.PaperBroker
import com.indodaxbot.trading.PaperResult
import com.indodaxbot.trading.SignalCandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class BotStatus(
    val running: Boolean = false,
    val message: String = "Stopped",
    val lastTickMs: Long = 0L,
    val error: String? = null
)

/**
 * PAPER-mode trading loop. It (1) evaluates the strategy right after each candle close, (2) acts on
 * actionable signals, and (3) watches the price every few seconds to fill simulated limit orders and to
 * run chase-cancel, take profit and the synthetic stop loss. Live orders are not implemented.
 */
class BotEngine(
    private val client: IndodaxClient,
    private val signals: SignalService,
    private val broker: PaperBroker,
    private val strategyStore: StrategyStore,
    private val db: AppDatabase,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private val _status = MutableStateFlow(BotStatus())
    private var jobs: List<Job> = emptyList()
    private var lastErrorText: String? = null

    val status: StateFlow<BotStatus> = _status

    @Synchronized
    fun start() {
        if (_status.value.running) return
        _status.value = BotStatus(running = true, message = "Starting")
        lastErrorText = null
        // The event collector is launched first and undispatched, so it is subscribed before any refresh runs.
        jobs = listOf(
            scope.launch(start = CoroutineStart.UNDISPATCHED) { eventLoop() },
            scope.launch { signalLoop() },
            scope.launch { tickLoop() }
        )
        scope.launch {
            broker.ensureAccount()
            log("INFO", "Bot started (PAPER mode, simulated orders only)")
        }
    }

    @Synchronized
    fun stop() {
        if (!_status.value.running) return
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        _status.value = BotStatus(running = false, message = "Stopped")
        scope.launch { log("INFO", "Bot stopped. Open positions are NOT closed and are no longer monitored.") }
    }

    // ------------------------------------------------------------------ loops

    /** Refreshes the signal shortly after every candle close (and when the settings change). */
    private suspend fun signalLoop() {
        var lastBoundary = -1L
        var lastKey = ""
        var lastAttempt = 0L
        while (true) {
            try {
                val cfg = strategyStore.current
                val tfMs = (Candles.timeframeSeconds(cfg.timeframe) ?: 900L) * 1000L
                val now = clock()
                val boundary = now / tfMs
                val changed = boundary != lastBoundary || cfg.key() != lastKey
                val graceOver = now - boundary * tfMs >= SIGNAL_GRACE_MS
                val retryOk = now - lastAttempt >= RETRY_MS
                if (changed && graceOver && retryOk) {
                    lastAttempt = now
                    when (val r = signals.refresh()) {
                        is ApiResult.Success -> { lastBoundary = boundary; lastKey = cfg.key() }
                        is ApiResult.Failure -> reportError("Signal refresh failed: ${r.kind}: ${r.message}")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportError("Signal loop error: ${e.message}")
            }
            delay(LOOP_MS)
        }
    }

    private suspend fun eventLoop() {
        signals.events.collect { ev ->
            try {
                handleSignal(ev)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportError("Signal handling error: ${e.message}")
            }
        }
    }

    private suspend fun tickLoop() {
        while (true) {
            try {
                val m = fetchMarket()
                if (m == null) {
                    reportError("Price unavailable, retrying")
                } else {
                    var text = ""
                    lock.withLock {
                        broker.onTick(m.ask, m.bid, PAPER_PARTIAL_PCT)
                        val cycle = buildCycle(m.bid)
                        val actions = ExecutionPlanner.onTick(cycle, m, strategyStore.execution.value)
                        if (actions.isNotEmpty()) {
                            log("INFO", "Tick: ${actions.joinToString()}")
                            execute(actions, cycle, m)
                        }
                        text = describe(buildCycle(m.bid), m)
                    }
                    lastErrorText = null
                    _status.value = _status.value.copy(running = true, message = text, lastTickMs = clock(), error = null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportError("Tick error: ${e.message}")
            }
            delay(TICK_MS)
        }
    }

    // --------------------------------------------------------------- handling

    private suspend fun handleSignal(ev: SignalEvent) {
        val m = fetchMarket()
        if (m == null) {
            log("WARN", "Signal ${ev.type} missed: no price available")
            return
        }
        lock.withLock {
            val ex = strategyStore.execution.value
            val cycle = buildCycle(m.bid)
            val actions = ExecutionPlanner.onSignal(ev.type, cycle, SignalCandle(ev.low, ev.high), m, ex)
            log(
                "INFO",
                "Signal ${ev.type} (${ex.mode}) candle low ${ev.low} high ${ev.high}: " +
                    actions.joinToString().ifEmpty { "no action" }
            )
            execute(actions, cycle, m)
        }
    }

    private suspend fun execute(actions: List<Action>, cycle: CycleView, m: Market) {
        for (a in actions) {
            val pid = cycle.positionId
            val result: PaperResult? = when (a) {
                is Action.MarketBuy -> broker.marketBuy(a.budgetIdr, m.ask)
                is Action.LimitBuy -> broker.placeLimitBuy(a.budgetIdr, a.price, m.ask)
                is Action.CancelBuyOrder -> cycle.buyOrderId?.let { broker.cancelOrder(it) }
                is Action.CancelAllOrders -> cancelAll(pid)
                is Action.LimitSell -> pid?.let { broker.placeLimitSell(it, null, a.price, m.bid) }
                is Action.MarketSellAll -> pid?.let { broker.marketSell(it, null, m.bid) }
                is Action.Skip -> { log("INFO", a.reason); null }
            }
            when (result) {
                is PaperResult.Ok -> log("INFO", "${a.label()}: ${result.message}")
                is PaperResult.Rejected -> log("WARN", "${a.label()} rejected: ${result.reason}")
                null -> Unit
            }
        }
    }

    private suspend fun cancelAll(positionId: Long?): PaperResult? {
        if (positionId == null) return null
        var n = 0
        for (o in db.tradingDao().ordersOf(positionId)) {
            if (OrderStatus.valueOf(o.status).isActive) {
                if (broker.cancelOrder(o.clientOrderId) is PaperResult.Ok) n++
            }
        }
        return PaperResult.Ok("cancelled $n open order(s)")
    }

    /** The bot's view of the open paper position (dust below the minimum order is ignored). */
    private suspend fun buildCycle(bid: Long): CycleView {
        val dao = db.tradingDao()
        val pos = dao.openPositions(PaperBroker.MODE).firstOrNull() ?: return CycleView()
        val orders = dao.ordersOf(pos.id)
        val active = orders.filter { OrderStatus.valueOf(it.status).isActive }
        val buy = active.firstOrNull { it.side == "BUY" }
        val sell = active.firstOrNull { it.side == "SELL" }
        val held = pos.buyQtySats - pos.soldQtySats
        if (active.isEmpty() && (held <= 0 || Money.cost(bid, held) < PaperBroker.MIN_ORDER_IDR)) return CycleView()
        val avg = if (pos.buyQtySats == 0L) 0L else
            java.math.BigDecimal(pos.buyNotionalIdr).multiply(java.math.BigDecimal(Money.SATS_PER_BTC))
                .divide(java.math.BigDecimal(pos.buyQtySats), 0, java.math.RoundingMode.HALF_UP).longValueExact()
        return CycleView(
            positionId = pos.id,
            heldSats = held,
            avgEntryPrice = avg,
            buyOrderId = buy?.clientOrderId,
            buyLimitPrice = buy?.priceIdr ?: 0L,
            buyFilledPct = buy?.let { it.filledSats * 100.0 / it.qtySats } ?: 100.0,
            sellOrderActive = sell != null
        )
    }

    private fun describe(c: CycleView, m: Market): String = when {
        !c.hasPosition -> "Idle, waiting for a signal · bid ${m.bid}"
        c.sellOrderActive -> "Exiting: limit sell waiting · held ${Money.btcString(c.heldSats)} BTC"
        c.buyOrderId != null -> "Buying: ${"%.0f".format(c.buyFilledPct)}% filled at ${c.buyLimitPrice}"
        else -> "Holding ${Money.btcString(c.heldSats)} BTC · entry ${c.avgEntryPrice}"
    }

    private suspend fun fetchMarket(): Market? {
        val r = client.snapshot("btc_idr")
        if (r !is ApiResult.Success) return null
        val t = r.value.ticker
        val m = Market(last = Money.idr(t.last), ask = Money.idr(t.sell), bid = Money.idr(t.buy))
        return if (m.ask > 0 && m.bid > 0 && m.last > 0) m else null
    }

    // ---------------------------------------------------------------- logging

    private suspend fun reportError(text: String) {
        if (text != lastErrorText) {
            lastErrorText = text
            log("WARN", text)
        }
        _status.value = _status.value.copy(error = text)
    }

    private suspend fun log(level: String, message: String) {
        try {
            db.botEventDao().insert(BotEvent(timestampMs = clock(), mode = PaperBroker.MODE, level = level, message = message))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Logging must never break trading.
        }
    }

    private fun Action.label(): String = when (this) {
        is Action.MarketBuy -> "Market buy"
        is Action.LimitBuy -> "Limit buy"
        is Action.CancelBuyOrder -> "Cancel buy ($reason)"
        is Action.CancelAllOrders -> "Cancel orders"
        is Action.LimitSell -> "Limit sell"
        is Action.MarketSellAll -> "Market sell ($reason)"
        is Action.Skip -> "Skip"
    }

    companion object {
        const val TICK_MS = 15_000L
        const val LOOP_MS = 5_000L
        const val RETRY_MS = 30_000L
        const val SIGNAL_GRACE_MS = 20_000L
        /** Paper limit orders fill in two halves, so the staged stop-loss logic is exercised. */
        const val PAPER_PARTIAL_PCT = 50
    }
}
