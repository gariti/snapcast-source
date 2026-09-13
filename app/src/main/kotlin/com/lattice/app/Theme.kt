package com.lattice.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The desktop's wallust palette (2026-09-13 render) so the phone reads as
 * part of the same system: near-black ground, cyan accent, teal secondaries.
 * Dark-first; the light scheme keeps the same hues on a pale ground.
 */
private val Ground = Color(0xFF101314)
private val Ground2 = Color(0xFF171C1D)
private val Ground3 = Color(0xFF1F2728)
private val Ink = Color(0xFFD2F1F2)
private val Ink2 = Color(0xFFB6E3E5)
private val Mute = Color(0xFF7F9FA0)
private val Cyan = Color(0xFF7DD7DB)
private val Teal = Color(0xFF629DAA)
private val Deep = Color(0xFF417273)
private val Line = Color(0xFF2A3637)

private val Dark: ColorScheme = darkColorScheme(
    primary = Cyan,
    onPrimary = Ground,
    primaryContainer = Deep,
    onPrimaryContainer = Ink,
    secondary = Teal,
    onSecondary = Ground,
    secondaryContainer = Ground3,
    onSecondaryContainer = Ink2,
    tertiary = Deep,
    onTertiary = Ink,
    background = Ground,
    onBackground = Ink,
    surface = Ground,
    onSurface = Ink,
    surfaceVariant = Ground2,
    onSurfaceVariant = Ink2,
    surfaceContainer = Ground2,
    surfaceContainerHigh = Ground3,
    surfaceContainerHighest = Ground3,
    outline = Mute,
    outlineVariant = Line,
    error = Color(0xFFF2A08F),
    onError = Ground,
)

private val Light: ColorScheme = lightColorScheme(
    primary = Color(0xFF024242),
    onPrimary = Color(0xFFEDF4F4),
    primaryContainer = Color(0xFFBFD4D5),
    onPrimaryContainer = Color(0xFF101314),
    secondary = Deep,
    onSecondary = Color(0xFFEDF4F4),
    secondaryContainer = Color(0xFFD8E6E6),
    onSecondaryContainer = Color(0xFF101314),
    tertiary = Teal,
    background = Color(0xFFEDF4F4),
    onBackground = Color(0xFF101314),
    surface = Color(0xFFEDF4F4),
    onSurface = Color(0xFF101314),
    surfaceVariant = Color(0xFFE2ECEC),
    onSurfaceVariant = Color(0xFF2E4A4B),
    surfaceContainer = Color(0xFFE2ECEC),
    surfaceContainerHigh = Color(0xFFD6E3E3),
    surfaceContainerHighest = Color(0xFFCBDADA),
    outline = Color(0xFF597171),
    outlineVariant = Color(0xFFBFD4D5),
)

@Composable
fun LatticeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        content = content,
    )
}
