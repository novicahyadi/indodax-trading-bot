package com.indodaxbot

import android.app.Application
import android.content.Context
import com.indodaxbot.api.IndodaxClient
import com.indodaxbot.bot.BotEngine
import com.indodaxbot.bot.BotService
import com.indodaxbot.bot.BotStore
import com.indodaxbot.data.AppDatabase
import com.indodaxbot.security.CredentialStore
import com.indodaxbot.strategy.SignalService
import com.indodaxbot.strategy.StrategyStore
import com.indodaxbot.trading.PaperBroker

/** Single set of shared objects, used by the UI and by the background service. */
class AppContainer(context: Context) {
    val credentialStore = CredentialStore(context)
    val client = IndodaxClient(credentials = { credentialStore.load() })
    val db = AppDatabase.get(context)
    val strategyStore = StrategyStore(context)
    val signalService = SignalService(client, db, config = { strategyStore.current })
    val broker = PaperBroker(db, fees = { strategyStore.fees.value })
    val botStore = BotStore(context)
    val engine = BotEngine(client, signalService, broker, strategyStore, db)

    fun startBot(context: Context) {
        botStore.enabled = true
        BotService.start(context)
    }

    fun stopBot(context: Context) {
        botStore.enabled = false
        engine.stop()
        BotService.stop(context)
    }
}

class BotApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
