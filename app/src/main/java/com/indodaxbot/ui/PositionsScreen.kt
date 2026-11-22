package com.indodaxbot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indodaxbot.api.ApiResult
import com.indodaxbot.api.IndodaxClient
import com.indodaxbot.data.PaperBalance
import com.indodaxbot.data.Position
import com.indodaxbot.data.TradingOrder
import com.indodaxbot.trading.Money
import com.indodaxbot.trading.PaperBroker
import com.indodaxbot.trading.PaperResult
import com.indodaxbot.trading.PositionMath
import com.indodaxbot.trading.toState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PGreen = Color(0xFF1B8A4B)
private val PRed = Color(0xFFC62828)
private val PNeutral = Color(0xFF6B7280)
private val PAmber = Color(0xFFB26A00)

private const val TICK_MS = 30_000L
private const val PARTIAL_FILL_PCT = 50   // paper limit orders fill in 50% steps to simulate partial fills

private fun rp(v: Long): String =
    (if (v < 0) "-" else "") + "Rp" + NumberFormat.getIntegerInstance(Locale("id", "ID")).format(Math.abs(v))

private fun dt(ms: Long?): String =
    if (ms == null) "—" else SimpleDateFormat("dd MMM yyyy HH:mm", Locale("id", "ID")).format(Date(ms))

private fun pct(p: Double): String = String.format(Locale("id", "ID"), "%+.2f%%", p)

private enum class Filter { ALL, OPEN, CLOSED }

@Composable
fun PositionsScreen(client: IndodaxClient, broker: PaperBroker) {
    val positions by broker.positions().collectAsState(initial = emptyList())
    val orders by broker.orders().collectAsState(initial = emptyList())
    val balance by broker.balance().collectAsState(initial = null)

    var ask by remember { mutableStateOf(0L) }
    var bid by remember { mutableStateOf(0L) }
    var last by remember { mutableStateOf(0L) }
    var message by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(Filter.ALL) }
    val scope = rememberCoroutineScope()

    // While this screen is open: refresh prices and let paper limit orders fill.
    // (Continuous background operation arrives with the Foreground Service in Phase 5.)
    LaunchedEffect(Unit) {
        broker.ensureAccount()
        while (true) {
            when (val r = client.ticker("btcidr")) {
                is ApiResult.Success -> {
                    ask = Money.idr(r.value.sell)
                    bid = Money.idr(r.value.buy)
                    last = Money.idr(r.value.last)
                    broker.onTick(ask, bid, PARTIAL_FILL_PCT)
                }
                is ApiResult.Failure -> message = "Price unavailable: ${r.message}"
            }
            delay(TICK_MS)
        }
    }

    val shown = positions
        .filter {
            when (filter) {
                Filter.ALL -> true
                Filter.OPEN -> it.status == "OPEN"
                Filter.CLOSED -> it.status == "CLOSED"
            }
        }
        // OPEN first (newest first), then CLOSED (newest first)
        .sortedWith(
            compareBy<Position> { if (it.status == "OPEN") 0 else 1 }
                .thenByDescending { if (it.status == "OPEN") it.createdAtMs else (it.closedAtMs ?: it.createdAtMs) }
        )

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text("Positions", style = MaterialTheme.typography.titleLarge) }
        item {
            PaperPanel(
                balance = balance, ask = ask, bid = bid,
                onBuy = { amount ->
                    scope.launch { message = broker.marketBuy(amount, ask).text() }
                },
                onLimitBuy = { amount ->
                    scope.launch { message = broker.placeLimitBuy(amount, ask - ask * 3 / 1000, ask).text() }
                }
            )
        }
        if (message.isNotEmpty()) {
            item { Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Filter.values().forEach { f ->
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.name) })
                }
            }
        }
        if (shown.isEmpty()) {
            item { Text("No positions.", style = MaterialTheme.typography.bodyMedium) }
        }
        items(shown, key = { it.id }) { p ->
            PositionCard(
                p = p,
                orders = orders.filter { it.positionId == p.id },
                bid = bid, last = last,
                takerBps = broker.feeRates.takerSellBps,
                onMarketSell = { scope.launch { message = broker.marketSell(p.id, null, bid).text() } },
                onLimitSell = { scope.launch { message = broker.placeLimitSell(p.id, null, bid + bid * 1 / 100, bid).text() } },
                onCancel = { id -> scope.launch { message = broker.cancelOrder(id).text() } }
            )
        }
    }
}

private fun PaperResult.text(): String = when (this) {
    is PaperResult.Ok -> message
    is PaperResult.Rejected -> "Rejected: $reason"
}

