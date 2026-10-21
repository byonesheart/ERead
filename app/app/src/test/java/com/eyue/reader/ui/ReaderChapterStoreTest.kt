package com.eyue.reader.ui

import android.content.Context
import android.content.ContextWrapper
import com.eyue.reader.data.Book
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.Chapter
import com.eyue.reader.data.ChapterTextReadException
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.PageSource
import com.eyue.reader.data.PreparedChapter
import com.eyue.reader.data.readChapterText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    @Test fun crossingManyChaptersReleasesDocumentDataButKeepsLightweightScrollSlots() = runBlocking {
        val book = book()
        val requested = mutableListOf<Int>()
        val store = ReaderChapterStore(context, book) { index ->
            requested += index
            PreparedChapter(book.chapters[index], ("第${index + 1}章正文\n".repeat(80)))
        }
        var document = ReaderDocument(List(book.chapters.size) { "" }, emptyList(), emptyList(),
            List(book.chapters.size) { null }, List(book.chapters.size) { emptyList() }, chapters = book.chapters,
            scrollItemCounts = List(book.chapters.size) { 1 })
        for (center in book.chapters.indices) {
            store.retainWindow(center)
            for (index in (center - 1).coerceAtLeast(0)..(center + 1).coerceAtMost(book.chapters.lastIndex)) {
                val loaded = store.load(index)
                document = readerRetainChapterWindow(document.copy(
                    text = document.text.toMutableList().apply { this[index] = loaded.text },
                    sourceStyles = document.sourceStyles.toMutableList().apply { this[index] = loaded.styles },
                    scrollBlocks = document.scrollBlocks.toMutableList().apply { this[index] = loaded.blocks },
                    readyChapters = document.readyChapters + index,
                    scrollItemCounts = document.scrollItemCounts.toMutableList().apply { this[index] = loaded.blocks.size }), center, book.chapters)
            }
            assertTrue(document.text.count { it.isNotEmpty() } <= 3)
            assertTrue(document.sourceStyles.count { it != null } <= 3)
            assertTrue(document.scrollBlocks.count { it.isNotEmpty() } <= 3)
            assertTrue(document.readyChapters.all { it in (center - 1)..(center + 1) })
        }
        assertTrue(document.scrollItemCounts.all { it > 0 })
        assertEquals(readerScrollBlocks("第1章正文\n".repeat(80), emptyList()).size, document.scrollItemCounts.first())
        assertEquals("", document.text.first())
        store.retainWindow(0)
        store.load(0)
        assertEquals(2, requested.count { it == 0 })
    }

    @Test fun lateLoadOutsideTheNewWindowCannotRepopulateTheStore() = runBlocking {
        val book = book()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val reads = AtomicInteger()
        val store = ReaderChapterStore(context, book) { index ->
            if (reads.incrementAndGet() == 1) { started.complete(Unit); release.await() }
            PreparedChapter(book.chapters[index], "章节正文")
        }
        store.retainWindow(0)
        val old = async { store.load(0) }
        started.await()
        store.retainWindow(10)
        release.complete(Unit)
        old.await()
        store.retainWindow(0)
        store.load(0)
        assertEquals(2, reads.get())
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

    @Test fun successfulEmptyChapterRemainsValidForReadingPreparationAndLegacyCounts() = runBlocking {
        val file = File(root, "empty.txt").apply { writeText("") }
        val chapter = Chapter(title = "合法空章", textPath = file.absolutePath)
        val book = book(listOf(chapter))
        val store = ReaderChapterStore(context, book)
        assertEquals("", store.readText(0))
        assertEquals("", store.load(0).text)
        assertEquals(listOf(ReaderTextPage(0, 0)), store.load(0).blocks)
        assertEquals(listOf(0), readerChapterLengths(context, book))
    }

    @Test fun missingAndCorruptTextRetainTheChapterErrorAndCanBeRetriedAfterRepair() = runBlocking {
        val missing = File(root, "missing.txt")
        val corrupt = File(root, "corrupt.txt").apply { writeBytes(byteArrayOf(0xc3.toByte(), 0x28)) }
        for (file in listOf(missing, corrupt)) {
            val chapter = Chapter(title = "损坏章节 ${file.name}", textPath = file.absolutePath)
            val book = book(listOf(chapter))
            val store = ReaderChapterStore(context, book)
            for (failure in listOf(runCatching { store.readText(0) }.exceptionOrNull(),
                runCatching { store.load(0) }.exceptionOrNull(),
                runCatching { readerChapterLengths(context, book) }.exceptionOrNull())) {
                assertTrue("A read error must never become a successful empty chapter", failure is ChapterTextReadException)
                assertEquals(chapter.title, (failure as ChapterTextReadException).chapterTitle)
                assertTrue(failure.message.orEmpty().contains(chapter.title))
                assertNotNull(failure.cause)
            }
            file.writeText("已修复的正文😀")
            assertEquals("已修复的正文😀", store.readText(0))
            assertEquals("已修复的正文😀", store.load(0).text)
            assertEquals(listOf("已修复的正文😀".length), readerChapterLengths(context, book))
        }
    }

    @Test fun absentOcrIsValidButBrokenOcrAndMissingNovelReferencesAreErrors() = runBlocking {
        val missing = File(root, "missing-ocr.txt")
        val empty = File(root, "empty-ocr.txt").apply { writeText("") }
        val chapter = Chapter(title = "PDF 正文", pages = listOf(PageSource("page0.png"),
            PageSource("page1.png", textPath = missing.absolutePath), PageSource("page2.png", textPath = empty.absolutePath)))
        val pdf = book(listOf(chapter)).copy(format = BookFormat.PDF)
        val store = ReaderChapterStore(context, pdf)
        assertEquals("", store.readPageText(0, 0))
        assertEquals("", store.readPageText(0, 2))
        assertEquals("", store.load(0).text)
        val failure = runCatching { store.readPageText(0, 1) }.exceptionOrNull()
        assertTrue(failure is ChapterTextReadException)
        assertTrue(failure?.message.orEmpty().contains("第 2 页"))
        val novel = ReaderChapterStore(context, book(listOf(Chapter(title = "缺少正文路径"))))
        assertTrue(runCatching { novel.readText(0) }.exceptionOrNull() is ChapterTextReadException)
    }

    @Test fun searchAndSpeechUseThePreparedWindowWithoutPreparingOrRetainingDistantChapters() = runBlocking {
        val paths = (0..5).map { File(root, "raw-$it.txt").apply { writeText("正文 $it") } }
        val book = book(paths.mapIndexed { index, file -> Chapter(title = "第 $index 章", textPath = file.absolutePath) })
        val prepared = mutableListOf<Int>()
        val store = ReaderChapterStore(context, book) { index ->
            prepared += index
            PreparedChapter(book.chapters[index], "已准备的 $index")
        }
        store.retainWindow(1)
        store.load(1)
        assertEquals("已准备的 1", store.readText(1))
        assertEquals("正文 5", store.readText(5))
        paths[5].writeText("远章正文已变更")
        assertEquals("远章正文已变更", store.readText(5))
        assertEquals(listOf(1), prepared)
        store.retainWindow(5)
        assertEquals("正文 1", store.readText(1))
    }

    @Test fun textReadsPreserveCancellationInsteadOfWrappingItAsDamagedCache() {
        val chapter = Chapter(title = "可取消章节", textPath = File(root, "cancel.txt").apply {
            writeText("长正文😀".repeat(10_000))
        }.absolutePath)
        var checks = 0
        val cancellation = CancellationException("stop reading")
        val failure = runCatching { readChapterText(chapter) { if (++checks == 3) throw cancellation } }.exceptionOrNull()
        assertSame(cancellation, failure)
    }

    @Test fun corruptSourceMetadataReportsItsChapterWhilePlainTextRemainsReadable() = runBlocking {
        val chapter = Chapter(title = "源排版损坏章节", textPath = File(root, "body.txt").apply {
            writeText("正文缓存仍然完整")
        }.absolutePath, lazySourcePath = File(root, "source.json").apply { writeText("broken json") }.absolutePath)
        val store = ReaderChapterStore(context, book(listOf(chapter)))
        val failure = runCatching { store.load(0) }.exceptionOrNull()
        assertTrue(failure is ChapterTextReadException)
        assertEquals(chapter.title, (failure as ChapterTextReadException).chapterTitle)
        assertNotNull(failure.cause)
        assertEquals("正文缓存仍然完整", store.readText(0))
    }
}
