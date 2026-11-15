package com.indodaxbot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.indodaxbot.AppContainer
import com.indodaxbot.api.Candles
import com.indodaxbot.trading.Money
import com.indodaxbot.strategy.Timeframes
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indodaxbot.api.ApiResult
import com.indodaxbot.api.MarketSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val PRICE_GRACE_MS = 20_000L   // same small wait after a candle closes as the signal card

private val Green = Color(0xFF1B8A4B)
private val Red = Color(0xFFC62828)
private val BtcOrange = Color(0xFFF7931A)

private data class PriceState(
    val snapshot: MarketSnapshot? = null,
    val updatedAtMs: Long = 0L,
    val error: String? = null
)

private fun rupiah(v: BigDecimal): String =
    "Rp" + NumberFormat.getIntegerInstance(Locale("id", "ID")).format(v)

private fun percent(p: Double): String = String.format(Locale("id", "ID"), "%+.2f%%", p)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(container: AppContainer) {
    val client = container.client
    val signalService = container.signalService
    val strategyStore = container.strategyStore
    var state by remember { mutableStateOf(PriceState()) }
    var refreshing by remember { mutableStateOf(false) }
    var signalTick by remember { mutableIntStateOf(0) }
    val strategy by strategyStore.config.collectAsState()
    val scope = rememberCoroutineScope()

    suspend fun load() {
        if (refreshing) return
        refreshing = true
        state = when (val r = client.snapshot("btc_idr")) {
            is ApiResult.Success -> PriceState(r.value, System.currentTimeMillis(), null)
            is ApiResult.Failure -> state.copy(error = "${r.kind}: ${r.message}")
        }
        refreshing = false
    }

    // Load on open, then right after every candle close of the chosen timeframe (same rhythm as the signal card).
    LaunchedEffect(strategy.timeframe) {
        val tfSec = Candles.timeframeSeconds(strategy.timeframe) ?: 900L
        while (true) {
            load()
            delay(Candles.msUntilNextClose(tfSec, System.currentTimeMillis(), PRICE_GRACE_MS))
        }
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            signalTick++
            scope.launch { load() }
        },
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Dashboard", style = MaterialTheme.typography.titleLarge)
            PriceCard(state.snapshot)
            BotCard(container)
            PortfolioCard(container, state.snapshot?.ticker?.buy?.let { Money.idr(it) })
            SignalsSection(signalService, strategyStore, signalTick)

            val footer = buildString {
                if (state.updatedAtMs > 0) {
                    append("Updated ")
                    append(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(state.updatedAtMs)))
                    append("  ·  ")
                }
                append("Pull down to refresh. Auto-refresh at every ${Timeframes.label(strategy.timeframe)} candle close.")
            }
            Text(footer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.error?.let {
                Text("Last refresh failed ($it)", style = MaterialTheme.typography.bodySmall, color = Red)
            }
        }
    }
}

@Composable
private fun PriceCard(s: MarketSnapshot?) {
    val dash = "—"
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(BtcOrange),
                    contentAlignment = Alignment.Center
                ) { Text("₿", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("BTC/IDR", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Spot",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 10.dp, vertical = 3.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Bitcoin",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                s?.change24hPct?.let { ChangeChip(it) }
            }

            Text(
                s?.let { rupiah(it.ticker.last) } ?: dash,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )

            HorizontalDivider()

            Row(Modifier.fillMaxWidth()) {
                LabeledValue("24h High", s?.let { rupiah(it.ticker.high) } ?: dash, Modifier.weight(1f))
                LabeledValue("24h Low", s?.let { rupiah(it.ticker.low) } ?: dash, Modifier.weight(1f))
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TintBox("SELL (ASK)", s?.let { rupiah(it.ticker.sell) } ?: dash, Red, Modifier.weight(1f))
                TintBox("BUY (BID)", s?.let { rupiah(it.ticker.buy) } ?: dash, Green, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ChangeChip(pct: Double) {
    val color = if (pct < 0) Red else Green
    val arrow = if (pct < 0) "▼" else "▲"
    Text(
        "$arrow ${percent(pct)}",
        color = color,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable
private fun LabeledValue(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun TintBox(label: String, value: String, color: Color, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(12.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = color)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
