package com.indodaxbot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.indodaxbot.api.ApiResult
import com.indodaxbot.api.IndodaxClient
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

private fun rupiah(v: java.math.BigDecimal): String =
    "Rp " + NumberFormat.getIntegerInstance(Locale("id", "ID")).format(v)

@Composable
fun DashboardScreen(client: IndodaxClient) {
    var text by remember { mutableStateOf("Loading...") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        busy = true
        text = when (val r = client.ticker("btcidr")) {
            is ApiResult.Success -> "BTC/IDR  ${rupiah(r.value.last)}\n" +
                "Buy ${rupiah(r.value.buy)}  Sell ${rupiah(r.value.sell)}\n" +
                "24h high ${rupiah(r.value.high)}  low ${rupiah(r.value.low)}"
            is ApiResult.Failure -> "Price unavailable (${r.kind}): ${r.message}"
        }
        busy = false
    }

    LaunchedEffect(Unit) { refresh() }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Dashboard", style = MaterialTheme.typography.titleLarge)
        Text(text, style = MaterialTheme.typography.bodyLarge)
        Button(
            enabled = !busy,
            onClick = { scope.launch { refresh() } },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Refresh price") }
        Text(
            "Bot controls, signals and portfolio arrive in later phases.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
