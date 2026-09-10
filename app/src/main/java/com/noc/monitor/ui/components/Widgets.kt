package com.noc.monitor.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noc.monitor.data.repo.TrafficPoint
import com.noc.monitor.ui.theme.Cyan
import com.noc.monitor.ui.theme.GaugeFill
import com.noc.monitor.ui.theme.GaugeTrack
import com.noc.monitor.ui.theme.Purple
import com.noc.monitor.ui.theme.TextMute

@Composable
fun ArcGauge(percent: Int?, color: Color = GaugeFill, size: Dp = 82.dp) {
    val p = (percent ?: 0).coerceIn(0, 100) / 100f
    Canvas(Modifier.size(size)) {
        val stroke = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
        drawArc(GaugeTrack, 135f, 270f, false, style = stroke)
        if (percent != null) {
            drawArc(color, 135f, 270f * p, false, style = stroke)
        }
    }
}

@Composable
fun Sparkline(points: List<TrafficPoint>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        val padStart = 36.dp.toPx()
        val padBottom = 16.dp.toPx()
        val plotW = (size.width - padStart).coerceAtLeast(1f)
        val plotH = (size.height - padBottom).coerceAtLeast(1f)
        val maxBps = points.maxOfOrNull { maxOf(it.rxBps, it.txBps) }?.coerceAtLeast(500_000L) ?: 1_000_000L
        val maxMbps = (maxBps / 1_000_000.0).coerceAtLeast(1.0)

        drawLine(Color(0x22000000), Offset(padStart, 0f), Offset(padStart, plotH), 1.2f)
        drawLine(Color(0x22000000), Offset(padStart, plotH), Offset(size.width, plotH), 1.2f)
        val top = measurer.measure("${maxMbps.toInt()}Mbps", TextStyle(color = TextMute, fontSize = 9.sp))
        drawText(top, topLeft = Offset(0f, 0f))
        val bot = measurer.measure("0Mbps", TextStyle(color = TextMute, fontSize = 9.sp))
        drawText(bot, topLeft = Offset(0f, plotH - bot.size.height))

        if (points.size >= 2) {
            val step = plotW / (points.size - 1).coerceAtLeast(1)
            fun drawSeries(color: Color, pick: (TrafficPoint) -> Long) {
                var prev: Offset? = null
                points.forEachIndexed { i, p ->
                    val x = padStart + i * step
                    val y = plotH - ((pick(p) / 1_000_000.0) / maxMbps).toFloat() * plotH
                    val cur = Offset(x, y.coerceIn(0f, plotH))
                    if (prev != null) drawLine(color, prev!!, cur, strokeWidth = 3.2f, cap = StrokeCap.Round)
                    prev = cur
                }
            }
            drawSeries(Cyan) { it.rxBps }
            drawSeries(Purple) { it.txBps }
        }

        val labels = listOf("60", "50", "40", "30", "20", "10", "1")
        labels.forEachIndexed { i, label ->
            val x = padStart + plotW * i / (labels.size - 1)
            val m = measurer.measure(label, TextStyle(color = TextMute, fontSize = 9.sp))
            drawText(m, topLeft = Offset(x - m.size.width / 2f, plotH + 2.dp.toPx()))
        }
    }
}
