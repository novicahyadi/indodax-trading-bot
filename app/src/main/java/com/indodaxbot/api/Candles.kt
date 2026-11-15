package com.indodaxbot.api

object Candles {
    /** Seconds per candle for the timeframes Indodax's history endpoint supports. */
    fun timeframeSeconds(tf: String): Long? = when (tf) {
        "1" -> 60L
        "5" -> 300L
        "15" -> 900L
        "30" -> 1800L
        "60" -> 3600L
        "240" -> 14400L
        "1D" -> 86400L
        "3D" -> 259200L
        "1W" -> 604800L
        else -> null
    }

    /** Milliseconds from [nowMs] until the next candle close of this timeframe plus [graceMs]. */
    fun msUntilNextClose(tfSec: Long, nowMs: Long, graceMs: Long = 20_000L): Long {
        val tf = tfSec * 1000L
        return (nowMs / tf + 1) * tf + graceMs - nowMs
    }

    /**
     * Keeps only fully closed candles: open time + timeframe <= now.
     * The still-forming candle must never be used for signals.
     */
    fun closedOnly(candles: List<Candle>, tfSeconds: Long, nowSec: Long): List<Candle> =
        candles.filter { it.timeSec + tfSeconds <= nowSec }
}
