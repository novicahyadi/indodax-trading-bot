package com.indodaxbot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indodaxbot.api.ApiResult
import com.indodaxbot.api.Candles
import com.indodaxbot.strategy.EvalStatus
import com.indodaxbot.strategy.SignalService
import com.indodaxbot.strategy.SignalSnapshot
import com.indodaxbot.strategy.SignalType
import com.indodaxbot.strategy.StrategyMode
import com.indodaxbot.strategy.StrategyStore
import com.indodaxbot.strategy.Timeframes
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private val SigGreen = Color(0xFF1B8A4B)
private val SigRed = Color(0xFFC62828)

private const val CANDLE_CLOSE_DELAY_MS = 20_000L   // wait a little after the candle closes
private const val RETRY_MS = 60_000L

private fun num(v: Double?): String {
    if (v == null || !v.isFinite()) return "—"
    val nf = NumberFormat.getNumberInstance(Locale("id", "ID"))
    nf.maximumFractionDigits = if (abs(v) < 100) 2 else 0
    return nf.format(v)
}

private fun dateTime(ms: Long): String =
    SimpleDateFormat("dd MMM HH:mm", Locale("id", "ID")).format(Date(ms))

/**
 * Evaluates the active strategy right after every candle close while the screen is open.
 * (Continuous background evaluation arrives with the Foreground Service in Phase 5.)
 * The loop restarts when the configuration changes or when [refreshTick] changes (pull-to-refresh).
 */
@Composable
fun SignalsSection(service: SignalService, store: StrategyStore, refreshTick: Int) {
    val config by store.config.collectAsState()
    var snap by remember { mutableStateOf<SignalSnapshot?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // Timeframe the user has picked but not applied yet.
    var pendingTf by remember(config.timeframe) { mutableStateOf(config.timeframe) }

    LaunchedEffect(refreshTick, config) {
        while (true) {
            var waitMs: Long
            when (val r = service.refresh()) {
                is ApiResult.Success -> {
                    snap = r.value
                    error = null
                    val now = System.currentTimeMillis()
                    val tf = (Candles.timeframeSeconds(r.value.config.timeframe) ?: 900L) * 1000L
                    waitMs = (now / tf + 1) * tf + CANDLE_CLOSE_DELAY_MS - now
                }
                is ApiResult.Failure -> {
                    error = "${r.kind}: ${r.message}"
                    waitMs = RETRY_MS
                }
            }
            delay(waitMs)
        }
    }

    // Show the snapshot only if it belongs to the applied configuration (avoids stale numbers after Apply).
    val s = snap?.takeIf { it.config == config }
    val eval = s?.evaluation
    val type = eval?.type ?: SignalType.HOLD
    val sigColor = when (type) {
        SignalType.BUY -> SigGreen
        SignalType.SELL -> SigRed
        SignalType.HOLD -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("MACD Signal", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    Timeframes.label(config.timeframe),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                )
            }

            // Timeframe picker + Apply
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Timeframes.all.forEach { tf ->
                    FilterChip(
                        selected = pendingTf == tf,
                        onClick = { pendingTf = tf },
                        label = { Text(Timeframes.label(tf)) }
                    )
                }
            }
            Button(
                enabled = pendingTf != config.timeframe,
                onClick = { store.applyTimeframe(pendingTf) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (pendingTf != config.timeframe) "Apply ${Timeframes.label(pendingTf)}" else "Applied")
            }

            Text(
                if (eval == null) "…" else type.name,
                color = sigColor,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(sigColor.copy(alpha = 0.12f))
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            )

            Row(Modifier.fillMaxWidth()) {
                SigValue("MACD", num(eval?.latest?.macd), Modifier.weight(1f))
                SigValue("Signal line", num(eval?.latest?.signal), Modifier.weight(1f))
                SigValue(
                    "Histogram", num(eval?.latest?.histogram), Modifier.weight(1f),
                    color = eval?.latest?.histogram?.let { if (it < 0) SigRed else SigGreen }
                )
            }

            if (s != null && eval != null && eval.points.isNotEmpty()) {
                MacdChart(s.chartCandles, eval.points, config, Modifier.fillMaxWidth())
            } else if (eval == null) {
                Text("Loading chart…", style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider()

            Text(
                "Strategy: " + when (config.mode) {
                    StrategyMode.ZONE_REVERSAL -> "signal-line reversal in zones"
                    StrategyMode.CROSSOVER -> "MACD / signal crossover"
                } + "  ·  MACD ${config.macd.fast}, ${config.macd.slow}, ${config.macd.signal}",
                style = MaterialTheme.typography.bodySmall
            )
            if (config.mode == StrategyMode.ZONE_REVERSAL) {
                Text(
                    if (config.zonesSet) "BUY zone ≤ ${num(config.buyZone)}   ·   SELL zone ≥ ${num(config.sellZone)}"
                    else "Zones not set for ${Timeframes.label(config.timeframe)} (Settings)",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (config.zonesSet) MaterialTheme.colorScheme.onSurfaceVariant else SigRed
                )
            }

            val last = s?.lastSignal
            Text(
                if (last != null) "Last signal: ${last.type} · ${dateTime(last.candleTimeSec * 1000)}"
                else "No BUY/SELL signal recorded yet",
                style = MaterialTheme.typography.bodyMedium
            )
            eval?.latest?.let {
                val tfSec = Candles.timeframeSeconds(config.timeframe) ?: 900L
                Text(
                    "Based on candle closed ${dateTime((it.timeSec + tfSec) * 1000)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (eval != null && eval.status != EvalStatus.OK) {
                Text(
                    "No signal: ${eval.note ?: eval.status.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = SigRed
                )
            } else if (eval?.note != null) {
                Text(eval.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error?.let {
                Text("Last refresh failed ($it)", style = MaterialTheme.typography.bodySmall, color = SigRed)
            }
        }
    }
}

@Composable
private fun SigValue(label: String, value: String, modifier: Modifier, color: Color? = null) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            color = color ?: MaterialTheme.colorScheme.onSurface
        )
    }
}
