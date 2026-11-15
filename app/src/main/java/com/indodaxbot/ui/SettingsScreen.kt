package com.indodaxbot.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.indodaxbot.api.ApiResult
import com.indodaxbot.api.IndodaxClient
import com.indodaxbot.security.CredentialStore
import com.indodaxbot.strategy.ExecutionConfig
import com.indodaxbot.strategy.ExecutionMode
import com.indodaxbot.strategy.LogicMode
import com.indodaxbot.strategy.MacdParams
import com.indodaxbot.strategy.StrategyConfig
import com.indodaxbot.strategy.StrategyStore
import com.indodaxbot.strategy.StrategyValidator
import com.indodaxbot.strategy.Timeframes
import com.indodaxbot.trading.PaperBroker
import com.indodaxbot.trading.PaperResult
import kotlinx.coroutines.launch

private val settingsTabs = listOf("API", "Strategy", "Execution", "Paper")

@Composable
fun SettingsScreen(
    store: CredentialStore, client: IndodaxClient, broker: PaperBroker, strategyStore: StrategyStore
) {
    var tab by remember { mutableIntStateOf(0) }
    Column {
        TabRow(selectedTabIndex = tab) {
            settingsTabs.forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
            }
        }
        when (tab) {
            0 -> ApiTab(store, client)
            1 -> StrategyTab(strategyStore)
            2 -> ExecutionTab(strategyStore)
            else -> PaperTab(broker)
        }
    }
}

@Composable
private fun TabColumn(content: @Composable () -> Unit) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) { content() }
}

@Composable
private fun IntField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value, onValueChange = { onChange(it.filter(Char::isDigit)) },
        label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}

@Composable
private fun DecField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value, onValueChange = { onChange(it.filter { c -> c.isDigit() || c == '.' }) },
        label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun fmt(v: Double): String = if (v == Math.rint(v) && kotlin.math.abs(v) < 1e12) v.toLong().toString() else v.toString()