@Composable
private fun PaperPanel(
    balance: PaperBalance?, ask: Long, bid: Long,
    onBuy: (Long) -> Unit, onLimitBuy: (Long) -> Unit
) {
    var amount by remember { mutableStateOf("1000000") }
    val amountIdr = amount.toLongOrNull() ?: 0L
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("PAPER account", fontWeight = FontWeight.Bold)
                Text("simulation only", style = MaterialTheme.typography.labelMedium, color = PAmber)
            }
            Text("IDR available: ${rp(balance?.availableIdr ?: 0)}")
            Text("BTC: ${Money.btcString(balance?.btcSats ?: 0)}  (locked ${Money.btcString(balance?.lockedBtcSats ?: 0)})")
            OutlinedTextField(
                value = amount, onValueChange = { amount = it.filter(Char::isDigit) },
                label = { Text("Test buy amount (IDR)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = ask > 0 && amountIdr > 0, onClick = { onBuy(amountIdr) }, modifier = Modifier.weight(1f)) {
                    Text("Market buy")
                }
                OutlinedButton(enabled = ask > 0 && amountIdr > 0, onClick = { onLimitBuy(amountIdr) }, modifier = Modifier.weight(1f)) {
                    Text("Limit buy -0.3%")
                }
            }
            Text(
                "Test controls until the bot (Phase 5) trades by itself. Prices refresh every 30 s while this screen is open.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PositionCard(
    p: Position, orders: List<TradingOrder>, bid: Long, last: Long, takerBps: Int,
    onMarketSell: () -> Unit, onLimitSell: () -> Unit, onCancel: (String) -> Unit
) {
    val s = p.toState()
    var expanded by remember(p.id) { mutableStateOf(false) }
    val pnl = if (s.isClosed) s.realizedPnlIdr else PositionMath.totalPnl(s, bid, takerBps)
    val pnlPct = PositionMath.pnlPercent(pnl, s)
    val pnlColor = if (pnl < 0) PRed else if (pnl > 0) PGreen else PNeutral

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("BTC/IDR", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(dt(p.createdAtMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (p.status == "OPEN") {
                    Badge(if (s.isFilling) "FILLING" else "HOLDING", if (s.isFilling) PAmber else PGreen)
                    Spacer(Modifier.width(6.dp))
                }
                Badge(p.status, if (p.status == "OPEN") PGreen else PNeutral)
            }

            Text(
                "${rp(pnl)}  (${pct(pnlPct)})",
                color = pnlColor, fontWeight = FontWeight.Bold, fontSize = 20.sp
            )
            if (s.buyQtySats == 0L) {
                Text("No fill yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            HorizontalDivider()
            Line("Avg entry", if (s.buyQtySats > 0) rp(s.avgEntryPrice) else "—")
            if (!s.isClosed) {
                Line("Current price", if (last > 0) rp(last) else "—")
                Line("Break-even price", PositionMath.breakEvenPrice(s, takerBps)?.let { rp(it) } ?: "—")
            }
            Line("Take profit", p.tpPrice?.let { rp(it) } ?: "—")
            Line("Stop loss", p.slPrice?.let { rp(it) } ?: "—")
            Line("Total qty", "${Money.btcString(s.targetQtySats)} BTC")
            Line("Filled qty", "${Money.btcString(s.buyQtySats)} BTC")
            Line("Remaining unfilled", "${Money.btcString(s.remainingToFillSats)} BTC")
            Line("Holding / sold", "${Money.btcString(s.holdingSats)} / ${Money.btcString(s.soldQtySats)} BTC")
            Line("Realized P/L", rp(s.realizedPnlIdr))

            if (p.status == "OPEN" && s.holdingSats > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onMarketSell, modifier = Modifier.weight(1f)) { Text("Market sell all") }
                    OutlinedButton(onClick = onLimitSell, modifier = Modifier.weight(1f)) { Text("Limit sell +1%") }
                }
            }

            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Orders (${orders.size})", fontWeight = FontWeight.SemiBold)
                Text(if (expanded) "▲" else "▼")
            }
            if (expanded) {
                orders.forEach { OrderRow(it, onCancel) }
            }
        }
    }
}

@Composable
private fun OrderRow(o: TradingOrder, onCancel: (String) -> Unit) {
    val sideColor = if (o.side == "BUY") PGreen else PRed
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${o.side} ${o.type}", color = sideColor, fontWeight = FontWeight.Bold)
            Text(o.status, style = MaterialTheme.typography.labelMedium)
        }
        Line("Order ID", o.clientOrderId.take(8))
        Line(if (o.type == "MARKET") "Price (market)" else "Price", rp(o.priceIdr))
        Line("Requested", "${Money.btcString(o.qtySats)} BTC")
        Line("Filled", "${Money.btcString(o.filledSats)} BTC")
        Line("Remaining", "${Money.btcString(o.qtySats - o.filledSats)} BTC")
        Line("Created", dt(o.createdAtMs))
        Line("Executed", dt(o.executedAtMs))
        if (o.status == "OPEN" || o.status == "PARTIALLY_FILLED") {
            TextButton(onClick = { onCancel(o.clientOrderId) }) { Text("Cancel order") }
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}
