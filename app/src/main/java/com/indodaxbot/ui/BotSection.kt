package com.indodaxbot.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indodaxbot.AppContainer
import com.indodaxbot.trading.Money
import com.indodaxbot.trading.PortfolioCalc
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private val BotGreen = Color(0xFF1B8A4B)
private val BotRed = Color(0xFFC62828)

private fun rp(v: Long, signed: Boolean = false): String {
    val nf = NumberFormat.getIntegerInstance(Locale("id", "ID"))
    val sign = if (signed) (if (v > 0) "+" else if (v < 0) "-" else "") else if (v < 0) "-" else ""
    return sign + "Rp" + nf.format(abs(v))
}

private fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

@Composable
fun BotCard(c: AppContainer) {
    val ctx = LocalContext.current
    val status by c.engine.status.collectAsState()
    val cfg by c.strategyStore.config.collectAsState()
    val eventFlow = remember { c.db.botEventDao().observeLatest(6) }
    val events by eventFlow.collectAsState(initial = emptyList())
    // The service still runs if the notification permission is denied, so start either way.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { c.startBot(ctx) }
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Trading Bot", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Badge("PAPER", MaterialTheme.colorScheme.onSurfaceVariant)
                    Badge(if (status.running) "RUNNING" else "STOPPED", if (status.running) BotGreen else BotRed)
                }
            }
            Text(status.message, style = MaterialTheme.typography.bodyMedium)
            status.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = BotRed) }
            if (!cfg.anyEnabled) {
                Text(
                    "Both strategies are OFF: the bot will not open trades.",
                    style = MaterialTheme.typography.bodySmall, color = BotRed
                )
            }
            Button(
                onClick = {
                    if (status.running) {
                        c.stopBot(ctx)
                    } else if (Build.VERSION.SDK_INT >= 33 &&
                        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        c.startBot(ctx)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (status.running) "Stop bot" else "Start bot") }
            Text(
                "Paper mode: orders are simulated with real prices. Stopping the bot does not close open positions, and they are not watched while it is stopped.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (events.isNotEmpty()) {
                HorizontalDivider()
                Text("Recent activity", style = MaterialTheme.typography.labelLarge)
                events.forEach { e ->
                    Text(
                        "${fmt.format(Date(e.timestampMs))}  ${e.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (e.level == "INFO") MaterialTheme.colorScheme.onSurfaceVariant else BotRed
                    )
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 3.dp)
    )
}

@Composable
fun PortfolioCard(c: AppContainer, bid: Long?) {
    val balFlow = remember { c.broker.balance() }
    val posFlow = remember { c.broker.positions() }
    val balance by balFlow.collectAsState(initial = null)
    val positions by posFlow.collectAsState(initial = emptyList())
    val s = remember(balance, positions, bid) {
        PortfolioCalc.compute(balance, positions, bid, System.currentTimeMillis(), startOfToday(), c.broker.feeRates.takerBps)
    }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Paper Portfolio", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Badge("PAPER", MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(rp(s.equityIdr), fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(
                "Total P/L ${rp(s.totalPnlIdr, signed = true)} vs start ${rp(s.initialIdr)}",
                style = MaterialTheme.typography.bodyMedium,
                color = pnlColor(s.totalPnlIdr)
            )
            HorizontalDivider()
            Row(Modifier.fillMaxWidth()) {
                Stat("Today", rp(s.realizedTodayIdr, true), Modifier.weight(1f), pnlColor(s.realizedTodayIdr))
                Stat("Last 24h", rp(s.realizedLast24hIdr, true), Modifier.weight(1f), pnlColor(s.realizedLast24hIdr))
                Stat("All time", rp(s.realizedAllTimeIdr, true), Modifier.weight(1f), pnlColor(s.realizedAllTimeIdr))
            }
            Text("Realized from closed trades", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth()) {
                Stat("Unrealized", rp(s.unrealizedIdr, true), Modifier.weight(1f), pnlColor(s.unrealizedIdr))
                Stat(
                    "Closed trades",
                    s.closedTrades.toString() + (s.winRatePct?.let { " · ${"%.0f".format(it)}% win" } ?: ""),
                    Modifier.weight(1f)
                )
            }
            Row(Modifier.fillMaxWidth()) {
                Stat("IDR", rp(s.idrBalance), Modifier.weight(1f))
                Stat("BTC", Money.btcString(s.btcSats), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun pnlColor(v: Long): Color =
    if (v > 0) BotGreen else if (v < 0) BotRed else MaterialTheme.colorScheme.onSurface

@Composable
private fun Stat(label: String, value: String, modifier: Modifier, color: Color? = null) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, color = color ?: MaterialTheme.colorScheme.onSurface)
    }
}

/** Used by the Settings > Bot tab. */
@Composable
fun GuideButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
}
