package com.eyue.reader.ui

import android.graphics.Color
import com.eyue.reader.data.PageSource
import com.eyue.reader.data.PdfCrop
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfCropInstrumentedTest {
    @Test fun croppingRemovesRealPdfBordersAndTurningItOffRestoresTheFullPageWithoutEditingTheSource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        val file = File.createTempFile("crop-page", ".pdf", context.cacheDir)
        try {
            PDDocument().use { document ->
                val page = PDPage(PDRectangle(200f, 300f)); document.addPage(page)
                PDPageContentStream(document, page).use { canvas ->
                    canvas.setNonStrokingColor(255, 0, 0); canvas.addRect(0f, 0f, 200f, 300f); canvas.fill()
                    canvas.setNonStrokingColor(0, 255, 0); canvas.addRect(20f, 30f, 160f, 240f); canvas.fill()
                }
                document.save(file)
            }
            val original = file.readBytes()
            val source = PageSource(file.absolutePath, 0)
            val full = decodeReaderPage(context, source, 400)
            val cropped = decodeReaderPage(context, source, 400, PdfCrop(true, .1f, .1f, .1f, .1f))
            val restored = decodeReaderPage(context, source, 400, PdfCrop(false, .1f, .1f, .1f, .1f))
            try {
                assertEquals(400, full.width); assertEquals(600, full.height)
                assertEquals(Color.RED, full.getPixel(5, 5))
                assertEquals(Color.GREEN, cropped.getPixel(5, 5))
                assertEquals(Color.GREEN, cropped.getPixel(cropped.width / 2, cropped.height / 2))
                assertEquals(Color.RED, restored.getPixel(5, 5))
                assertArrayEquals(original, file.readBytes())
            } finally { full.recycle(); cropped.recycle(); restored.recycle() }
        } finally { file.delete() }
    }
}
