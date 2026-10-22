package com.eyue.reader.ui

import androidx.annotation.FontRes
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.eyue.reader.R
import com.eyue.reader.data.ReaderSettings
import java.io.File

private val ReaderSongFamily = FontFamily(Font(R.font.reader_song))
private val ReaderHeiFamily = FontFamily(Font(R.font.reader_hei))

/** Generic Android serif/sans families may redirect Chinese to the user's OEM theme font. */
@FontRes
internal fun readerBundledFontResource(settings: ReaderSettings): Int? =
    if (settings.fontPath != null) null else when (settings.fontName) {
        "系统宋体" -> R.font.reader_song
        "系统黑体" -> R.font.reader_hei
        else -> null
    }

internal fun readerFontFamily(settings: ReaderSettings): FontFamily =
    settings.fontPath?.let { path -> runCatching { FontFamily(Font(File(path))) }.getOrNull() }
        ?: when (settings.fontName) {
            "系统宋体" -> ReaderSongFamily
            "系统黑体" -> ReaderHeiFamily
            "等宽字体" -> FontFamily.Monospace
            else -> FontFamily.Default
        }