@Composable
private fun ApiTab(store: CredentialStore, client: IndodaxClient) {
    var apiKey by remember { mutableStateOf("") }
    var apiSecret by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(store.hasCredentials()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    TabColumn {
        Text("Indodax API", style = MaterialTheme.typography.titleLarge)
        Text(
            if (saved) "Credentials saved (encrypted)." else "No credentials saved.",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            "Use a key with view + trade permission only. Never enable withdrawal.",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = apiKey, onValueChange = { apiKey = it },
            label = { Text("API Key") }, singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = apiSecret, onValueChange = { apiSecret = it },
            label = { Text("API Secret") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            enabled = apiKey.isNotBlank() && apiSecret.isNotBlank() && !busy,
            onClick = {
                store.save(apiKey, apiSecret)
                apiKey = ""
                apiSecret = ""
                saved = true
                status = "Saved."
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save credentials") }

        OutlinedButton(
            enabled = saved && !busy,
            onClick = {
                busy = true
                status = "Testing..."
                scope.launch {
                    status = when (val r = client.testConnection()) {
                        is ApiResult.Success -> {
                            val v = r.value
                            buildString {
                                append("Connected. Trade: ${if (v.canTrade) "yes" else "NO"}. ")
                                append("IDR free: ${v.idrFree.toPlainString()}, BTC free: ${v.btcFree.toPlainString()}.")
                                if (v.canWithdraw) {
                                    append("\nWARNING: this key also has withdraw permission. Create a new key without it.")
                                }
                            }
                        }
                        is ApiResult.Failure -> "Failed (${r.kind}): ${r.message}" +
                            (r.apiCode?.let { " [code $it]" } ?: "")
                    }
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Test API connection") }

        OutlinedButton(
            enabled = saved && !busy,
            onClick = { store.clear(); saved = false; status = "Credentials removed." },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Remove credentials") }

        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StrategyTab(strategyStore: StrategyStore) {
    val cfg by strategyStore.config.collectAsState()
    var tf by remember(cfg) { mutableStateOf(cfg.timeframe) }
    var fast by remember(cfg) { mutableStateOf(cfg.macd.fast.toString()) }
    var slow by remember(cfg) { mutableStateOf(cfg.macd.slow.toString()) }
    var sig by remember(cfg) { mutableStateOf(cfg.macd.signal.toString()) }
    var lookback by remember(cfg) { mutableStateOf(cfg.normLookback.toString()) }
    var sellMin by remember(cfg) { mutableStateOf(fmt(cfg.sellZoneMin)) }
    var sellMax by remember(cfg) { mutableStateOf(fmt(cfg.sellZoneMax)) }
    var buyMin by remember(cfg) { mutableStateOf(fmt(cfg.buyZoneMin)) }
    var buyMax by remember(cfg) { mutableStateOf(fmt(cfg.buyZoneMax)) }
    var enRev by remember(cfg) { mutableStateOf(cfg.enableReversal) }
    var revThr by remember(cfg) { mutableStateOf(fmt(cfg.reversalThreshold)) }
    var enCross by remember(cfg) { mutableStateOf(cfg.enableCrossover) }
    var angle by remember(cfg) { mutableStateOf(fmt(cfg.minCrossoverAngle)) }
    var logic by remember(cfg) { mutableStateOf(cfg.logicMode) }
    var msg by remember { mutableStateOf("") }

    TabColumn {
        Text("Timeframe", style = MaterialTheme.typography.titleLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Timeframes.all.forEach { t ->
                FilterChip(selected = tf == t, onClick = { tf = t }, label = { Text(Timeframes.label(t)) })
            }
        }

        HorizontalDivider()
        Text("MACD and normalization", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IntField("Fast EMA", fast, { fast = it }, Modifier.weight(1f))
            IntField("Slow EMA", slow, { slow = it }, Modifier.weight(1f))
            IntField("Signal", sig, { sig = it }, Modifier.weight(1f))
        }
        IntField("Normalization lookback (candles)", lookback, { lookback = it }, Modifier.fillMaxWidth())
        Text(
            "The indicator is scaled to 0-100 over the last N candles, so the zones work for any coin.",
            style = MaterialTheme.typography.bodySmall
        )

        HorizontalDivider()
        Text("Strategy 1: Reversal zone", style = MaterialTheme.typography.titleMedium)
        SwitchRow("Enable reversal signal", enRev) { enRev = it }
        if (enRev) {
            Text("Zona SELL (0-100)", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DecField("Sell min", sellMin, { sellMin = it }, Modifier.weight(1f))
                DecField("Sell max", sellMax, { sellMax = it }, Modifier.weight(1f))
            }
            Text("Zona BUY (0-100)", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DecField("Buy min", buyMin, { buyMin = it }, Modifier.weight(1f))
                DecField("Buy max", buyMax, { buyMax = it }, Modifier.weight(1f))
            }
            DecField("Reversal threshold", revThr, { revThr = it }, Modifier.fillMaxWidth())
            Text(
                "Minimum bounce of the signal line (0-100 scale) after touching a zone.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        Text("Strategy 2: Crossover angle", style = MaterialTheme.typography.titleMedium)
        SwitchRow("Enable crossover signal", enCross) { enCross = it }
        if (enCross) {
            DecField("Minimum crossover angle (0-90°)", angle, { angle = it }, Modifier.fillMaxWidth())
        }

        if (enRev && enCross) {
            Text("Signal combination", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = logic == LogicMode.AND, onClick = { logic = LogicMode.AND }, label = { Text("AND") })
                FilterChip(selected = logic == LogicMode.OR, onClick = { logic = LogicMode.OR }, label = { Text("OR") })
            }
            Text(
                "AND: both strategies must give the same signal. OR: either one is enough (if they disagree, no signal).",
                style = MaterialTheme.typography.bodySmall
            )
        } else if (!enRev && !enCross) {
            Text(
                "Both strategies are OFF: the Dashboard shows the indicator only, with status OFF and no signals.",
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            Text("Only one strategy is on, so it is used alone.", style = MaterialTheme.typography.bodySmall)
        }

        Button(
            onClick = {
                val f = fast.toIntOrNull()
                val sl = slow.toIntOrNull()
                val sg = sig.toIntOrNull()
                val lb = lookback.toIntOrNull()
                // Hidden (disabled) strategies keep their saved values and are not validated.
                val bMin = if (enRev) buyMin.toDoubleOrNull() else cfg.buyZoneMin
                val bMax = if (enRev) buyMax.toDoubleOrNull() else cfg.buyZoneMax
                val sMin = if (enRev) sellMin.toDoubleOrNull() else cfg.sellZoneMin
                val sMax = if (enRev) sellMax.toDoubleOrNull() else cfg.sellZoneMax
                val thr = if (enRev) revThr.toDoubleOrNull() else cfg.reversalThreshold
                val ang = if (enCross) angle.toDoubleOrNull() else cfg.minCrossoverAngle
                val err = StrategyValidator.validate(f, sl, sg, lb, bMin, bMax, sMin, sMax, thr, ang, enRev, enCross)
                if (err != null) {
                    msg = err
                } else {
                    strategyStore.saveStrategy(
                        StrategyConfig(
                            timeframe = tf, macd = MacdParams(f!!, sl!!, sg!!), normLookback = lb!!,
                            buyZoneMin = bMin!!, buyZoneMax = bMax!!, sellZoneMin = sMin!!, sellZoneMax = sMax!!,
                            enableReversal = enRev, reversalThreshold = thr!!,
                            enableCrossover = enCross, minCrossoverAngle = ang!!, logicMode = logic
                        )
                    )
                    msg = "Saved and applied to the Dashboard."
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save strategy settings") }
        if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ExecutionTab(strategyStore: StrategyStore) {
    val ex by strategyStore.execution.collectAsState()
    var mode by remember(ex) { mutableStateOf(ex.mode) }
    var chase by remember(ex) { mutableStateOf(fmt(ex.makerChaseCancelPct)) }
    var mSl by remember(ex) { mutableStateOf(fmt(ex.makerStopLossPct)) }
    var mTp by remember(ex) { mutableStateOf(fmt(ex.makerTakeProfitPct)) }
    var l1 by remember(ex) { mutableStateOf(ex.slLevel1.toString()) }
    var l2 by remember(ex) { mutableStateOf(ex.slLevel2.toString()) }
    var l3 by remember(ex) { mutableStateOf(ex.slLevel3.toString()) }
    var useSlTp by remember(ex) { mutableStateOf(ex.takerUseSlTp) }
    var tSl by remember(ex) { mutableStateOf(fmt(ex.takerStopLossPct)) }
    var tTp by remember(ex) { mutableStateOf(fmt(ex.takerTakeProfitPct)) }
    var msg by remember { mutableStateOf("") }

    TabColumn {
        Text("Execution mode", style = MaterialTheme.typography.titleLarge)
        Text(
            "These settings are saved now. The bot does not place orders yet: order execution arrives in a later phase.",
            style = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == ExecutionMode.MARKET_MAKER,
                onClick = { mode = ExecutionMode.MARKET_MAKER }, label = { Text("Market maker") }
            )
            FilterChip(
                selected = mode == ExecutionMode.MARKET_TAKER,
                onClick = { mode = ExecutionMode.MARKET_TAKER }, label = { Text("Market taker") }
            )
        }

        if (mode == ExecutionMode.MARKET_MAKER) {
            Text("Market maker (limit orders)", style = MaterialTheme.typography.titleMedium)
            DecField("Chase-cancel % (price rises above the unfilled limit buy)", chase, { chase = it }, Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DecField("Stop loss %", mSl, { mSl = it }, Modifier.weight(1f))
                DecField("Take profit %", mTp, { mTp = it }, Modifier.weight(1f))
            }
            Text("Stop-loss steps (% of the order filled)", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IntField("Level 1", l1, { l1 = it }, Modifier.weight(1f))
                IntField("Level 2", l2, { l2 = it }, Modifier.weight(1f))
                IntField("Level 3", l3, { l3 = it }, Modifier.weight(1f))
            }
            Text("Level 4 is automatic at 100 % filled.", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("Market taker (market orders)", style = MaterialTheme.typography.titleMedium)
            SwitchRow("Use stop loss and take profit", useSlTp) { useSlTp = it }
            Text(
                "OFF: hold until a SELL signal, no stop loss and no take profit.",
                style = MaterialTheme.typography.bodySmall
            )
            if (useSlTp) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DecField("Stop loss %", tSl, { tSl = it }, Modifier.weight(1f))
                    DecField("Take profit %", tTp, { tTp = it }, Modifier.weight(1f))
                }
            }
        }

        Button(
            onClick = {
                // The maker fields are validated even while the taker view is open, so hidden values stay safe.
                val c = chase.toDoubleOrNull()
                val ms = mSl.toDoubleOrNull()
                val mt = mTp.toDoubleOrNull()
                val a = l1.toIntOrNull()
                val b = l2.toIntOrNull()
                val d = l3.toIntOrNull()
                val ts = tSl.toDoubleOrNull()
                val tt = tTp.toDoubleOrNull()
                val err = StrategyValidator.validateExecution(c, ms, mt, a, b, d, useSlTp, ts, tt)
                if (err != null) {
                    msg = err
                } else {
                    strategyStore.saveExecution(
                        ExecutionConfig(
                            mode = mode, makerChaseCancelPct = c!!, makerStopLossPct = ms!!, makerTakeProfitPct = mt!!,
                            slLevel1 = a!!, slLevel2 = b!!, slLevel3 = d!!,
                            takerUseSlTp = useSlTp, takerStopLossPct = ts ?: ex.takerStopLossPct,
                            takerTakeProfitPct = tt ?: ex.takerTakeProfitPct
                        )
                    )
                    msg = "Saved."
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save execution settings") }
        if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PaperTab(broker: PaperBroker) {
    var paperIdr by remember { mutableStateOf("10000000") }
    var confirmReset by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    TabColumn {
        Text("Paper trading", style = MaterialTheme.typography.titleLarge)
        Text(
            "Simulated account using real prices. It never sends real orders and is completely separate from live data.",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = paperIdr, onValueChange = { paperIdr = it.filter(Char::isDigit); confirmReset = false },
            label = { Text("Initial demo balance (IDR)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(
            enabled = (paperIdr.toLongOrNull() ?: 0L) >= 100_000L,
            onClick = {
                if (!confirmReset) {
                    confirmReset = true
                    status = "Tap again to confirm: this deletes ALL paper positions and orders."
                } else {
                    confirmReset = false
                    scope.launch {
                        status = when (val r = broker.resetAccount(paperIdr.toLong())) {
                            is PaperResult.Ok -> r.message
                            is PaperResult.Rejected -> "Rejected: ${r.reason}"
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (confirmReset) "Confirm reset" else "Reset paper account") }

        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodyMedium)
    }
}
