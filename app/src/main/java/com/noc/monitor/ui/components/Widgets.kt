package com.noc.monitor.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noc.monitor.protocol.traffic.StoredSample
import com.noc.monitor.protocol.traffic.TrafficFormat
import com.noc.monitor.ui.theme.Accent
import com.noc.monitor.ui.theme.Card
import com.noc.monitor.ui.theme.Offline
import com.noc.monitor.ui.theme.Online
import com.noc.monitor.ui.theme.TextMain
import com.noc.monitor.ui.theme.TextMute
import com.noc.monitor.ui.theme.Warning

@Composable
fun StatusDot(status: String) {
    val color = when (status) {
        "ONLINE" -> Online
        "OFFLINE" -> Offline
        else -> Warning
    }
    Box(
        Modifier
            .size(10.dp)
            .background(color, CircleShape),
    )
}

@Composable
fun MetricCard(title: String, value: String, accent: Color = Accent, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Card),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, color = TextMute, fontSize = 12.sp)
            Text(value, color = accent, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
fun NocCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Card),
        shape = RoundedCornerShape(14.dp),
    ) {
        Box(Modifier.padding(14.dp)) { content() }
    }
}

@Composable
fun Kv(label: String, value: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextMute, fontSize = 13.sp)
        Text(value ?: "—", color = TextMain, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    }
}

@Composable
fun TrafficGraph(samples: List<StoredSample>, modifier: Modifier = Modifier) {
    val rx = samples.map { it.rxBps.toFloat() }
    val tx = samples.map { it.txBps.toFloat() }
    val max = (rx + tx).maxOrNull()?.coerceAtLeast(1f) ?: 1f
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("RX ${TrafficFormat.bps(rx.lastOrNull()?.toLong() ?: 0)}", color = Accent, fontSize = 12.sp)
            Text("TX ${TrafficFormat.bps(tx.lastOrNull()?.toLong() ?: 0)}", color = Warning, fontSize = 12.sp)
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(160.dp)
                .padding(top = 8.dp),
        ) {
            if (samples.size < 2) return@Canvas
            fun line(values: List<Float>, color: Color) {
                val path = Path()
                val w = size.width
                val h = size.height
                values.forEachIndexed { i, v ->
                    val x = i * (w / (values.size - 1).coerceAtLeast(1))
                    val y = h - (v / max) * h
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color, style = Stroke(width = 3f, cap = StrokeCap.Round))
            }
            val steps = 4
            for (i in 0..steps) {
                val y = size.height * i / steps
                drawLine(Color.White.copy(alpha = 0.06f), Offset(0f, y), Offset(size.width, y))
            }
            line(rx, Accent)
            line(tx, Warning)
        }
    }
}

@Composable
fun RowScope.FillMetric(title: String, value: String, accent: Color = Accent) {
    MetricCard(title, value, accent, Modifier.weight(1f))
}
