package com.eyue.reader.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.eyue.reader.data.ReaderBackground

// Reading palette pairs adapted from 7Mrlan/ebook v3.0.0+build.66 (GPL-3.0).
internal data class ReaderPalette(val background: Color, val foreground: Color, val secondary: Color,
    val overridePublisherColors: Boolean = false)

internal val ReaderBackgroundChoices = listOf(
    ReaderBackground.PAPER to "纸白", ReaderBackground.WHITE to "纯白", ReaderBackground.GREEN to "护眼",
    ReaderBackground.PARCHMENT to "羊皮", ReaderBackground.MIST_GREEN to "雾绿", ReaderBackground.GRAY to "雾灰",
    ReaderBackground.CHARCOAL to "深灰", ReaderBackground.DARK to "暖夜")

internal fun readerPalette(background: ReaderBackground, night: Boolean, colors: ColorScheme, eink: Boolean = false): ReaderPalette {
    if (eink) return ReaderPalette(if (night) Color.Black else Color.White,
        if (night) Color.White else Color.Black, if (night) Color.White else Color.Black, true)
    if (night) return ReaderPalette(Color(0xFF333333), Color(0xFFB4ADA3), Color(0xFFA49D93), true)
    return when (background) {
        ReaderBackground.PARCHMENT -> ReaderPalette(Color(0xFFECE1CB), Color(0xFF625135), Color(0xFF6E5B3F), true)
        ReaderBackground.MIST_GREEN -> ReaderPalette(Color(0xFFD8E2D0), Color(0xFF3C4839), Color(0xFF56614F), true)
        ReaderBackground.GRAY -> ReaderPalette(Color(0xFFDCDCDC), Color(0xFF404040), Color(0xFF5B5B5B), true)
        ReaderBackground.CHARCOAL -> ReaderPalette(Color(0xFF44474C), Color(0xFFBFC0C4), Color(0xFFB3B4B8), true)
        else -> ReaderPalette(when (background) { ReaderBackground.WHITE -> Color.White
            ReaderBackground.GREEN -> Color(0xFFE5EBD9); else -> colors.background }, colors.onBackground, colors.onBackground.copy(alpha = .56f))
    }
}
