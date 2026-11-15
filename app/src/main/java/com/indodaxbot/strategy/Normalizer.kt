package com.indodaxbot.strategy

/** MACD and signal line scaled to 0-100 over a rolling window. */
data class NormPoint(val timeSec: Long, val macdNorm: Double, val signalNorm: Double)

object Normalizer {
    /**
     * For each point i >= lookback-1, take the last [lookback] MACD and signal values:
     * highest = max of both, lowest = min of both. norm = (raw - lowest) / range * 100,
     * or 50 for both lines when the range is 0.
     */
    fun normalize(points: List<MacdPoint>, lookback: Int): List<NormPoint> {
        require(lookback > 0)
        val out = ArrayList<NormPoint>()
        for (i in lookback - 1 until points.size) {
            var hi = Double.NEGATIVE_INFINITY
            var lo = Double.POSITIVE_INFINITY
            for (j in i - lookback + 1..i) {
                val p = points[j]
                hi = maxOf(hi, p.macd, p.signal)
                lo = minOf(lo, p.macd, p.signal)
            }
            val range = hi - lo
            val cur = points[i]
            if (range == 0.0) {
                out.add(NormPoint(cur.timeSec, 50.0, 50.0))
            } else {
                out.add(NormPoint(cur.timeSec, (cur.macd - lo) / range * 100.0, (cur.signal - lo) / range * 100.0))
            }
        }
        return out
    }
}
