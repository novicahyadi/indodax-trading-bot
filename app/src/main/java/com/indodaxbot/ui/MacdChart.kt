package com.indodaxbot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
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
import com.indodaxbot.strategy.DualSignal
import com.indodaxbot.strategy.NormPoint
import com.indodaxbot.strategy.SignalType
import com.indodaxbot.strategy.StrategyConfig

private val MacdBlue = Color(0xFF2196F3)
private val SignalOrange = Color(0xFFFFA000)
private val Up = Color(0xFF1B8A4B)
private val Down = Color(0xFFC62828)
private val RevBuy = Color(0xFF1565C0)
private val RevSell = Color(0xFF7B1FA2)
private val BuyZoneColor = Color(0xFF00897B)
private val SellZoneColor = Color(0xFF8E24AA)

/**
 * Candlestick price chart with the normalized (0-100) MACD panel underneath. Both share the same
 * candle columns. The indicator Y axis is fixed 0-100; the BUY/SELL zone bands follow the settings
 * and are drawn behind the MACD (blue) and signal (orange) lines.
 */
@Composable
fun MacdChart(candles: List<Candle>, norm: List<NormPoint>, cfg: StrategyConfig, modifier: Modifier = Modifier) {
    if (candles.size < 2) return
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val timeToCandle = remember(candles) { candles.withIndex().associate { it.value.timeSec to it.index } }
    val pointByTime = remember(norm) { norm.associateBy { it.timeSec } }
    val shownPoints = remember(candles, pointByTime) { candles.map { pointByTime[it.timeSec] } }
    // Markers that fall inside the visible candle window: candle index -> type.
    val revMarkers = remember(norm, cfg, timeToCandle) { visibleMarkers(DualSignal.reversalMarkers(norm, cfg), norm, timeToCandle) }
    val crossMarkers = remember(norm, cfg, timeToCandle) { visibleMarkers(DualSignal.crossoverMarkers(norm, cfg), norm, timeToCandle) }
    val panelHeight = 160f
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Price", style = MaterialTheme.typography.labelMedium, color = labelColor)
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
            crossMarkers.forEach { (i, type) ->
                val c = candles[i]
                val x = (i + 0.5f) * colW
                if (type == SignalType.BUY) drawTriangle(x, y(c.low) + 10f, up = true, color = Up)
                else drawTriangle(x, y(c.high) - 10f, up = false, color = Down)
            }
            revMarkers.forEach { (i, type) ->
                val c = candles[i]
                val x = (i + 0.5f) * colW
                if (type == SignalType.BUY) drawTriangle(x, y(c.low) + 24f, up = true, color = RevBuy)
                else drawTriangle(x, y(c.high) - 24f, up = false, color = RevSell)
            }
        }

        Text("MACD (0-100)", style = MaterialTheme.typography.labelMedium, color = labelColor)
        Box(Modifier.fillMaxWidth().height(panelHeight.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                drawGrid(grid)
                val h = size.height
                val colW = size.width / candles.size
                // Fixed axis: 100 at the top, 0 at the bottom.
                fun y(v: Double) = (h - (v / 100.0 * h)).toFloat().coerceIn(0f, h)
                fun x(i: Int) = (i + 0.5f) * colW

                // Zone bands first, so the lines are drawn on top of them.
                if (cfg.enableReversal) {
                    val sellTop = y(cfg.sellZoneMax)
                    drawRect(SellZoneColor.copy(alpha = 0.22f), Offset(0f, sellTop), Size(size.width, y(cfg.sellZoneMin) - sellTop))
                    val buyTop = y(cfg.buyZoneMax)
                    drawRect(BuyZoneColor.copy(alpha = 0.22f), Offset(0f, buyTop), Size(size.width, y(cfg.buyZoneMin) - buyTop))
                }

                fun line(color: Color, value: (NormPoint) -> Double) {
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
                line(MacdBlue) { it.macdNorm }
                line(SignalOrange) { it.signalNorm }

                // Crossover arrows sit on the crossing point; reversal arrows sit on the signal line in the zone.
                crossMarkers.forEach { (i, type) ->
                    val p = shownPoints[i] ?: return@forEach
                    val mid = (p.macdNorm + p.signalNorm) / 2.0
                    if (type == SignalType.BUY) drawTriangle(x(i), y(mid) + 12f, up = true, color = Up)
                    else drawTriangle(x(i), y(mid) - 12f, up = false, color = Down)
                }
                revMarkers.forEach { (i, type) ->
                    val p = shownPoints[i] ?: return@forEach
                    if (type == SignalType.BUY) drawTriangle(x(i), y(p.signalNorm) + 12f, up = true, color = RevBuy)
                    else drawTriangle(x(i), y(p.signalNorm) - 12f, up = false, color = RevSell)
                }
            }
            // Band captions, centered in each band (skipped when the band is too thin to hold text).
            if (cfg.enableReversal) {
                BandLabel("ZONA SELL", cfg.sellZoneMin, cfg.sellZoneMax, panelHeight, SellZoneColor)
                BandLabel("ZONA BUY", cfg.buyZoneMin, cfg.buyZoneMax, panelHeight, BuyZoneColor)
            }
            Text("100", style = MaterialTheme.typography.labelSmall, color = labelColor, modifier = Modifier.align(Alignment.TopEnd))
            Text("0", style = MaterialTheme.typography.labelSmall, color = labelColor, modifier = Modifier.align(Alignment.BottomEnd))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            LegendDot(MacdBlue, "MACD")
            LegendDot(SignalOrange, "Signal")
            if (cfg.enableReversal) {
                LegendDot(RevBuy, "Reversal buy")
                LegendDot(RevSell, "Reversal sell")
            }
        }
        if (cfg.enableCrossover) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                LegendDot(Up, "Crossover buy")
                LegendDot(Down, "Crossover sell")
            }
        }
    }
}

private fun visibleMarkers(
    list: List<Pair<Int, SignalType>>, norm: List<NormPoint>, timeToCandle: Map<Long, Int>
): List<Pair<Int, SignalType>> =
    list.mapNotNull { (pi, type) -> timeToCandle[norm[pi].timeSec]?.let { it to type } }

@Composable
private fun androidx.compose.foundation.layout.BoxScope.BandLabel(
    text: String, zoneMin: Double, zoneMax: Double, panelHeight: Float, color: Color
) {
    if (zoneMax - zoneMin < 12.0) return
    val mid = (zoneMin + zoneMax) / 2.0
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .offset(y = (panelHeight * (1f - (mid / 100.0).toFloat()) - 7f).dp)
    )
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
