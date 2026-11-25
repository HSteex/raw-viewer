package com.hsteex.rawviewer.ui

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint

// Colori con un significato: restano fissi anche con Material You (✓ verde, ✗ rosso, stelle, scaricate).
val PickGreen = Color(0xFF4CD964)
val RejectRed = Color(0xFFFF5A52)
val StarYellow = Color(0xFFFFCC33)
val DownloadedBlue = Color(0xFF5AB0FF)

/** Ambra dell'app, usata quando i colori dinamici sono spenti o non disponibili (Android < 12). */
private val Amber = Color(0xFFFFB547)

// Colori di tema: con Material You arrivano dallo sfondo del telefono.
val Accent: Color
    @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
val Surface1: Color
    @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainer
val Surface2: Color
    @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainerHigh

private val staticScheme = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF2A1A00),
    primaryContainer = Color(0xFF4A3200),
    onPrimaryContainer = Color(0xFFFFDDB0),
    secondary = Color(0xFFD6C3A8),
    onSecondary = Color(0xFF3A2E1C),
    secondaryContainer = Color(0xFF3A3024),
    onSecondaryContainer = Color(0xFFEDE0CF),
    tertiary = Color(0xFFB4CFA8),
    tertiaryContainer = Color(0xFF2E4426),
    onTertiaryContainer = Color(0xFFD0EBC3),
    background = Color(0xFF0E0E0F),
    onBackground = Color(0xFFE8E3DC),
    surface = Color(0xFF0E0E0F),
    onSurface = Color(0xFFE8E3DC),
    surfaceVariant = Color(0xFF232326),
    onSurfaceVariant = Color(0xFFB9B2A8),
    surfaceContainerLowest = Color(0xFF09090A),
    surfaceContainerLow = Color(0xFF141416),
    surfaceContainer = Color(0xFF18181A),
    surfaceContainerHigh = Color(0xFF232326),
    surfaceContainerHighest = Color(0xFF2C2C30),
    outline = Color(0xFF5A5650),
    outlineVariant = Color(0xFF38363A),
    error = RejectRed,
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Tema scuro (scelta dell'utente). [dynamic]: Material You, colori dallo sfondo (Android 12+). */
@Composable
fun RawTheme(dynamic: Boolean = true, content: @Composable () -> Unit) {
    val scheme = if (dynamic && Build.VERSION.SDK_INT >= 31) {
        // sfondo quasi nero: le foto si giudicano meglio su un fondo neutro
        dynamicDarkColorScheme(LocalContext.current).copy(background = Color(0xFF0B0B0C), surface = Color(0xFF0B0B0C))
    } else staticScheme
    MaterialTheme(colorScheme = scheme, shapes = shapes, content = content)
}

/**
 * Vetro opaco per barre e pannelli sopra le foto: sfoca ciò che c'è dietro e lo tinge col colore di superficie.
 * Sotto Android 12 Haze usa da solo una tinta piena come ripiego.
 */
@Composable
fun frosted(strength: Float = 0.62f): HazeStyle {
    val base = MaterialTheme.colorScheme.surfaceContainer
    return HazeStyle(
        backgroundColor = MaterialTheme.colorScheme.background,
        tint = HazeTint(base.copy(alpha = strength)),
        blurRadius = 24.dp,
        noiseFactor = 0.08f,
    )
}

/** Variante per il visualizzatore: tinta scura neutra, così non altera i colori percepiti della foto. */
@Composable
fun frostedDark(strength: Float = 0.45f): HazeStyle = HazeStyle(
    backgroundColor = Color.Black,
    tint = HazeTint(Color.Black.copy(alpha = strength)),
    blurRadius = 28.dp,
    noiseFactor = 0.05f,
)
