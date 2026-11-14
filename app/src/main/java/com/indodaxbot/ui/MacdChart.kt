package com.indodaxbot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.indodaxbot.api.Candle
import com.indodaxbot.strategy.MacdPoint
import com.indodaxbot.strategy.SignalType
import com.indodaxbot.strategy.Strategy
import com.indodaxbot.strategy.StrategyConfig

private val MacdBlue = Color(0xFF2196F3)
private val SignalOrange = Color(0xFFFFA000)
private val Up = Color(0xFF1B8A4B)
private val Down = Color(0xFFC62828)

/** Candlestick price chart with the MACD panel underneath. Both share the same candle columns. */
@Composable
fun MacdChart(candles: List<Candle>, points: List<MacdPoint>, cfg: StrategyConfig, modifier: Modifier = Modifier) {
    if (candles.size < 2) return
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val markers = remember(points, cfg) { Strategy.markers(points, cfg) }
    val timeToCandle = remember(candles) { candles.withIndex().associate { it.value.timeSec to it.index } }
    val pointByTime = remember(points) { points.associateBy { it.timeSec } }
    val shownPoints = remember(candles, pointByTime) { candles.map { pointByTime[it.timeSec] } }
    // Markers that fall inside the visible candle window: candle index -> type.
    val visibleMarkers = remember(markers, timeToCandle, points) {
        markers.mapNotNull { (pi, type) -> timeToCandle[points[pi].timeSec]?.let { it to type } }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Price", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            drawGrid(grid)
            val lo0 = candles.minOf { it.low }
            val hi0 = candles.maxOf { it.high }
            val pad = (hi0 - lo0).coerceAtLeast(1e-9) * 0.06
            val lo = lo0 - pad
            val hi = hi0 + pad
            val colW = size.width / candles.size
            fun y(v: Double) = (size.height - ((v - lo) / (hi - lo) * size.height)).toFloat()
            candles.forEachIndexed { i, c ->
                val x = (i + 0.5f) * colW
                val color = if (c.close >= c.open) Up else Down
                drawLine(color, Offset(x, y(c.high)), Offset(x, y(c.low)), strokeWidth = 1.5f)
                val top = y(maxOf(c.open, c.close))
                val bottom = y(minOf(c.open, c.close))
                drawRect(
                    color, Offset(x - colW * 0.3f, top),
                    Size(colW * 0.6f, (bottom - top).coerceAtLeast(1.5f))
                )
            }
            visibleMarkers.forEach { (i, type) ->
                val c = candles[i]
                val x = (i + 0.5f) * colW
                if (type == SignalType.BUY) drawTriangle(x, y(c.low) + 10f, up = true, color = Up)
                else drawTriangle(x, y(c.high) - 10f, up = false, color = Down)
            }
        }

        Text("MACD", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            drawGrid(grid)
            val vals = shownPoints.filterNotNull()
            if (vals.isEmpty()) return@Canvas
            val lo0 = vals.minOf { minOf(it.macd, it.signal, it.histogram, 0.0) }
            val hi0 = vals.maxOf { maxOf(it.macd, it.signal, it.histogram, 0.0) }
            val pad = (hi0 - lo0).coerceAtLeast(1e-9) * 0.15
            val lo = lo0 - pad
            val hi = hi0 + pad
            val h = size.height
            val colW = size.width / candles.size
            fun y(v: Double) = (h - ((v - lo) / (hi - lo) * h)).toFloat().coerceIn(0f, h)
            fun x(i: Int) = (i + 0.5f) * colW

            // Zones: BUY below its threshold, SELL above its threshold.
            cfg.buyZone?.let { z ->
                val yz = y(z)
                drawRect(Up.copy(alpha = 0.14f), Offset(0f, yz), Size(size.width, h - yz))
                drawLine(Up.copy(alpha = 0.6f), Offset(0f, yz), Offset(size.width, yz), strokeWidth = 1.5f)
            }
            cfg.sellZone?.let { z ->
                val yz = y(z)
                drawRect(Down.copy(alpha = 0.14f), Offset(0f, 0f), Size(size.width, yz))
                drawLine(Down.copy(alpha = 0.6f), Offset(0f, yz), Offset(size.width, yz), strokeWidth = 1.5f)
            }
            // Zero line
            drawLine(grid.copy(alpha = 0.35f), Offset(0f, y(0.0)), Offset(size.width, y(0.0)), strokeWidth = 1f)

            // Histogram bars
            shownPoints.forEachIndexed { i, p ->
                if (p == null) return@forEachIndexed
                val color = (if (p.histogram >= 0) Up else Down).copy(alpha = 0.35f)
                val y0 = y(0.0)
                val y1 = y(p.histogram)
                drawRect(
                    color, Offset(x(i) - colW * 0.3f, minOf(y0, y1)),
                    Size(colW * 0.6f, kotlin.math.abs(y1 - y0).coerceAtLeast(1f))
                )
            }

            fun line(color: Color, value: (MacdPoint) -> Double) {
                val path = Path()
                var started = false
                shownPoints.forEachIndexed { i, p ->
                    if (p == null) return@forEachIndexed
                    val px = x(i)
                    val py = y(value(p))
                    if (!started) { path.moveTo(px, py); started = true } else path.lineTo(px, py)
                }
                drawPath(path, color, style = Stroke(width = 3f))
            }
            line(MacdBlue) { it.macd }
            line(SignalOrange) { it.signal }

            visibleMarkers.forEach { (i, type) ->
                val p = shownPoints[i] ?: return@forEach
                if (type == SignalType.BUY) drawTriangle(x(i), y(p.signal) + 12f, up = true, color = Up)
                else drawTriangle(x(i), y(p.signal) - 12f, up = false, color = Down)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            LegendDot(MacdBlue, "MACD")
            LegendDot(SignalOrange, "Signal")
            LegendDot(Up, "Buy zone")
            LegendDot(Down, "Sell zone")
        }
    }
}

private fun DrawScope.drawGrid(color: Color) {
    for (i in 1..3) {
        val y = size.height * i / 4f
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
    }
}

private fun DrawScope.drawTriangle(cx: Float, cy: Float, up: Boolean, color: Color) {
    val r = 9f
    val path = Path().apply {
        if (up) {
            moveTo(cx, cy - r); lineTo(cx - r, cy + r); lineTo(cx + r, cy + r)
        } else {
            moveTo(cx, cy + r); lineTo(cx - r, cy - r); lineTo(cx + r, cy - r)
        }
        close()
    }
    drawPath(path, color)
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
