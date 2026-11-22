package com.indodaxbot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
import com.indodaxbot.strategy.SellTrigger
import com.indodaxbot.strategy.StrategyConfig
import com.indodaxbot.strategy.StrategyStore
import com.indodaxbot.strategy.StrategyValidator
import com.indodaxbot.strategy.Timeframes
import com.indodaxbot.trading.FeeRates
import com.indodaxbot.trading.PaperBroker
import com.indodaxbot.trading.PaperResult
import kotlinx.coroutines.launch

private val settingsTabs = listOf("API", "Strategy", "Execution", "Paper", "Bot")

@Composable
fun SettingsScreen(
    store: CredentialStore, client: IndodaxClient, broker: PaperBroker, strategyStore: StrategyStore
) {
    var tab by remember { mutableIntStateOf(0) }
    Column {
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
            settingsTabs.forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
            }
        }
        when (tab) {
            0 -> ApiTab(store, client)
            1 -> StrategyTab(strategyStore)
            2 -> ExecutionTab(strategyStore)
            3 -> PaperTab(broker)
            else -> BotGuideTab()
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

private val SellTint = Color(0xFF8E24AA)
private val BuyTint = Color(0xFF00897B)

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

/** Tinted, outlined box that groups related fields (used for the BUY and SELL zones). */
@Composable
private fun TintBox(tint: Color, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(tint.copy(alpha = 0.10f), shape)
            .border(1.dp, tint.copy(alpha = 0.45f), shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) { content() }
}

@Composable
private fun NoteBox(container: Color, text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .fillMaxWidth()
            .background(container, RoundedCornerShape(12.dp))
            .padding(12.dp)
    )
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
    var buyThr by remember(cfg) { mutableStateOf(fmt(cfg.buyReversalThreshold)) }
    var sellThr by remember(cfg) { mutableStateOf(fmt(cfg.sellReversalThreshold)) }
    var sellTrigger by remember(cfg) { mutableStateOf(cfg.sellTrigger) }
    var enCross by remember(cfg) { mutableStateOf(cfg.enableCrossover) }
    var angle by remember(cfg) { mutableStateOf(fmt(cfg.minCrossoverAngle)) }
    var logic by remember(cfg) { mutableStateOf(cfg.logicMode) }
    var indicatorOpen by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }

    TabColumn {
        SectionCard {
            Text("Timeframe", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Timeframes.all.forEach { t ->
                    FilterChip(selected = tf == t, onClick = { tf = t }, label = { Text(Timeframes.label(t)) })
                }
            }
        }

        SectionCard {
            Row(
                Modifier.fillMaxWidth().clickable { indicatorOpen = !indicatorOpen },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Indicator Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Customized MACD with normalization to 0-100",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(if (indicatorOpen) "▴" else "▾", style = MaterialTheme.typography.titleLarge)
            }
            if (indicatorOpen) {
                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IntField("Fast EMA", fast, { fast = it }, Modifier.weight(1f))
                    IntField("Slow EMA", slow, { slow = it }, Modifier.weight(1f))
                    IntField("Signal", sig, { sig = it }, Modifier.weight(1f))
                }
                IntField("Normalization lookback (candles)", lookback, { lookback = it }, Modifier.fillMaxWidth())
                Text(
                    "The indicator is scaled to 0-100 over the last N candles, so the zones work for any coin.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SectionCard {
            Text("Strategies", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

            SwitchRow("Enable reversal signal in zone strategy", enRev) { enRev = it }
            if (enRev) {
                HorizontalDivider()
                Text("SELL Strategy", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                TintBox(SellTint) {
                    Text("Zona SELL (0-100)", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DecField("Sell min", sellMin, { sellMin = it }, Modifier.weight(1f))
                        DecField("Sell max", sellMax, { sellMax = it }, Modifier.weight(1f))
                    }
                }
                Text("Sell trigger", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = sellTrigger == SellTrigger.ONCE_TOUCHING,
                        onClick = { sellTrigger = SellTrigger.ONCE_TOUCHING }, label = { Text("Once touching") }
                    )
                    FilterChip(
                        selected = sellTrigger == SellTrigger.WAIT_REVERSAL,
                        onClick = { sellTrigger = SellTrigger.WAIT_REVERSAL }, label = { Text("Waiting for reversal") }
                    )
                }
                if (sellTrigger == SellTrigger.WAIT_REVERSAL) {
                    DecField("Sell reversal threshold", sellThr, { sellThr = it }, Modifier.fillMaxWidth())
                    Text(
                        "Minimum bounce down of the signal line (0-100 scale) before a SELL.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "SELL fires on the candle where the signal line first reaches the sell zone, without waiting for a reversal.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text("BUY Strategy", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                TintBox(BuyTint) {
                    Text("Zona BUY (0-100)", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DecField("Buy min", buyMin, { buyMin = it }, Modifier.weight(1f))
                        DecField("Buy max", buyMax, { buyMax = it }, Modifier.weight(1f))
                    }
                }
                DecField("Buy reversal threshold", buyThr, { buyThr = it }, Modifier.fillMaxWidth())
                Text(
                    "Minimum bounce up of the signal line (0-100 scale) before a BUY.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider()

            SwitchRow("Enable crossover signal strategy", enCross) { enCross = it }
            if (enCross) {
                DecField("Minimum crossover angle (0-90°)", angle, { angle = it }, Modifier.fillMaxWidth())
            }

            if (enRev && enCross) {
                HorizontalDivider()
                Text("Signal combination", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = logic == LogicMode.AND, onClick = { logic = LogicMode.AND }, label = { Text("AND") })
                    FilterChip(selected = logic == LogicMode.OR, onClick = { logic = LogicMode.OR }, label = { Text("OR") })
                }
                Text(
                    "AND: both strategies must give the same signal. OR: either one is enough (if they disagree, no signal).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (!enRev && !enCross) {
                NoteBox(
                    MaterialTheme.colorScheme.tertiaryContainer,
                    "Both strategies are OFF: the Dashboard shows the indicator only, with status OFF and no signals."
                )
            } else {
                NoteBox(MaterialTheme.colorScheme.primaryContainer, "Only one strategy is on, so it is used alone.")
            }
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
                val bThr = if (enRev) buyThr.toDoubleOrNull() else cfg.buyReversalThreshold
                val sThr = if (enRev) sellThr.toDoubleOrNull() ?: (if (sellTrigger == SellTrigger.ONCE_TOUCHING) cfg.sellReversalThreshold else null) else cfg.sellReversalThreshold
                val ang = if (enCross) angle.toDoubleOrNull() else cfg.minCrossoverAngle
                val err = StrategyValidator.validate(f, sl, sg, lb, bMin, bMax, sMin, sMax, bThr, sThr, sellTrigger, ang, enRev, enCross)
                if (err != null) {
                    indicatorOpen = true   // the error may be about a field inside the collapsed card
                    msg = err
                } else {
                    strategyStore.saveStrategy(
                        StrategyConfig(
                            timeframe = tf, macd = MacdParams(f!!, sl!!, sg!!), normLookback = lb!!,
                            buyZoneMin = bMin!!, buyZoneMax = bMax!!, sellZoneMin = sMin!!, sellZoneMax = sMax!!,
                            enableReversal = enRev, buyReversalThreshold = bThr!!,
                            sellTrigger = sellTrigger, sellReversalThreshold = sThr ?: cfg.sellReversalThreshold,
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
    var orderSize by remember(ex) { mutableStateOf(ex.orderSizeIdr.toString()) }
    var useMinMax by remember(ex) { mutableStateOf(ex.takerUseMinMax) }
    var minTp by remember(ex) { mutableStateOf(fmt(ex.takerMinTakeProfitPct)) }
    var maxSl by remember(ex) { mutableStateOf(fmt(ex.takerMaxStopLossPct)) }
    val fees by strategyStore.fees.collectAsState()
    var fMakerBuy by remember(fees) { mutableStateOf(fmt(fees.makerBuyBps / 100.0)) }
    var fMakerSell by remember(fees) { mutableStateOf(fmt(fees.makerSellBps / 100.0)) }
    var fTakerBuy by remember(fees) { mutableStateOf(fmt(fees.takerBuyBps / 100.0)) }
    var fTakerSell by remember(fees) { mutableStateOf(fmt(fees.takerSellBps / 100.0)) }
    var msg by remember { mutableStateOf("") }

    TabColumn {
        Text("Execution mode", style = MaterialTheme.typography.titleLarge)
        Text(
            "Used by the bot in PAPER mode (simulated orders). Live orders are not enabled yet.",
            style = MaterialTheme.typography.bodySmall
        )
        SectionCard {
            Text("Trade Sizing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            IntField("Order size per BUY (IDR)", orderSize, { orderSize = it }, Modifier.fillMaxWidth())
        }

        SectionCard {
            Text("Mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
                Text("Market maker (limit orders)", style = MaterialTheme.typography.bodyMedium)
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
                Text("Market taker (market orders)", style = MaterialTheme.typography.bodyMedium)
                SwitchRow("Use stop loss and take profit", useSlTp) {
                    useSlTp = it
                    if (it) useMinMax = false     // the two options exclude each other
                }
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
                HorizontalDivider()
                SwitchRow("Minimum take profit and maximum stop loss", useMinMax) {
                    useMinMax = it
                    if (it) useSlTp = false       // the two options exclude each other
                }
                Text(
                    "Trades still follow the signals. A SELL signal is only obeyed when the position is at least the minimum take profit in profit, or at least the maximum stop loss in loss. In between, the SELL signal is ignored and the position is held. P/L is measured after fees, as on the Positions screen. Cannot be combined with the option above.",
                    style = MaterialTheme.typography.bodySmall
                )
                if (useMinMax) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DecField("Minimum take profit %", minTp, { minTp = it }, Modifier.weight(1f))
                        DecField("Maximum stop loss %", maxSl, { maxSl = it }, Modifier.weight(1f))
                    }
                }
            }
        }

        SectionCard {
            Text("Trading fees (%)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Used for paper trading now and for live P/L estimates later. Check your real rates in your Indodax trade history.",
                style = MaterialTheme.typography.bodySmall
            )
            Text("Buy", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DecField("Maker (limit)", fMakerBuy, { fMakerBuy = it }, Modifier.weight(1f))
                DecField("Taker (market)", fTakerBuy, { fTakerBuy = it }, Modifier.weight(1f))
            }
            Text("Sell", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DecField("Maker (limit)", fMakerSell, { fMakerSell = it }, Modifier.weight(1f))
                DecField("Taker (market)", fTakerSell, { fTakerSell = it }, Modifier.weight(1f))
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
                val size = orderSize.toLongOrNull()
                val mm = if (useMinMax) minTp.toDoubleOrNull() else ex.takerMinTakeProfitPct
                val xs = if (useMinMax) maxSl.toDoubleOrNull() else ex.takerMaxStopLossPct
                val fmb = fMakerBuy.toDoubleOrNull()
                val fms = fMakerSell.toDoubleOrNull()
                val ftb = fTakerBuy.toDoubleOrNull()
                val fts = fTakerSell.toDoubleOrNull()
                val err = StrategyValidator.validateExecution(
                    c, ms, mt, a, b, d, useSlTp, ts, tt, size, useMinMax, mm, xs
                ) ?: StrategyValidator.validateFees(fmb, fms, ftb, fts)
                if (err != null) {
                    msg = err
                } else {
                    strategyStore.saveExecution(
                        ExecutionConfig(
                            mode = mode, makerChaseCancelPct = c!!, makerStopLossPct = ms!!, makerTakeProfitPct = mt!!,
                            slLevel1 = a!!, slLevel2 = b!!, slLevel3 = d!!,
                            takerUseSlTp = useSlTp, takerStopLossPct = ts ?: ex.takerStopLossPct,
                            takerTakeProfitPct = tt ?: ex.takerTakeProfitPct,
                            orderSizeIdr = size!!,
                            takerUseMinMax = useMinMax, takerMinTakeProfitPct = mm!!, takerMaxStopLossPct = xs!!
                        )
                    )
                    strategyStore.saveFees(
                        FeeRates(
                            makerBuyBps = Math.round(fmb!! * 100).toInt(), makerSellBps = Math.round(fms!! * 100).toInt(),
                            takerBuyBps = Math.round(ftb!! * 100).toInt(), takerSellBps = Math.round(fts!! * 100).toInt()
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

private fun tryStart(ctx: Context, intent: Intent, fallback: Intent) {
    try {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        try { ctx.startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e2: Exception) { /* nothing else to open */ }
    }
}

@Composable
private fun BotGuideTab() {
    val ctx = LocalContext.current
    val appInfo = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
    TabColumn {
        Text("Keep the bot alive (Xiaomi / MIUI)", style = MaterialTheme.typography.titleLarge)
        Text(
            "MIUI closes background apps aggressively. Without these settings the bot may stop while the screen is off, and open positions are then NOT watched.",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            "1. Autostart: allow this app.\n" +
                "2. Battery saver for this app: choose \"No restrictions\".\n" +
                "3. Recent apps: long-press the app card and tap the lock icon so it is not cleared.\n" +
                "4. Notifications: allow them, so you can see the bot status and the Stop button.\n" +
                "5. Do not use \"Force stop\" or swipe-away cleaners.",
            style = MaterialTheme.typography.bodyMedium
        )
        GuideButton("Open Autostart settings") {
            tryStart(
                ctx,
                Intent().setComponent(
                    ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                ),
                appInfo
            )
        }
        GuideButton("Open battery optimization settings") {
            tryStart(ctx, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), appInfo)
        }
        GuideButton("Open app info (battery, notifications)") { tryStart(ctx, appInfo, appInfo) }
        Text(
            "The bot runs as a foreground service with a notification. It does not restart after a phone reboot: open the app and tap Start bot again.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
