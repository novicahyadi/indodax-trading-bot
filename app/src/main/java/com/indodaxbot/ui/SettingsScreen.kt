package com.indodaxbot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.indodaxbot.api.ApiResult
import com.indodaxbot.api.IndodaxClient
import com.indodaxbot.security.CredentialStore
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(store: CredentialStore, client: IndodaxClient) {
    var apiKey by remember { mutableStateOf("") }
    var apiSecret by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(store.hasCredentials()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
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
