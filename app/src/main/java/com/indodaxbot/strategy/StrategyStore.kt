package com.indodaxbot.strategy

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Persists the strategy and execution settings (SharedPreferences, no extra dependency) and
 * publishes them as StateFlows so the Dashboard and Settings stay in sync.
 * Callers must validate with [StrategyValidator] before saving.
 */
class StrategyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("strategy_prefs", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(loadStrategy())
    private val _execution = MutableStateFlow(loadExecution())

    val config: StateFlow<StrategyConfig> = _config
    val execution: StateFlow<ExecutionConfig> = _execution
    val current: StrategyConfig get() = _config.value

    fun saveStrategy(c: StrategyConfig) {
        prefs.edit()
            .putString(K_TF, c.timeframe)
            .putInt(K_FAST, c.macd.fast).putInt(K_SLOW, c.macd.slow).putInt(K_SIGNAL, c.macd.signal)
            .putInt(K_LOOKBACK, c.normLookback)
            .putString(K_BUY_MIN, c.buyZoneMin.toString()).putString(K_BUY_MAX, c.buyZoneMax.toString())
            .putString(K_SELL_MIN, c.sellZoneMin.toString()).putString(K_SELL_MAX, c.sellZoneMax.toString())
            .putBoolean(K_EN_REV, c.enableReversal).putString(K_REV_THR, c.reversalThreshold.toString())
            .putBoolean(K_EN_CROSS, c.enableCrossover).putString(K_ANGLE, c.minCrossoverAngle.toString())
            .putString(K_LOGIC, c.logicMode.name)
            .apply()
        _config.value = c
    }

    fun saveExecution(e: ExecutionConfig) {
        prefs.edit()
            .putString(K_EXEC, e.mode.name)
            .putString(K_CHASE, e.makerChaseCancelPct.toString())
            .putString(K_M_SL, e.makerStopLossPct.toString())
            .putString(K_M_TP, e.makerTakeProfitPct.toString())
            .putInt(K_L1, e.slLevel1).putInt(K_L2, e.slLevel2).putInt(K_L3, e.slLevel3)
            .putBoolean(K_T_USE, e.takerUseSlTp)
            .putString(K_T_SL, e.takerStopLossPct.toString())
            .putString(K_T_TP, e.takerTakeProfitPct.toString())
            .putLong(K_ORDER, e.orderSizeIdr)
            .apply()
        _execution.value = e
    }

    private fun dbl(key: String, def: Double): Double =
        prefs.getString(key, null)?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: def

    private fun loadStrategy(): StrategyConfig {
        val d = StrategyConfig()
        val params = try {
            MacdParams(prefs.getInt(K_FAST, d.macd.fast), prefs.getInt(K_SLOW, d.macd.slow), prefs.getInt(K_SIGNAL, d.macd.signal))
        } catch (e: IllegalArgumentException) {
            MacdParams()
        }
        val logic = try {
            LogicMode.valueOf(prefs.getString(K_LOGIC, null) ?: d.logicMode.name)
        } catch (e: IllegalArgumentException) {
            d.logicMode
        }
        val loaded = StrategyConfig(
            timeframe = prefs.getString(K_TF, d.timeframe)?.takeIf { it in Timeframes.all } ?: d.timeframe,
            macd = params,
            normLookback = prefs.getInt(K_LOOKBACK, d.normLookback),
            buyZoneMin = dbl(K_BUY_MIN, d.buyZoneMin), buyZoneMax = dbl(K_BUY_MAX, d.buyZoneMax),
            sellZoneMin = dbl(K_SELL_MIN, d.sellZoneMin), sellZoneMax = dbl(K_SELL_MAX, d.sellZoneMax),
            enableReversal = prefs.getBoolean(K_EN_REV, d.enableReversal),
            reversalThreshold = dbl(K_REV_THR, d.reversalThreshold),
            enableCrossover = prefs.getBoolean(K_EN_CROSS, d.enableCrossover),
            minCrossoverAngle = dbl(K_ANGLE, d.minCrossoverAngle),
            logicMode = logic
        )
        // Fall back to defaults if stored values somehow fail validation.
        val err = StrategyValidator.validate(
            loaded.macd.fast, loaded.macd.slow, loaded.macd.signal, loaded.normLookback,
            loaded.buyZoneMin, loaded.buyZoneMax, loaded.sellZoneMin, loaded.sellZoneMax,
            loaded.reversalThreshold, loaded.minCrossoverAngle, loaded.enableReversal, loaded.enableCrossover
        )
        return if (err == null) loaded else d.copy(timeframe = loaded.timeframe)
    }

    private fun loadExecution(): ExecutionConfig {
        val d = ExecutionConfig()
        val mode = try {
            ExecutionMode.valueOf(prefs.getString(K_EXEC, null) ?: d.mode.name)
        } catch (e: IllegalArgumentException) {
            d.mode
        }
        val loaded = ExecutionConfig(
            mode = mode,
            makerChaseCancelPct = dbl(K_CHASE, d.makerChaseCancelPct),
            makerStopLossPct = dbl(K_M_SL, d.makerStopLossPct),
            makerTakeProfitPct = dbl(K_M_TP, d.makerTakeProfitPct),
            slLevel1 = prefs.getInt(K_L1, d.slLevel1),
            slLevel2 = prefs.getInt(K_L2, d.slLevel2),
            slLevel3 = prefs.getInt(K_L3, d.slLevel3),
            takerUseSlTp = prefs.getBoolean(K_T_USE, d.takerUseSlTp),
            takerStopLossPct = dbl(K_T_SL, d.takerStopLossPct),
            takerTakeProfitPct = dbl(K_T_TP, d.takerTakeProfitPct),
            orderSizeIdr = prefs.getLong(K_ORDER, d.orderSizeIdr)
        )
        val err = StrategyValidator.validateExecution(
            loaded.makerChaseCancelPct, loaded.makerStopLossPct, loaded.makerTakeProfitPct,
            loaded.slLevel1, loaded.slLevel2, loaded.slLevel3,
            loaded.takerUseSlTp, loaded.takerStopLossPct, loaded.takerTakeProfitPct, loaded.orderSizeIdr
        )
        return if (err == null) loaded else d
    }

    private companion object {
        const val K_TF = "tf"
        const val K_FAST = "fast"
        const val K_SLOW = "slow"
        const val K_SIGNAL = "signal"
        const val K_LOOKBACK = "norm_lookback"
        const val K_BUY_MIN = "buy_zone_min"
        const val K_BUY_MAX = "buy_zone_max"
        const val K_SELL_MIN = "sell_zone_min"
        const val K_SELL_MAX = "sell_zone_max"
        const val K_EN_REV = "enable_reversal_signal"
        const val K_REV_THR = "reversal_threshold"
        const val K_EN_CROSS = "enable_crossover_signal"
        const val K_ANGLE = "min_crossover_angle"
        const val K_LOGIC = "signal_logic_mode"
        const val K_EXEC = "execution_mode"
        const val K_CHASE = "maker_chase_cancel_pct"
        const val K_M_SL = "maker_stop_loss_pct"
        const val K_M_TP = "maker_take_profit_pct"
        const val K_L1 = "sl_trigger_level_1"
        const val K_L2 = "sl_trigger_level_2"
        const val K_L3 = "sl_trigger_level_3"
        const val K_T_USE = "taker_use_sl_tp"
        const val K_T_SL = "taker_stop_loss_pct"
        const val K_T_TP = "taker_take_profit_pct"
        const val K_ORDER = "order_size_idr"
    }
}
