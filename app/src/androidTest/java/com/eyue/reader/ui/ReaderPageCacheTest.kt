package com.eyue.reader.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eyue.reader.data.Book
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.Chapter
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.ReaderSettings
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
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReaderPageCacheTest {
    private lateinit var base: Context
    private lateinit var root: File
    private lateinit var context: Context
    private val settings = ReaderSettings()
    private val texts = listOf("abcdefgh")
    private val pages = listOf(listOf(ReaderTextPage(0, 4), ReaderTextPage(4, 8)))

    @Before fun setUp() {
        base = ApplicationProvider.getApplicationContext()
        root = File(base.cacheDir, "page-cache-test-${UUID.randomUUID()}").apply { mkdirs() }
        context = newContext()
    }

    @After fun tearDown() { root.deleteRecursively() }

    private fun newContext() = object : ContextWrapper(base) {
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
    }

    private fun book(content: List<String> = texts): Book = Book(id = root.name, title = "缓存测试", format = BookFormat.MOBI,
        mode = LibraryMode.NOVEL, chapters = content.mapIndexed { index, text ->
            Chapter(title = "第 $index 章", textPath = File(root, "chapter-$index.txt").apply { writeText(text) }.absolutePath)
        })

    private suspend fun cached(book: Book, style: ReaderSettings = settings, content: List<String> = texts,
        images: List<List<String>> = content.map { emptyList() }, owner: Context = context,
        calculate: suspend () -> List<List<ReaderTextPage>>): List<List<ReaderTextPage>> =
        readerCachedPages(owner, book, style, Density(2f, 1f), LayoutDirection.Ltr, 600, 900, content, images, calculate)

    @Test fun completeLayoutPersistsAndFreshContextReadsWithoutPagination() = runBlocking {
        val book = book()
        var calls = 0
        assertEquals(pages, cached(book) { calls++; pages })
        assertEquals(pages, cached(book, owner = newContext()) { error("Disk cache should survive a new context") })
        assertEquals(1, calls)
    }

    @Test fun presentationChangesHitButFontChangesRecompute() = runBlocking {
        val book = book()
        var calls = 0
        cached(book) { calls++; pages }
        cached(book, style = settings.copy(nightMode = true, brightness = .2f)) { error("Presentation cannot invalidate layout") }
        val changed = settings.copy(fontSize = 24f)
        cached(book, style = changed) { calls++; pages }
        cached(book, style = changed) { error("New font layout should now be cached") }
        assertEquals(2, calls)
    }

    @Test fun returningToARecentFontReusesItsLayoutAndBookRemovalClearsAllLayouts() = runBlocking {
        val book = book()
        cached(book) { pages }
        cached(book, style = settings.copy(fontSize = 23f)) { pages }
        assertEquals(pages, cached(book) { error("Returning to the previous font must reuse its layout") })
        assertEquals(2, File(context.cacheDir, "reader-page-cache").listFiles()!!.size)
        removeReaderPageCaches(context, book.id)
        assertTrue(File(context.cacheDir, "reader-page-cache").listFiles().orEmpty().isEmpty())
    }

    @Test fun onlyThreeRecentLayoutsAreKeptPerBook() = runBlocking {
        val book = book()
        cached(book) { pages }
        File(context.cacheDir, "reader-page-cache").listFiles()!!.single().setLastModified(1_000L)
        listOf(20f, 21f, 22f).forEach { size -> cached(book, style = settings.copy(fontSize = size)) { pages } }
        assertEquals(3, File(context.cacheDir, "reader-page-cache").listFiles()!!.size)
        assertEquals(pages, cached(book, style = settings.copy(fontSize = 22f)) { error("The latest layout must survive eviction") })
        var recalculated = false
        cached(book) { recalculated = true; pages }
        assertTrue(recalculated)
        assertEquals(3, File(context.cacheDir, "reader-page-cache").listFiles()!!.size)
    }

    @Test fun changedChapterAndInlineImageFileRecompute() = runBlocking {
        val book = book()
        val image = File(root, "image.bin").apply { writeBytes(byteArrayOf(1)) }
        val identities = listOf(listOf("4:${image.absolutePath}"))
        val illustrated = listOf(listOf(ReaderTextPage(0, 4), ReaderTextPage(4, 4, 0), ReaderTextPage(4, 8)))
        var calls = 0
        cached(book, images = identities) { calls++; illustrated }
        cached(book, images = identities) { error("Same image layout should hit") }
        image.appendBytes(byteArrayOf(2))
        cached(book, images = identities) { calls++; illustrated }
        val newText = listOf("ABCDEFGH")
        File(book.chapters.single().textPath!!).writeText(newText.single())
        cached(book, content = newText, images = identities) { calls++; illustrated }
        assertEquals(3, calls)
    }

    @Test fun cancellationAfterCalculationNeverPublishesCache() = runBlocking {
        val book = book()
        val cancelled = launch {
            cached(book) { currentCoroutineContext().cancel(); pages }
        }
        cancelled.join()
        assertTrue(cancelled.isCancelled)
        var recomputed = false
        cached(book) { recomputed = true; pages }
        assertTrue(recomputed)
    }

    @Test fun truncatedInvalidRangesHugeCountsAndOversizedFilesAreCacheMisses() = runBlocking {
        val book = book()
        var calls = 0
        suspend fun load() = cached(book) { calls++; pages }
        load()
        val file = File(context.cacheDir, "reader-page-cache").listFiles()!!.single()
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(pages, load())
        val header = DataInputStream(file.inputStream()).use { Triple(it.readInt(), it.readInt(), it.readUTF()) }
        fun corrupt(count: Int, end: Int = 9) {
            DataOutputStream(file.outputStream()).use {
                it.writeInt(header.first); it.writeInt(header.second); it.writeUTF(header.third)
                it.writeInt(1); it.writeInt(count)
                if (count == 1) { it.writeInt(0); it.writeInt(end); it.writeInt(-1) }
            }
        }
        corrupt(1)
        assertEquals(pages, load())
        corrupt(Int.MAX_VALUE)
        assertEquals(pages, load())
        RandomAccessFile(file, "rw").use { it.setLength(4L * 1024 * 1024 + 1) }
        assertEquals(pages, load())
        assertEquals(5, calls)
    }

    @Test fun imageSlotsBeforeTheirTextOrWithWrongIndexAreNotWritten() = runBlocking {
        val book = book()
        val image = File(root, "picture.bin").apply { writeBytes(byteArrayOf(1)) }
        val identities = listOf(listOf("4:${image.absolutePath}"))
        val invalid = listOf(listOf(ReaderTextPage(4, 4, 0), ReaderTextPage(0, 8)))
        cached(book, images = identities) { invalid }
        val wrongIndex = listOf(listOf(ReaderTextPage(0, 4), ReaderTextPage(4, 4, 2), ReaderTextPage(4, 8)))
        cached(book, images = identities) { wrongIndex }
        assertTrue(File(context.cacheDir, "reader-page-cache").listFiles().orEmpty().isEmpty())
        val valid = listOf(listOf(ReaderTextPage(0, 4), ReaderTextPage(4, 4, 0), ReaderTextPage(4, 8)))
        cached(book, images = identities) { valid }
        assertEquals(valid, cached(book, images = identities) { error("Validated image layout should hit") })
    }

    @Test fun imageOnlyAndEmptyChaptersKeepTheirDistinctPageSlots() = runBlocking {
        val content = listOf("", "")
        val book = book(content)
        val image = File(root, "picture.bin").apply { writeBytes(byteArrayOf(1)) }
        val identities = listOf(listOf("0:${image.absolutePath}", "0:${image.absolutePath}"), emptyList())
        val result = listOf(listOf(ReaderTextPage(0, 0, 0), ReaderTextPage(0, 0, 1)), listOf(ReaderTextPage(0, 0)))
        cached(book, content = content, images = identities) { result }
        assertEquals(result, cached(book, content = content, images = identities) { error("Empty/image-only slots should persist") })
    }
}
