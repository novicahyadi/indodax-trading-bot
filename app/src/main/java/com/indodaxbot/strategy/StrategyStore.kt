package com.indodaxbot.strategy

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Persists the strategy settings (SharedPreferences, no extra dependency) and publishes the active
 * configuration as a StateFlow so the Dashboard and Settings stay in sync.
 */
class StrategyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("strategy_prefs", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(load())

    val config: StateFlow<StrategyConfig> = _config
    val current: StrategyConfig get() = _config.value

    /** Switch timeframe (the zones saved for that timeframe are loaded with it). */
    fun applyTimeframe(tf: String) {
        if (tf !in Timeframes.all) return
        prefs.edit().putString(K_TF, tf).apply()
        val (b, s) = zonesFor(tf)
        _config.value = current.copy(timeframe = tf, buyZone = b, sellZone = s)
    }

    /** Save MACD periods, mode and the zones of the CURRENT timeframe. Values must be validated first. */
    fun saveSettings(params: MacdParams, mode: StrategyMode, buyZone: Double?, sellZone: Double?) {
        val tf = current.timeframe
        prefs.edit()
            .putInt(K_FAST, params.fast).putInt(K_SLOW, params.slow).putInt(K_SIGNAL, params.signal)
            .putString(K_MODE, mode.name)
            .putString(zoneKey("zb", tf), buyZone?.toString() ?: "")   // "" = explicitly unset
            .putString(zoneKey("zs", tf), sellZone?.toString() ?: "")
            .apply()
        _config.value = StrategyConfig(tf, params, mode, buyZone, sellZone)
    }

    private fun zoneKey(prefix: String, tf: String) = "${prefix}_$tf"

    private fun zonesFor(tf: String): Pair<Double?, Double?> {
        val b = prefs.getString(zoneKey("zb", tf), null)
        val s = prefs.getString(zoneKey("zs", tf), null)
        if (b == null && s == null) {
            // Placeholder defaults for the 15m chart only; every other timeframe must be set by the user.
            return if (tf == "15") DEFAULT_BUY_15 to DEFAULT_SELL_15 else null to null
        }
        return b?.toDoubleOrNull() to s?.toDoubleOrNull()
    }

    private fun load(): StrategyConfig {
        val tf = prefs.getString(K_TF, "15")?.takeIf { it in Timeframes.all } ?: "15"
        val params = try {
            MacdParams(prefs.getInt(K_FAST, 12), prefs.getInt(K_SLOW, 26), prefs.getInt(K_SIGNAL, 9))
        } catch (e: IllegalArgumentException) {
            MacdParams()
        }
        val mode = try {
            StrategyMode.valueOf(prefs.getString(K_MODE, null) ?: StrategyMode.ZONE_REVERSAL.name)
        } catch (e: IllegalArgumentException) {
            StrategyMode.ZONE_REVERSAL
        }
        val (b, s) = zonesFor(tf)
        return StrategyConfig(tf, params, mode, b, s)
    }

    private companion object {
        const val K_TF = "tf"
        const val K_FAST = "fast"
        const val K_SLOW = "slow"
        const val K_SIGNAL = "signal"
        const val K_MODE = "mode"
        const val DEFAULT_BUY_15 = -1_000_000.0
        const val DEFAULT_SELL_15 = 1_000_000.0
    }
}
