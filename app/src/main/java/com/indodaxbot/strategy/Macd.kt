package com.indodaxbot.strategy

import com.indodaxbot.api.Candle

data class MacdParams(val fast: Int = 12, val slow: Int = 26, val signal: Int = 9) {
    init {
        require(fast > 0 && slow > fast && signal > 0) { "Invalid MACD parameters" }
    }

    /** Fewest candles that produce at least one MACD point. */
    val minCandles: Int get() = slow + signal - 1

    /**
     * History required before trusting signals. EMAs start from a simple average, so we want
     * roughly 3x the slow period of warm-up so that the seeding effect has faded.
     */
    val warmupCandles: Int get() = slow * 3 + signal
}

data class MacdPoint(
    val timeSec: Long,
    val macd: Double,
    val signal: Double,
    val histogram: Double
)

object Ema {
    /** Result is NaN before index period-1. Seeded with the simple average of the first [period] values. */
    fun series(values: DoubleArray, period: Int): DoubleArray {
        require(period > 0)
        val out = DoubleArray(values.size) { Double.NaN }
        if (values.size < period) return out
        var sum = 0.0
        for (i in 0 until period) sum += values[i]
        var ema = sum / period
        out[period - 1] = ema
        val k = 2.0 / (period + 1)
        for (i in period until values.size) {
            ema = values[i] * k + ema * (1 - k)
            out[i] = ema
        }
        return out
    }
}

object Macd {
    /** MACD points (one per candle once all three EMAs are defined), oldest first. */
    fun compute(candles: List<Candle>, p: MacdParams = MacdParams()): List<MacdPoint> {
        val n = candles.size
        if (n < p.minCandles) return emptyList()
        val closes = DoubleArray(n) { candles[it].close }
        val fast = Ema.series(closes, p.fast)
        val slow = Ema.series(closes, p.slow)
        val offset = p.slow - 1
        val macdLine = DoubleArray(n - offset) { fast[it + offset] - slow[it + offset] }
        val sig = Ema.series(macdLine, p.signal)
        val out = ArrayList<MacdPoint>()
        for (j in p.signal - 1 until macdLine.size) {
            out.add(MacdPoint(candles[j + offset].timeSec, macdLine[j], sig[j], macdLine[j] - sig[j]))
        }
        return out
    }
}

enum class SignalType { BUY, SELL, HOLD }

object Crossover {
    /**
     * BUY : previous MACD <= previous signal AND current MACD > current signal.
     * SELL: previous MACD >= previous signal AND current MACD < current signal.
     */
    fun detect(prev: MacdPoint, cur: MacdPoint): SignalType = when {
        prev.macd <= prev.signal && cur.macd > cur.signal -> SignalType.BUY
        prev.macd >= prev.signal && cur.macd < cur.signal -> SignalType.SELL
        else -> SignalType.HOLD
    }
}
