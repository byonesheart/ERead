package com.eyue.reader.ui

import androidx.compose.ui.geometry.Rect
import com.eyue.reader.data.PageSource
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PdfSpeechGeometryRobolectricTest {
    @Test fun realPdfTextCoordinatesKeepParagraphsSeparateAndHandlePageRotation() {
        val context = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(context)
        for (rotation in listOf(0, 90, 180, 270)) {
            val file = File.createTempFile("speech-pdf", ".pdf", context.cacheDir)
            try {
                PDDocument().use { document ->
                    val page = PDPage().apply { this.rotation = rotation }
                    document.addPage(page)
                    PDPageContentStream(document, page).use { output ->
                        output.beginText(); output.setFont(PDType1Font.HELVETICA, 18f)
                        output.newLineAtOffset(72f, 700f); output.showText("First paragraph.")
                        output.newLineAtOffset(0f, -30f); output.showText("Second paragraph."); output.endText()
                    }
                    document.save(file)
                }
                val geometry = loadPdfSpeechGeometry(context, PageSource(file.toURI().toString(), 0))
                assertTrue(geometry.text.contains("First paragraph."))
                val first = geometry.paragraph(geometry.text.indexOf("First"))
                val second = geometry.paragraph(geometry.text.indexOf("Second"))
                assertFalse(first.isEmpty()); assertFalse(second.isEmpty())
                assertTrue(first.none { it in second })
                assertTrue((first + second).all { it.left >= 0f && it.top >= 0f && it.right <= 1f && it.bottom <= 1f })
                if (rotation == 0) assertTrue(first.all { it.top < second.first().top })
            } finally { file.delete() }
        }
    }
    @Test fun normalizedHighlightRotationPreservesTheGlyphRectangle() {
        val rect = Rect(.1f, .2f, .4f, .3f)
        val rotated = rotatePdfSpeechRect(rect, 90)
        assertEquals(.7f, rotated.left, .0001f)
        assertEquals(.1f, rotated.top, .0001f)
        val restored = rotatePdfSpeechRect(rotated, 270)
        assertEquals(rect.left, restored.left, .0001f); assertEquals(rect.top, restored.top, .0001f)
        assertEquals(rect.right, restored.right, .0001f); assertEquals(rect.bottom, restored.bottom, .0001f)
    }
}
