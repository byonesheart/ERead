package com.eyue.reader.ui

import android.content.Context
import android.content.ContextWrapper
import com.eyue.reader.data.Book
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.Chapter
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.PreparedChapter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ReaderChapterStoreTest {
    private lateinit var root: File
    private lateinit var context: Context

    @Before fun setUp() {
        val base = RuntimeEnvironment.getApplication()
        root = File(base.cacheDir, "chapter-store-test-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        }
    }

    @After fun tearDown() { root.deleteRecursively() }

    private fun book(chapters: List<Chapter> = (0..19).map {
        Chapter(id = "chapter-$it", title = "第${it + 1}章", characterCount = 1_000)
    }) = Book(id = root.name, title = "按章加载", format = BookFormat.MOBI,
        mode = LibraryMode.NOVEL, chapters = chapters, chapterIndex = 5)

    @Test fun requestingTheResumeChapterAndNeighborsLeavesOtherChaptersUnread() = runBlocking {
        val book = book()
        val requested = mutableListOf<Int>()
        val store = ReaderChapterStore(context, book) { index ->
            requested += index
            PreparedChapter(book.chapters[index], "第${index + 1}章正文😀")
        }
        val current = store.load(book.chapterIndex)
        store.load(book.chapterIndex + 1)
        store.load(book.chapterIndex - 1)
        assertSame(current, store.load(book.chapterIndex))
        assertEquals(listOf(5, 6, 4), requested)
        assertEquals("第6章正文😀", current.text)
        assertEquals(current.text, current.blocks.joinToString("") { current.text.substring(it.start, it.end) })
    }

    @Test fun concurrentRequestsForOneChapterShareASingleLoad() = runBlocking {
        val book = book()
        val reads = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = ReaderChapterStore(context, book) { index ->
            reads.incrementAndGet()
            started.complete(Unit)
            release.await()
            PreparedChapter(book.chapters[index], "并发正文😀")
        }
        val jobs = List(20) { async { store.load(5) } }
        started.await()
        assertEquals(1, reads.get())
        release.complete(Unit)
        val chapters = jobs.awaitAll()
        assertEquals(1, reads.get())
        assertTrue(chapters.all { it === chapters.first() })
    }

    @Test fun canceledLoadsAreNotMemoizedAndCanBeRetried() = runBlocking {
        val book = book()
        var reads = 0
        val started = CompletableDeferred<Unit>()
        val store = ReaderChapterStore(context, book) { index ->
            reads++
            if (reads == 1) {
                started.complete(Unit)
                awaitCancellation()
            }
            PreparedChapter(book.chapters[index], "完整重试正文")
        }
        val canceled = async { store.load(5) }
        started.await()
        canceled.cancelAndJoin()
        assertTrue(canceled.isCancelled)
        val loaded = store.load(5)
        assertEquals("完整重试正文", loaded.text)
        assertSame(loaded, store.load(5))
        assertEquals(2, reads)
    }

    @Test fun legacyLengthCatalogCountsUtf16AndInvalidatesChangedSourceMetadata() = runBlocking {
        // The surrogate pair crosses the streaming reader's 8,192-character boundary.
        val longText = "甲".repeat(8_191) + "😀末尾"
        val first = File(root, "first.txt").apply { writeText(longText) }
        val second = File(root, "second.txt").apply { writeText("😀😀") }
        val book = book(listOf(
            Chapter(id = "first", title = "第一章", textPath = first.absolutePath),
            Chapter(id = "second", title = "第二章", textPath = second.absolutePath)))
        assertEquals(listOf(longText.length, 4), readerChapterLengths(context, book))
        val catalog = File(context.cacheDir, "reader-chapter-lengths").listFiles().orEmpty().single()
        assertTrue(catalog.length() > 0)
        assertEquals(listOf(longText.length, 4), readerChapterLengths(context, book.copy(progress = .8f)))
        val previousTime = second.lastModified()
        val previousBytes = second.length()
        second.writeText("abcdefgh")
        assertEquals(previousBytes, second.length())
        second.setLastModified(previousTime + 5_000)
        assertEquals(listOf(longText.length, 8), readerChapterLengths(context, book))
        assertEquals(listOf(longText.length, 8), readerChapterLengths(context, book))
    }

    @Test fun knownCountsDoNotReadInaccessibleChapterSources() = runBlocking {
        val unreadableSource = File(root, "directory-not-text").apply { mkdirs() }
        val unknownSource = File(root, "missing.txt")
        val book = book(listOf(
            Chapter(title = "首章", textPath = unreadableSource.absolutePath, characterCount = 123),
            Chapter(title = "末章", textPath = unknownSource.absolutePath, characterCount = 456)))
        assertEquals(listOf(123, 456), readerChapterLengths(context, book))
        assertTrue(File(context.cacheDir, "reader-chapter-lengths").listFiles().orEmpty().isEmpty())
        val legacy = File(root, "legacy.txt").apply { writeText("原缓存😀") }
        val mixed = book.copy(chapters = book.chapters + Chapter(title = "旧章", textPath = legacy.absolutePath))
        assertEquals(listOf(123, 456, "原缓存😀".length), readerChapterLengths(context, mixed))
    }
}
