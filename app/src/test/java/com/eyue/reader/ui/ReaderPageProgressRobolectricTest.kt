package com.eyue.reader.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.eyue.reader.data.Book
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.Chapter
import com.eyue.reader.data.InlineImage
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.ReaderSettings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderPageProgressRobolectricTest {
    private lateinit var root: File
    private lateinit var context: Context
    private val density = Density(1f, 1f)

    @Before fun setUp() {
        root = File(RuntimeEnvironment.getApplication().cacheDir, "page-progress-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        }
    }

    @After fun tearDown() { root.deleteRecursively() }

    @Test fun chapterOffsetsMapToActualScreenPagesAndShareThePagedReaderCache() = runBlocking {
        val text = (0 until 35).joinToString("\n") { "Paragraph $it. A quiet harbor and the boats beside the old stone bridge." }
        val book = book(text, "Another chapter.\n" + text)
        val settings = ReaderSettings(fontName = "系统黑体", fontSize = 20f, lineHeight = 1.5f, paragraphSpacing = .8f)
        val spec = spec(settings)
        val first = readerProgressChapterPages(context, book, settings, spec, 0)
        val second = readerProgressChapterPages(context, book, settings, spec, 1)
        assertTrue(first.size > readerScrollBlocks(text, emptyList()).size)
        assertEquals(0, first.first().start)
        assertEquals(text.length, first.last().end)
        first.zipWithNext().forEach { (before, after) -> assertEquals(before.end, after.start) }
        assertTrue(first.drop(1).any { it.start % 1024 != 0 })
        val all = mapOf(0 to first, 1 to second)
        assertEquals("本章 1 / ${second.size} 页", readerPageProgress(all, 2, 1, 0).label)
        assertEquals("本章 3 / ${second.size} 页", readerPageProgress(all, 2, 1, second[2].start).label)
        assertEquals("本章 3 / ${second.size} 页", readerPageProgress(mapOf(1 to second), 2, 1, second[2].start).label)
        val fromCache = readerCachedChapterPages(context, book, 0, settings, density, LayoutDirection.Ltr,
            spec.textWidth, spec.textHeight, text) { error("The paged reader must reuse these real screen boundaries") }
        assertEquals(first, fromCache)
    }

    @Test fun typographyAndViewportRecomputeScreenPagesWhilePresentationKeepsThem() = runBlocking {
        val text = (0 until 80).joinToString("\n") { "A short paragraph $it with words that wrap across the measured page." }
        val book = book(text)
        val settings = ReaderSettings(fontName = "系统黑体", fontSize = 16f, lineHeight = 1.5f, paragraphSpacing = 0f)
        val compact = readerProgressChapterPages(context, book, settings, spec(settings), 0)
        val largeSettings = settings.copy(fontSize = 28f, paragraphSpacing = 1.6f)
        val large = readerProgressChapterPages(context, book, largeSettings, spec(largeSettings), 0)
        assertTrue(large.size > compact.size)
        assertNotEquals(compact[1].start, large[1].start)
        val taller = readerProgressChapterPages(context, book, largeSettings, spec(largeSettings, 800), 0)
        assertTrue(taller.size < large.size)
        val nightSettings = settings.copy(nightMode = true, ttsSpeed = 1.5f, ttsFollowPage = true, brightness = .2f)
        assertEquals(compact, readerCachedChapterPages(context, book, 0, nightSettings, density, LayoutDirection.Ltr,
            spec(settings).textWidth, spec(settings).textHeight, text) { error("Presentation must not change screen pages") })
    }

    @Test fun chapterPageLabelsNeverWaitForOrIncludeOtherChapterCounts() {
        val pages = listOf(ReaderTextPage(0, 20), ReaderTextPage(20, 40), ReaderTextPage(40, 60))
        assertEquals("本章页数计算中", readerPageProgress(emptyMap(), 3, 1, 27).label)
        assertEquals("本章 2 / 3 页", readerPageProgress(mapOf(1 to pages), 3, 1, 27).label)
        assertEquals("本章 2 / 3 页", readerPageProgress(mapOf(0 to pages, 1 to pages, 2 to pages), 3, 1, 27).label)
        assertEquals("页数暂不可用", readerPageProgress(mapOf(1 to pages), 3, 1, 27, failed = true).label)
    }

    @Test fun countingTheCurrentChapterDoesNotReadAnUnavailableDistantChapter() = runBlocking {
        val text = (0 until 35).joinToString("\n") { "Paragraph $it. A quiet harbor and the old stone bridge." }
        val original = book(text, "Unavailable chapter")
        val missing = File(requireNotNull(original.chapters[1].textPath)).apply { check(delete()) }
        val book = original.copy(chapters = listOf(original.chapters[0], original.chapters[1].copy(textPath = missing.absolutePath)))
        val settings = ReaderSettings(fontSize = 20f)
        val pages = readerProgressChapterPages(context, book, settings, spec(settings), 0)
        assertTrue(pages.size > 1)
        assertEquals("本章 1 / ${pages.size} 页", readerPageProgress(mapOf(0 to pages), 2, 0, 0).label)
        assertEquals(1, File(context.cacheDir, "reader-chapter-page-cache").listFiles().orEmpty().count { it.extension == "bin" })
        assertTrue("A chapter count must not create a whole-book page index",
            File(context.cacheDir, "reader-book-page-index").listFiles().orEmpty().isEmpty())
    }

    @Test fun inlineImagePagesCountAndMapSeparatelyFromTheirNeighboringText() {
        val first = listOf(ReaderTextPage(0, 10), ReaderTextPage(10, 10, 0),
            ReaderTextPage(10, 10, 1), ReaderTextPage(10, 30))
        val all = mapOf(0 to first, 1 to listOf(ReaderTextPage(0, 4)))
        assertEquals("本章 2 / 4 页", readerPageProgress(all, 2, 0, 10, imageIndex = 0).label)
        assertEquals("本章 3 / 4 页", readerPageProgress(all, 2, 0, 10, imageIndex = 1).label)
        assertEquals("本章 4 / 4 页", readerPageProgress(all, 2, 0, 10).label)
        assertEquals("本章 1 / 1 页", readerPageProgress(all, 2, 1, 4).label)
    }

    @Test fun actualInlineImagePaginationUsesOneSeparateScreenPage() = runBlocking {
        val text = "Opening paragraph.\n" + "Body text beside the harbor.\n".repeat(30)
        val original = book(text)
        val offset = text.indexOf('\n') + 1
        val illustrated = original.copy(chapters = listOf(original.chapters.single().copy(
            inlineImages = listOf(InlineImage(offset, File(root, "page.png").apply { writeBytes(byteArrayOf(1)) }.absolutePath)))))
        val settings = ReaderSettings(fontName = "系统黑体", fontSize = 20f, lineHeight = 1.5f)
        val pages = readerProgressChapterPages(context, illustrated, settings, spec(settings), 0)
        val image = pages.indexOfFirst { it.imageIndex == 0 }
        assertTrue(image >= 0)
        assertEquals(offset, pages[image].start)
        assertEquals(pages[image].start, pages[image].end)
        assertEquals("本章 ${image + 1} / ${pages.size} 页", readerPageProgress(mapOf(0 to pages), 1, 0, offset, 0).label)
    }

    private fun spec(settings: ReaderSettings, height: Int = 360) =
        readerLayoutSpec(settings, density, LayoutDirection.Ltr, IntSize(320, height))

    private fun book(vararg texts: String) = Book(title = "Screen pages", format = BookFormat.MOBI,
        mode = LibraryMode.NOVEL, chapters = texts.mapIndexed { index, text -> Chapter(title = "Chapter $index",
            textPath = File(root, "${UUID.randomUUID()}.txt").apply { writeText(text) }.absolutePath) })
}
