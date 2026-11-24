package com.hsteex.rawviewer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFFFFB547)
val PickGreen = Color(0xFF4CD964)
val RejectRed = Color(0xFFFF5A52)
val StarYellow = Color(0xFFFFCC33)
val DownloadedBlue = Color(0xFF5AB0FF)
val Bg = Color(0xFF0E0E0F)
val Surface1 = Color(0xFF18181A)
val Surface2 = Color(0xFF232326)

private val scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF2A1A00),
    primaryContainer = Color(0xFF4A3200),
    onPrimaryContainer = Color(0xFFFFDDB0),
    secondary = Color(0xFFD6C3A8),
    secondaryContainer = Surface2,
    onSecondaryContainer = Color(0xFFEDE0CF),
    background = Bg,
    onBackground = Color(0xFFE8E3DC),
    surface = Bg,
    onSurface = Color(0xFFE8E3DC),
    surfaceVariant = Surface2,
    onSurfaceVariant = Color(0xFFB9B2A8),
    surfaceContainer = Surface1,
    surfaceContainerHigh = Surface2,
    surfaceContainerHighest = Color(0xFF2C2C30),
    surfaceContainerLow = Color(0xFF141416),
    outline = Color(0xFF5A5650),
    outlineVariant = Color(0xFF38363A),
    error = RejectRed,
)

@Composable
fun RawTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
