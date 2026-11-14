package com.indodaxbot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.indodaxbot.api.IndodaxClient
import com.indodaxbot.data.AppDatabase
import com.indodaxbot.security.CredentialStore
import com.indodaxbot.strategy.SignalService
import com.indodaxbot.strategy.StrategyStore
import com.indodaxbot.trading.PaperBroker
import com.indodaxbot.ui.DashboardScreen
import com.indodaxbot.ui.PositionsScreen
import com.indodaxbot.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = CredentialStore(applicationContext)
        val client = IndodaxClient(credentials = { store.load() })
        val db = AppDatabase.get(applicationContext)
        val strategyStore = StrategyStore(applicationContext)
        val signalService = SignalService(client, db, config = { strategyStore.current })
        val broker = PaperBroker(db)
        setContent { MaterialTheme { AppRoot(store, client, signalService, broker, strategyStore) } }
    }
}

private val tabs = listOf("Dashboard", "Positions", "Settings")

@Composable
private fun AppRoot(
    store: CredentialStore, client: IndodaxClient, signalService: SignalService, broker: PaperBroker,
    strategyStore: StrategyStore
) {
    var selected by remember { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, title ->
                    NavigationBarItem(
                        selected = selected == i,
                        onClick = { selected = i },
                        icon = {},
                        label = { Text(title) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (selected) {
                0 -> DashboardScreen(client, signalService, strategyStore)
                1 -> PositionsScreen(client, broker)
                else -> SettingsScreen(store, client, broker, strategyStore)
            }
        }
    }
}
