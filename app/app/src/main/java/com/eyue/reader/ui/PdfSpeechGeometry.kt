package com.eyue.reader.ui

import android.content.Context
import androidx.compose.ui.geometry.Rect
import com.eyue.reader.data.PageSource
import com.eyue.reader.data.PdfCrop
import com.eyue.reader.data.withLocalDocument
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.StringWriter

internal data class PdfSpeechGlyph(val start: Int, val end: Int, val bounds: Rect)
internal data class PdfSpeechGeometry(val text: String, val glyphs: List<PdfSpeechGlyph>) {
    fun paragraph(offset: Int): List<Rect> {
        val range = readerSpeechParagraph(text, offset) ?: return emptyList()
        return glyphs.filter { it.start <= range.last && it.end > range.first }.map { it.bounds }
    }
}

internal fun rotatePdfSpeechRect(rect: Rect, rotation: Int): Rect = when (((rotation % 360) + 360) % 360) {
    90 -> Rect(1f - rect.bottom, rect.left, 1f - rect.top, rect.right)
    180 -> Rect(1f - rect.right, 1f - rect.bottom, 1f - rect.left, 1f - rect.top)
    270 -> Rect(rect.top, 1f - rect.right, rect.bottom, 1f - rect.left)
    else -> rect
}

internal fun cropPdfSpeechRect(rect: Rect, settings: PdfCrop): Rect? {
    val crop = settings.normalized()
    val visible = Rect(crop.left, crop.top, 1f - crop.right, 1f - crop.bottom)
    val clipped = rect.intersect(visible)
    if (clipped.isEmpty) return null
    return Rect((clipped.left - visible.left) / visible.width, (clipped.top - visible.top) / visible.height,
        (clipped.right - visible.left) / visible.width, (clipped.bottom - visible.top) / visible.height)
}

/** Only the currently spoken PDF page is indexed; image-only pages never invoke this loader. */
internal fun loadPdfSpeechGeometry(context: Context, source: PageSource): PdfSpeechGeometry = withLocalDocument(context, source.path) { file ->
    PDFBoxResourceLoader.init(context)
    PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)).use { document ->
        require(source.pdfPage in 0 until document.numberOfPages)
        val page = document.getPage(source.pdfPage)
        val glyphs = mutableListOf<PdfSpeechGlyph>()
        val stripper = object : PDFTextStripper() {
            override fun writeString(text: String, positions: MutableList<TextPosition>) {
                val preceding = (output as? StringWriter)?.buffer?.length ?: output.toString().length
                var cursor = 0
                positions.forEach { position ->
                    val unicode = position.unicode.orEmpty()
                    if (unicode.isEmpty()) return@forEach
                    val start = text.indexOf(unicode, cursor)
                    if (start < 0) return@forEach
                    cursor = start + unicode.length
                    val direction = position.dir.toInt()
                    val width = if (direction % 180 == 0) page.cropBox.width else page.cropBox.height
                    val height = if (direction % 180 == 0) page.cropBox.height else page.cropBox.width
                    val rect = rotatePdfSpeechRect(Rect(position.xDirAdj / width,
                        (position.yDirAdj - position.heightDir) / height,
                        (position.xDirAdj + position.widthDirAdj) / width, position.yDirAdj / height), page.rotation - direction)
                    if (rect.width > 0f && rect.height > 0f && listOf(rect.left, rect.top, rect.right, rect.bottom).all { it.isFinite() })
                        glyphs += PdfSpeechGlyph(preceding + start, preceding + cursor, rect)
                }
                super.writeString(text, positions)
            }
        }.apply { sortByPosition = true; startPage = source.pdfPage + 1; endPage = source.pdfPage + 1 }
        val raw = stripper.getText(document)
        val leading = raw.length - raw.trimStart().length
        val text = raw.trim()
        PdfSpeechGeometry(text, glyphs.mapNotNull {
            val start = (it.start - leading).coerceAtLeast(0)
            val end = (it.end - leading).coerceAtMost(text.length)
            if (end <= start) null else it.copy(start = start, end = end)
        })
    }
}
