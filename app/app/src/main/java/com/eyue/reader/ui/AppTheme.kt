package com.eyue.reader.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.eyue.reader.data.AppThemeStyle

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

internal val PixelLightAppColors = LightAppColors.copy(
    primary = Color(0xFF435AA8), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE4FF), onPrimaryContainer = Color(0xFF202746),
    secondary = Color(0xFF775283), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0DDF3), onSecondaryContainer = Color(0xFF3B2544),
    tertiary = Color(0xFF96502D), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDFC5), onTertiaryContainer = Color(0xFF43230F),
    background = Color(0xFFFFF8E8), onBackground = Color(0xFF252A43),
    surface = Color(0xFFFFFCF3), onSurface = Color(0xFF252A43),
    surfaceVariant = Color(0xFFECEAF5), onSurfaceVariant = Color(0xFF53566E),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFFF8E8),
    surfaceContainer = Color(0xFFF1EEF9), surfaceContainerHigh = Color(0xFFE7E3F2),
    surfaceContainerHighest = Color(0xFFDBD8E9),
    inverseSurface = Color(0xFF252A43), inverseOnSurface = Color(0xFFFFF8E8), inversePrimary = Color(0xFFB7C4FF),
    outline = Color(0xFF626883), outlineVariant = Color(0xFFBBB8CB), surfaceTint = Color.Transparent,
    error = Color(0xFFAE3B2D), onError = Color.White,
    errorContainer = Color(0xFFFFDAD4), onErrorContainer = Color(0xFF431009)
)

internal val PixelDarkAppColors = DarkAppColors.copy(
    primary = Color(0xFFB7C4FF), onPrimary = Color(0xFF202C63),
    primaryContainer = Color(0xFF344279), onPrimaryContainer = Color(0xFFE1E6FF),
    secondary = Color(0xFFE3B5ED), onSecondary = Color(0xFF45224E),
    secondaryContainer = Color(0xFF60406A), onSecondaryContainer = Color(0xFFF6D9FD),
    tertiary = Color(0xFFFFBF90), onTertiary = Color(0xFF522A10),
    tertiaryContainer = Color(0xFF713F20), onTertiaryContainer = Color(0xFFFFDFC5),
    background = Color(0xFF151829), onBackground = Color(0xFFF0EBD9),
    surface = Color(0xFF20243B), onSurface = Color(0xFFF0EBD9),
    surfaceVariant = Color(0xFF30354D), onSurfaceVariant = Color(0xFFBFC3DA),
    surfaceContainerLowest = Color(0xFF101322), surfaceContainerLow = Color(0xFF1B1F34),
    surfaceContainer = Color(0xFF262B43), surfaceContainerHigh = Color(0xFF30354D),
    surfaceContainerHighest = Color(0xFF3A405A),
    inverseSurface = Color(0xFFF0EBD9), inverseOnSurface = Color(0xFF252A43), inversePrimary = Color(0xFF435AA8),
    outline = Color(0xFF929AB8), outlineVariant = Color(0xFF505A7D), surfaceTint = Color.Transparent,
    error = Color(0xFFFFB4A5), onError = Color(0xFF661509),
    errorContainer = Color(0xFF8E291B), onErrorContainer = Color(0xFFFFDAD4)
)

internal val LocalAppThemeStyle = staticCompositionLocalOf { AppThemeStyle.CLASSIC }
internal val LocalAppEinkMode = staticCompositionLocalOf { false }
private val ClassicAppShapes = Shapes()
private val PixelAppShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp), small = RoundedCornerShape(0.dp),
    medium = RoundedCornerShape(0.dp), large = RoundedCornerShape(0.dp), extraLarge = RoundedCornerShape(0.dp)
)
private val ClassicAppTypography = Typography()
private val PixelAppTypography = ClassicAppTypography.copy(
    labelLarge = ClassicAppTypography.labelLarge.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
    labelMedium = ClassicAppTypography.labelMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
    labelSmall = ClassicAppTypography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
)

internal fun appColors(style: AppThemeStyle, dark: Boolean, eink: Boolean = false) = when {
    eink -> einkAppColors(dark)
    style == AppThemeStyle.PIXEL -> if (dark) PixelDarkAppColors else PixelLightAppColors
    style == AppThemeStyle.XINGCAI -> if (dark) XingcaiDarkAppColors else XingcaiLightAppColors
    else -> if (dark) DarkAppColors else LightAppColors
}

@Composable
internal fun EReadTheme(dark: Boolean, eink: Boolean, style: AppThemeStyle, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAppThemeStyle provides style, LocalAppEinkMode provides eink) {
        MaterialTheme(colorScheme = appColors(style, dark, eink),
            shapes = when (style) { AppThemeStyle.PIXEL -> PixelAppShapes; AppThemeStyle.XINGCAI -> XingcaiAppShapes; else -> ClassicAppShapes },
            typography = when (style) { AppThemeStyle.PIXEL -> PixelAppTypography; AppThemeStyle.XINGCAI -> XingcaiAppTypography; else -> ClassicAppTypography },
            content = content)
    }
}

@Composable
internal fun appShape(corner: Dp): Shape {
    val style = LocalAppThemeStyle.current
    return remember(style, corner) {
        if (style == AppThemeStyle.PIXEL) PixelAppShape(corner.coerceAtMost(8.dp))
        else RoundedCornerShape(if (style == AppThemeStyle.XINGCAI) corner.coerceAtMost(20.dp) else corner)
    }
}

/** Stepped corners keep the app's panels distinct from the reader's saved appearance profiles. */
internal class PixelAppShape(private val corner: Dp = 8.dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val c = with(density) { corner.toPx() }.coerceIn(0f, minOf(size.width, size.height) / 4f)
        val h = size.height
        val w = size.width
        val path = Path().apply {
            moveTo(c, 0f); lineTo(w - c, 0f)
            lineTo(w - c, c / 2); lineTo(w - c / 2, c / 2); lineTo(w - c / 2, c); lineTo(w, c)
            lineTo(w, h - c)
            lineTo(w - c / 2, h - c); lineTo(w - c / 2, h - c / 2); lineTo(w - c, h - c / 2); lineTo(w - c, h)
            lineTo(c, h)
            lineTo(c, h - c / 2); lineTo(c / 2, h - c / 2); lineTo(c / 2, h - c); lineTo(0f, h - c)
            lineTo(0f, c)
            lineTo(c / 2, c); lineTo(c / 2, c / 2); lineTo(c, c / 2)
            close()
        }
        return Outline.Generic(path)
    }
}

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
