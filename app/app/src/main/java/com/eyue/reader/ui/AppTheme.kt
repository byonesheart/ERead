package com.eyue.reader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal val LightAppColors = lightColorScheme(
    primary = Color(0xFF65704D), onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E9D9), onPrimaryContainer = Color(0xFF292D25),
    secondary = Color(0xFF65704D), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8EDDC), onSecondaryContainer = Color(0xFF292D25),
    tertiary = Color(0xFF65704D), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE8EDDC), onTertiaryContainer = Color(0xFF292D25),
    background = Color(0xFFF7F7F2), onBackground = Color(0xFF292D25),
    surface = Color(0xFFFDFDF9), onSurface = Color(0xFF292D25),
    surfaceVariant = Color(0xFFF0F1E9), onSurfaceVariant = Color(0xFF747B6B),
    surfaceContainerLowest = Color(0xFFFDFDF9), surfaceContainerLow = Color(0xFFF7F7F2),
    surfaceContainer = Color(0xFFF0F1E9), surfaceContainerHigh = Color(0xFFEBEDE4),
    surfaceContainerHighest = Color(0xFFE6E8DE),
    outline = Color(0xFF8B927F), outlineVariant = Color(0xFFE6E8DE),
    error = Color(0xFFAC695A)
)

internal val DarkAppColors = darkColorScheme(
    primary = Color(0xFFBDCCA3), onPrimary = Color(0xFF27331D),
    primaryContainer = Color(0xFF343E2A), onPrimaryContainer = Color(0xFFE2EACF),
    secondary = Color(0xFFBDCCA3), onSecondary = Color(0xFF27331D),
    secondaryContainer = Color(0xFF343E2A), onSecondaryContainer = Color(0xFFE2EACF),
    tertiary = Color(0xFFBDCCA3), onTertiary = Color(0xFF27331D),
    tertiaryContainer = Color(0xFF343E2A), onTertiaryContainer = Color(0xFFE2EACF),
    background = Color(0xFF151913), onBackground = Color(0xFFE1E7D9),
    surface = Color(0xFF1E241B), onSurface = Color(0xFFE1E7D9),
    surfaceVariant = Color(0xFF292F24), onSurfaceVariant = Color(0xFFABB29E),
    surfaceContainerLowest = Color(0xFF12160F), surfaceContainerLow = Color(0xFF1A2017),
    surfaceContainer = Color(0xFF23291F), surfaceContainerHigh = Color(0xFF2B3226),
    surfaceContainerHighest = Color(0xFF343C2E),
    inverseSurface = Color(0xFF30382A), inverseOnSurface = Color(0xFFE1E7D9),
    outline = Color(0xFF707A62), outlineVariant = Color(0xFF3B4534),
    error = Color(0xFFF1AF9E)
)

internal fun einkAppColors(dark: Boolean) = (if (dark) DarkAppColors else LightAppColors).let { base ->
    val paper = if (dark) Color.Black else Color.White
    val text = if (dark) Color.White else Color.Black
    val muted = if (dark) Color(0xFFD0D0D0) else Color(0xFF303030)
    val panel = if (dark) Color(0xFF202020) else Color(0xFFF0F0F0)
    base.copy(primary = text, onPrimary = paper, primaryContainer = panel, onPrimaryContainer = text,
        secondary = text, onSecondary = paper, secondaryContainer = panel, onSecondaryContainer = text,
        tertiary = text, onTertiary = paper, tertiaryContainer = panel, onTertiaryContainer = text,
        background = paper, onBackground = text, surface = paper, onSurface = text,
        surfaceVariant = panel, onSurfaceVariant = muted,
        surfaceContainerLowest = paper, surfaceContainerLow = paper, surfaceContainer = panel,
        surfaceContainerHigh = panel, surfaceContainerHighest = panel,
        inverseSurface = text, inverseOnSurface = paper, inversePrimary = paper,
        outline = text, outlineVariant = muted, surfaceTint = Color.Transparent,
        error = text, onError = paper, errorContainer = panel, onErrorContainer = text)
}

val Olive: Color @Composable get() = MaterialTheme.colorScheme.primary
val Ink: Color @Composable get() = MaterialTheme.colorScheme.onSurface
val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
val Paper: Color @Composable get() = MaterialTheme.colorScheme.background
val Line: Color @Composable get() = MaterialTheme.colorScheme.outlineVariant
