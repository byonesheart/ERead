package com.eyue.reader.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.eyue.reader.data.Book
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.Chapter
import com.eyue.reader.data.InlineImage
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.ReaderSettings
import com.eyue.reader.data.SourceTextStyle
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ReaderChapterPageCacheRobolectricTest {
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var book: Book
    private val text = "甲乙😀丙丁戊己"
    private val settings = ReaderSettings()
    private val pages = listOf(ReaderTextPage(0, 4), ReaderTextPage(4, text.length))

    @Before fun setUp() {
        val base = RuntimeEnvironment.getApplication()
        root = File(base.cacheDir, "chapter-cache-test-${UUID.randomUUID()}").apply { mkdirs() }
        context = newContext()
        book = Book(id = UUID.randomUUID().toString(), title = "章节缓存", format = BookFormat.MOBI,
            mode = LibraryMode.NOVEL, chapters = (0..1).map { index ->
                Chapter(id = "chapter-$index", title = "第${index + 1}章",
                    textPath = File(root, "chapter-$index.txt").apply { writeText(text) }.absolutePath)
            })
    }

    @After fun tearDown() { root.deleteRecursively() }

    private fun newContext() = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
    }

    private fun chapterFiles() = File(context.cacheDir, "reader-chapter-page-cache").listFiles().orEmpty().toList()

    private suspend fun cached(index: Int, target: Book = book, style: ReaderSettings = settings,
                               source: String = text, owner: Context = context,
                               calculate: suspend () -> List<ReaderTextPage>): List<ReaderTextPage> =
        readerCachedChapterPages(owner, target, index, style, Density(2f, 1f), LayoutDirection.Ltr,
            600, 900, source, paginate = calculate)

    @Test fun editedOtherChaptersDoNotInvalidateOrEvictAnExistingChapter() = runBlocking {
        cached(0) { pages }
        cached(1) { pages }
        val editedText = "新章节正文"
        File(book.chapters[0].textPath!!).writeText(editedText)
        cached(0, source = editedText) { listOf(ReaderTextPage(0, editedText.length)) }
        assertEquals(pages, cached(1, owner = newContext()) { error("Other chapter edits must not affect this layout") })
        // A cached chapter does not try to read the rest of the book, even if its source is inaccessible.
        File(book.chapters[0].textPath!!).delete()
        assertEquals(pages, cached(1, owner = newContext()) { error("Reopening must reuse the chapter disk cache") })
        assertEquals(3, chapterFiles().size)
    }

    @Test fun eachChapterKeepsThreeLayoutsWithoutEvictingOtherChaptersOrLegacyBookCache() = runBlocking {
        cached(0) { pages }
        chapterFiles().single().setLastModified(1_000L)
        cached(1) { pages }
        listOf(20f, 21f, 22f).forEach { size -> cached(0, style = settings.copy(fontSize = size)) { pages } }
        assertEquals(4, chapterFiles().size)
        assertEquals(pages, cached(1) { error("Chapter one changes cannot evict chapter two") })
        val allPages = listOf(pages, pages)
        readerCachedPages(context, book, settings, Density(2f), LayoutDirection.Ltr, 600, 900,
            listOf(text, text)) { allPages }
        assertEquals(4, chapterFiles().size)
        assertEquals(allPages, readerCachedPages(context, book, settings, Density(2f), LayoutDirection.Ltr,
            600, 900, listOf(text, text)) { error("Chapter layouts must coexist with existing whole-book layouts") })
        var recomputed = false
        cached(0) { recomputed = true; pages }
        assertTrue(recomputed)
        assertEquals(4, chapterFiles().size)
        removeReaderPageCaches(context, book.id)
        assertTrue(chapterFiles().isEmpty())
        assertTrue(File(context.cacheDir, "reader-page-cache").listFiles().orEmpty().isEmpty())
    }

    @Test fun presentationKeepsTheCacheButSourceFormatAndImageMetadataRecompute() = runBlocking {
        val image = File(root, "image.bin").apply { writeBytes(byteArrayOf(1)) }
        val chapter = book.chapters[0].copy(inlineImages = listOf(InlineImage(4, image.absolutePath)),
            sourceStyles = listOf(SourceTextStyle(0, 4, bold = true)))
        val illustrated = book.copy(chapters = listOf(chapter, book.chapters[1]))
        val result = listOf(ReaderTextPage(0, 4), ReaderTextPage(4, 4, 0), ReaderTextPage(4, text.length))
        var calls = 0
        cached(0, target = illustrated) { calls++; result }
        cached(0, target = illustrated, style = settings.copy(nightMode = true, brightness = .3f)) {
            error("Night and brightness do not affect page boundaries")
        }
        image.appendBytes(byteArrayOf(2))
        cached(0, target = illustrated) { calls++; result }
        val changed = illustrated.copy(chapters = listOf(chapter.copy(sourceStyles =
            listOf(SourceTextStyle(0, 4, bold = false))), book.chapters[1]))
        cached(0, target = changed) { calls++; result }
        cached(0, target = changed, style = settings.copy(fontSize = 25f)) { calls++; result }
        assertEquals(4, calls)
        assertEquals(3, chapterFiles().size)
    }

    @Test fun invalidRangesAndImageOrderAreNeverPublishedAndCorruptFilesRecompute() = runBlocking {
        val image = File(root, "image.bin").apply { writeBytes(byteArrayOf(1)) }
        val illustrated = book.copy(chapters = listOf(book.chapters[0].copy(inlineImages =
            listOf(InlineImage(4, image.absolutePath), InlineImage(4, image.absolutePath))), book.chapters[1]))
        val invalid = listOf(ReaderTextPage(0, 4), ReaderTextPage(4, 4, 1), ReaderTextPage(4, 4, 0),
            ReaderTextPage(4, text.length))
        cached(0, target = illustrated) { invalid }
        cached(0) { listOf(ReaderTextPage(0, 4), ReaderTextPage(5, text.length)) }
        assertTrue(chapterFiles().isEmpty())
        var calls = 0
        cached(0) { calls++; pages }
        chapterFiles().single().writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(pages, cached(0) { calls++; pages })
        RandomAccessFile(chapterFiles().single(), "rw").use { it.setLength(4L * 1024 * 1024 + 1) }
        assertEquals(pages, cached(0) { calls++; pages })
        assertEquals(3, calls)
    }

    @Test fun cancelledChapterPreparationDoesNotPublishIncompletePages() = runBlocking {
        val cancelled = launch { cached(0) { currentCoroutineContext().cancel(); pages } }
        cancelled.join()
        assertTrue(cancelled.isCancelled)
        assertTrue(chapterFiles().isEmpty())
        assertEquals(pages, cached(0) { pages })
        assertEquals(pages, cached(0) { error("A complete chapter should remain available on disk") })
    }
}
