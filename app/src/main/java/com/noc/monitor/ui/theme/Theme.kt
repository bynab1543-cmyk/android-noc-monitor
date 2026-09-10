package com.noc.monitor.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Bg = Color(0xFF070B14)
val Surface = Color(0xFF101826)
val Card = Color(0xFF162033)
val CardAlt = Color(0xFF1B2740)
val Accent = Color(0xFF38BDF8)
val Online = Color(0xFF22C55E)
val Offline = Color(0xFFEF4444)
val Warning = Color(0xFFF59E0B)
val TextMain = Color(0xFFE5E7EB)
val TextMute = Color(0xFF94A3B8)
val Demo = Color(0xFFFBBF24)

private val scheme: ColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF041018),
    background = Bg,
    onBackground = TextMain,
    surface = Surface,
    onSurface = TextMain,
    surfaceVariant = Card,
    onSurfaceVariant = TextMute,
    error = Offline,
    tertiary = Warning,
)

@Composable
fun NocTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme,
        typography = MaterialTheme.typography.copy(
            titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, color = TextMain),
            titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = TextMain),
            bodyMedium = TextStyle(fontSize = 14.sp, color = TextMain, fontFamily = FontFamily.SansSerif),
            labelSmall = TextStyle(fontSize = 11.sp, color = TextMute, fontFamily = FontFamily.Monospace),
        ),
        content = content,
    )
}
