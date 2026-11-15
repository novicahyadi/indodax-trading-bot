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
import androidx.compose.ui.Modifier
import com.indodaxbot.ui.DashboardScreen
import com.indodaxbot.ui.PositionsScreen
import com.indodaxbot.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as BotApp).container
        setContent { MaterialTheme { AppRoot(container) } }
    }
}

private val tabs = listOf("Dashboard", "Positions", "Settings")

@Composable
private fun AppRoot(c: AppContainer) {
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
                0 -> DashboardScreen(c)
                1 -> PositionsScreen(c.client, c.broker)
                else -> SettingsScreen(c.credentialStore, c.client, c.broker, c.strategyStore)
            }
        }
    }
}
