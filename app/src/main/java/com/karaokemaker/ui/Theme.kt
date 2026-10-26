package com.karaokemaker.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFFE0418F)
val AccentSoft = Color(0xFFFFB3D6)
val Background = Color(0xFF120B1E)
val SurfaceColor = Color(0xFF1E1530)

val StageBrush = Brush.verticalGradient(listOf(Color(0xFF2A1248), Background, Color(0xFF0B0712)))

private val colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = AccentSoft,
    background = Background,
    surface = SurfaceColor,
    surfaceVariant = Color(0xFF2A2040),
    onSurface = Color.White,
    onBackground = Color.White,
)

@Composable
fun KaraokeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}

fun formatTime(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000)
    return "%d:%02d".format(s / 60, s % 60)
}
