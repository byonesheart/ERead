package com.eyue.reader.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import com.eyue.reader.data.ChineseConversion
import com.eyue.reader.data.ChineseTextConverter
import com.eyue.reader.data.Book
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.Chapter
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.ReaderSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderChineseTextRobolectricTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun aPageBoundaryDoesNotLoseWordContextAndReusesPlainConversion() = runBlocking {
        val converter = ChineseTextConverter.load(context, ChineseConversion.TRADITIONAL)!!
        val source = "他的头发随后被风吹起。"
        val boundary = source.indexOf('发')
        val left = readerChineseText(AnnotatedString(source.substring(0, boundary)), converter, source, 0)
        val right = readerChineseText(AnnotatedString(source.substring(boundary)), converter, source, boundary)
        assertEquals(converter.convert(source).text, left.text.text + right.text.text)
        assertTrue(right.text.text.startsWith("髮"))
        assertSame(converter.convert(source), converter.convert(source))
    }

    @Test fun offlineDictionariesDisambiguatePhrasesAndLeaveSourceUnchangedOnAndroidEight() = runBlocking {
        val source = AnnotatedString("头发、发展、皇后、后来。😀 English 123")
        val converter = ChineseTextConverter.load(context, ChineseConversion.TRADITIONAL)
        val display = readerChineseText(source, converter)
        assertEquals("頭髮、發展、皇后、後來。😀 English 123", display.text.text)
        assertEquals("头发、发展、皇后、后来。😀 English 123", source.text)
        for (offset in 0..source.length) assertEquals(offset, display.offsetMapping.originalToTransformed(offset))
        assertEquals(source, readerChineseText(display.text, ChineseTextConverter.load(context, ChineseConversion.SIMPLIFIED)).text)
        assertSame(source, readerChineseText(source, ChineseTextConverter.load(context, ChineseConversion.ORIGINAL)).text)
    }

    @Test fun supplementaryCharacterMappingsRetainStylesNotesAndMonotoneSourcePositions() = runBlocking {
        val source = AnnotatedString.Builder("甲㓆乙\n头发").apply {
            addStyle(SpanStyle(fontWeight = FontWeight.Bold), 1, 2)
            addStringAnnotation("note", "saved-note", 1, 2)
        }.toAnnotatedString()
        val display = readerParagraphText(source, TextStyle(fontSize = 20.sp), .8f,
            ChineseTextConverter.load(context, ChineseConversion.TRADITIONAL))
        assertEquals("甲𠗣乙\u200B頭髮", display.text.text)
        val note = display.text.getStringAnnotations("note", 0, display.text.length).single()
        assertEquals("𠗣", display.text.text.substring(note.start, note.end))
        assertEquals(1, display.offsetMapping.transformedToOriginal(note.start))
        assertEquals(2, display.offsetMapping.transformedToOriginal(note.end))
        var last = -1
        for (offset in 0..source.length) {
            val mapped = display.offsetMapping.originalToTransformed(offset)
            assertTrue(mapped >= last && mapped in 0..display.text.length)
            assertEquals(offset, display.offsetMapping.transformedToOriginal(mapped)); last = mapped
        }
        last = -1
        for (offset in 0..display.text.length) {
            val mapped = display.offsetMapping.transformedToOriginal(offset)
            assertTrue(mapped >= last && mapped in 0..source.length); last = mapped
        }
        assertEquals(source.length, display.offsetMapping.transformedToOriginal(display.text.length))
    }

    @Test fun convertedPaginationStillStoresContiguousOriginalTextCoordinates() = runBlocking {
        val source = "头发㓆发展，随后是正文。\n".repeat(80)
        val pages = readerPaginate(context, listOf(source), TextStyle(fontSize = 20.sp, lineHeight = 32.sp),
            Density(1f), LayoutDirection.Ltr, 240, 320,
            chineseConverter = ChineseTextConverter.load(context, ChineseConversion.TRADITIONAL)).single()
        assertTrue(pages.size > 2)
        assertEquals(0, pages.first().start)
        assertEquals(source.length, pages.last().end)
        pages.zipWithNext().forEach { (left, right) -> assertEquals(left.end, right.start) }
        pages.forEach { assertTrue(it.end > it.start) }
    }

    @Test fun scrollPageStatisticsUseTheSameConversionAsTheDisplayedPagedReader() = runBlocking {
        val source = "头发㓆发展，随后是正文。\n".repeat(50)
        val file = java.io.File.createTempFile("chinese-page-progress-", ".txt", context.cacheDir).apply { writeText(source) }
        try {
            val settings = ReaderSettings(chineseConversion = ChineseConversion.TRADITIONAL)
            val chapter = Chapter(title = "第一章", textPath = file.absolutePath)
            val book = Book(title = "简繁回归", mode = LibraryMode.NOVEL, format = BookFormat.TXT, chapters = listOf(chapter))
            val style = TextStyle(fontSize = 20.sp, lineHeight = 32.sp)
            val spec = ReaderLayoutSpec(style, 240, 320, Density(1f), LayoutDirection.Ltr)
            val expected = readerPaginate(context, listOf(source), style, spec.density, spec.layoutDirection, 240, 320,
                chineseConverter = ChineseTextConverter.load(context, settings.chineseConversion)).single()
            assertEquals(expected, readerProgressChapterPages(context, book, settings, spec, 0))
        } finally { file.delete() }
    }
}
