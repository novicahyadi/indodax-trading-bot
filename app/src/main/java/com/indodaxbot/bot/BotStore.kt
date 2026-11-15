package com.indodaxbot.bot

import android.content.Context

/** Remembers whether the user wants the bot running, so the service can resume after being killed. */
class BotStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("bot_prefs", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) { prefs.edit().putBoolean("enabled", v).apply() }
}
