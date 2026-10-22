package com.eyue.reader.ui

import android.content.Context
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.annotation.FontRes
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.eyue.reader.R
import com.eyue.reader.data.ReaderSettings
import java.io.File

private val ReaderSongFamily = FontFamily(Font(R.font.reader_song))
private val ReaderHeiFamily = FontFamily(Font(R.font.reader_hei))

internal data class ReaderFontCalibration(val sizeScale: Float = 1f, val trackingEm: Float = 0f)

private data class FontProbeKey(val typeface: Typeface, val systemTypeface: Typeface, val locales: String)
private val fontCalibrations = object : LinkedHashMap<FontProbeKey, ReaderFontCalibration>(8, .75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<FontProbeKey, ReaderFontCalibration>?) = size > 8
}

/** Match Chinese ink size and cell pitch, since equal em sizes can have very different visible glyphs. */
internal fun readerFontCalibration(context: Context, settings: ReaderSettings): ReaderFontCalibration {
    val resource = readerBundledFontResource(settings) ?: return ReaderFontCalibration()
    val weight = if (settings.bold) Typeface.BOLD else Typeface.NORMAL
    val selected = Typeface.create(context.resources.getFont(resource), weight)
    val system = Typeface.create(Typeface.DEFAULT, weight)
    val locales = context.resources.configuration.locales
    val key = FontProbeKey(selected, system, locales.toLanguageTags())
    return synchronized(fontCalibrations) {
        fontCalibrations.getOrPut(key) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 256f; textLocales = locales }
            val bounds = Rect()
            val glyphs = "国田中日月山水书读"
            fun measure(font: Typeface): Pair<Float, Float> {
                paint.typeface = font
                val ink = glyphs.indices.sumOf { index ->
                    paint.getTextBounds(glyphs, index, index + 1, bounds)
                    maxOf(bounds.width(), bounds.height()).toDouble()
                }.toFloat() / glyphs.length
                return ink to paint.measureText(glyphs) / glyphs.length
            }
            val reference = measure(system)
            val bundled = measure(selected)
            if (reference.first <= 0f || bundled.first <= 0f) ReaderFontCalibration() else {
                val scale = (reference.first / bundled.first).coerceIn(.8f, 1.15f)
                ReaderFontCalibration(scale, (reference.second - bundled.second * scale) / paint.textSize)
            }
        }
    }
}

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
