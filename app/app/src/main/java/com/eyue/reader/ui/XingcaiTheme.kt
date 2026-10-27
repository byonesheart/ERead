package com.eyue.reader.ui

// Visuals adapted from the user-provided ebook 2.0.5 source; no engine or service code is imported.
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eyue.reader.data.AppThemeStyle

private data class XingcaiPalette(
    val paper: Long, val surface: Long, val ink: Long, val muted: Long,
    val accent: Long, val onAccent: Long, val pale: Long, val line: Long,
    val accent2: Long
)

private fun xingcaiPalette(dark: Boolean): XingcaiPalette = if (dark)
    XingcaiPalette(0xFF111D31, 0xFF192A43, 0xFFF2F6FF, 0xFFB4C6E0,
        0xFFB2CBFF, 0xFF102343, 0xFF243C60, 0xFF344A6A, 0xFFA78BFA)
else
    XingcaiPalette(0xFFF8FAFF, 0xFFFFFFFF, 0xFF1D2E4B, 0xFF6B7C99,
        0xFF3D64C5, 0xFFFFFFFF, 0xFFEDF3FF, 0xFFE4EBF7, 0xFF7FA9F0)

private fun xingcaiColors(dark: Boolean): ColorScheme {
    val p = xingcaiPalette(dark)
    val base = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = Color(p.accent), onPrimary = Color(p.onAccent),
        primaryContainer = Color(p.pale), onPrimaryContainer = Color(p.ink),
        secondary = Color(p.accent), onSecondary = Color(p.onAccent),
        secondaryContainer = Color(p.pale), onSecondaryContainer = Color(p.ink),
        tertiary = Color(p.accent2), onTertiary = Color(p.onAccent),
        tertiaryContainer = Color(p.pale), onTertiaryContainer = Color(p.ink),
        background = Color(p.paper), onBackground = Color(p.ink),
        surface = Color(p.surface), onSurface = Color(p.ink),
        surfaceVariant = Color(p.pale), onSurfaceVariant = Color(p.muted),
        surfaceContainerLowest = Color(p.surface), surfaceContainerLow = Color(p.paper),
        surfaceContainer = Color(p.paper), surfaceContainerHigh = Color(p.pale),
        surfaceContainerHighest = Color(p.line), outline = Color(p.muted), outlineVariant = Color(p.line),
        inverseSurface = Color(p.ink), inverseOnSurface = Color(p.paper), inversePrimary = Color(p.accent),
        surfaceBright = Color(p.surface), surfaceDim = Color(p.paper),
        surfaceTint = Color.Transparent,
        error = if (dark) Color(0xFFF4B5AB) else Color(0xFFA33F38)
    )
    return base
}

internal val XingcaiAppTypography = Typography(
    displayLarge = TextStyle(fontSize = 17.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
    displayMedium = TextStyle(fontSize = 17.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
    displaySmall = TextStyle(fontSize = 17.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
    headlineLarge = TextStyle(fontSize = 17.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 17.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 17.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 17.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 17.sp)
)

internal val XingcaiLightAppColors = xingcaiColors(false)
internal val XingcaiDarkAppColors = xingcaiColors(true)
internal val XingcaiAppShapes = Shapes(RoundedCornerShape(8.dp), RoundedCornerShape(10.dp),
    RoundedCornerShape(14.dp), RoundedCornerShape(18.dp), RoundedCornerShape(20.dp))

internal val AppThemeStyle.displayName: String get() = when (this) {
    AppThemeStyle.CLASSIC -> "经典"
    AppThemeStyle.PIXEL -> "像素"
    AppThemeStyle.XINGCAI -> "星彩"
}
internal val AppThemeStyle.description: String get() = when (this) {
    AppThemeStyle.CLASSIC -> "柔和配色 · 圆角界面"
    AppThemeStyle.PIXEL -> "复古配色 · 像素边角"
    AppThemeStyle.XINGCAI -> "清爽蓝色 · 星月图标 · 彩色导航"
}
