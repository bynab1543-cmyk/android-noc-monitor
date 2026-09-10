package com.noc.monitor.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val LavenderBg = Color(0xFFE8E0FF)
val SiteChip = Color(0xFFD6CCF8)
val Purple = Color(0xFF6B46F0)
val PurpleDeep = Color(0xFF5B35E8)
val BlueBtn = Color(0xFF3B9BFF)
val RedBtn = Color(0xFFEF4444)
val Green = Color(0xFF22C55E)
val Orange = Color(0xFFF5A623)
val Cyan = Color(0xFF22C3E6)
val TextDark = Color(0xFF1C1C28)
val TextMute = Color(0xFF9A96B0)
val CardWhite = Color(0xFFFFFFFF)
val NavBar = Color(0xFF5B35E8)
val GaugeTrack = Color(0xFFE8E6F2)
val GaugeFill = Color(0xFF2FBF6B)

private val scheme = lightColorScheme(
    primary = Purple,
    onPrimary = Color.White,
    background = LavenderBg,
    onBackground = TextDark,
    surface = CardWhite,
    onSurface = TextDark,
)

@Composable
fun NocTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
