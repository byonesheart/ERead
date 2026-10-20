package com.eyue.reader.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.eyue.reader.data.Book
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.Chapter
import com.eyue.reader.data.ChineseConversion
import com.eyue.reader.data.InlineImage
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.ReaderBackground
import com.eyue.reader.data.ReaderSettings
import com.eyue.reader.data.SourceTextStyle
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ReaderBookPageIndexRobolectricTest {
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var book: Book
    private val text = "甲乙丙丁戊己庚辛壬癸"
    private val pages = listOf(ReaderTextPage(0, 4), ReaderTextPage(4, 10))
    private val settings = ReaderSettings()
    private val revisions = ConcurrentHashMap<String, Int>()
    private val metadataReads = AtomicInteger()
    private val trusted: (File) -> String? = { file ->
        metadataReads.incrementAndGet()
        if (file.isFile) "${file.absolutePath}:${file.length()}:${file.lastModified()}:${revisions[file.absolutePath] ?: 0}" else null
    }

    @Before fun setUp() {
        root = File(RuntimeEnvironment.getApplication().cacheDir, "book-index-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        }
        book = Book(id = "index-book", title = "小说", format = BookFormat.MOBI, mode = LibraryMode.NOVEL,
            chapters = (0..4).map { index -> Chapter(id = "chapter-$index", title = "第${index + 1}章",
                textPath = File(root, "$index.txt").apply { writeText(text) }.absolutePath, characterCount = text.length) })
    }

    @After fun tearDown() { root.deleteRecursively() }

    private suspend fun session(target: Book = book, style: ReaderSettings = settings, width: Int = 600,
        height: Int = 900, density: Density = Density(2f, 1f), direction: LayoutDirection = LayoutDirection.Ltr,
        identity: (File) -> String? = trusted) = readerBookPageIndex(context, target, style, density, direction, width, height, identity)
    private suspend fun fill(index: ReaderBookPageIndex, target: Book = book) {
        target.chapters.indices.forEach { index.record(it, pages, text, target.chapters[it], "a".repeat(64)) }
    }
    private fun files() = File(context.cacheDir, "reader-book-page-index").listFiles().orEmpty().toList()

    @Test fun completedBookRestoresEveryPageAndLengthFromOneSnapshot() = runBlocking {
        val cold = session()
        fill(cold)
        assertTrue(cold.complete)
        assertEquals(1, files().size)
        assertTrue(files().single().isFile)
        val hot = session(book.copy(progress = .5f, pageIndex = 1, lastReadAt = 10))
        assertTrue(hot.complete)
        assertEquals(book.chapters.indices.associateWith { pages }, hot.pages)
        assertEquals(book.chapters.indices.associateWith { text.length }, hot.lengths)
        assertEquals(10, hot.pages.values.sumOf { it.size })
    }

    @Test fun partialCheckpointsSurviveReopenAndConsolidateOnlyWhenComplete() = runBlocking {
        val cold = session()
        cold.record(3, pages, text, cacheKey = "a".repeat(64))
        cold.record(0, pages, text, cacheKey = "a".repeat(64))
        val checkpoint = files().single()
        assertTrue(checkpoint.isDirectory)
        assertEquals(2, checkpoint.listFiles()!!.size)
        val resumed = session()
        assertEquals(setOf(0, 3), resumed.pages.keys)
        assertFalse(resumed.complete)
        fill(resumed)
        assertTrue(session().complete)
        assertEquals(1, files().size)
        assertTrue(files().single().extension == "bin")
    }

    @Test fun recordingOneChapterDoesNotRescanOtherChapterMetadata() = runBlocking {
        val index = session()
        metadataReads.set(0)
        index.record(0, pages, text, cacheKey = "a".repeat(64))
        assertEquals(1, metadataReads.get())
        index.record(1, pages, text, cacheKey = "a".repeat(64))
        assertEquals(2, metadataReads.get())
    }

    @Test fun legacyChapterCacheHitAccumulatesIndexWithoutPaginatingAgain() = runBlocking {
        readerCachedChapterPages(context, book, 0, settings, Density(2f, 1f), LayoutDirection.Ltr,
            600, 900, text) { pages }
        val index = session()
        assertEquals(pages, readerCachedChapterPages(context, book, 0, settings, Density(2f, 1f), LayoutDirection.Ltr,
            600, 900, text, bookPageIndex = index) { error("The existing chapter cache should be reused") })
        assertEquals(setOf(0), session().pages.keys)
    }

    @Test fun layoutInputsInvalidateButColorAndPlaybackDoNot() = runBlocking {
        fill(session())
        assertTrue(session(style = settings.copy(nightMode = true, background = ReaderBackground.DARK,
            brightness = .2f, ttsSpeed = 1.8f, ttsFollowPage = true)).complete)
        listOf(settings.copy(fontSize = 21f), settings.copy(lineHeight = 1.5f), settings.copy(paragraphSpacing = 1.2f),
            settings.copy(letterSpacing = .1f), settings.copy(fontName = "系统宋体"), settings.copy(bold = true),
            settings.copy(chineseConversion = ChineseConversion.TRADITIONAL), settings.copy(useSourceCss = true)).forEach {
            assertTrue(session(style = it).pages.isEmpty())
        }
        assertTrue(session(width = 599).pages.isEmpty())
        assertTrue(session(height = 899).pages.isEmpty())
        assertTrue(session(density = Density(2f, 1.1f)).pages.isEmpty())
        assertTrue(session(direction = LayoutDirection.Rtl).pages.isEmpty())
        assertTrue(session(book.copy(contentVersion = 2)).pages.isEmpty())
    }

    @Test fun filesystemChangeTokenDetectsSameSizeRewriteWithRestoredMtime() = runBlocking {
        fill(session())
        val source = File(book.chapters[4].textPath!!)
        val originalTime = source.lastModified()
        source.writeText("癸壬辛庚己戊丁丙乙甲")
        source.setLastModified(originalTime)
        revisions[source.absolutePath] = 1 // The platform's ctime changes even when users restore mtime.
        assertTrue(session().pages.isEmpty())
    }

    @Test fun preparedCacheCreatedOnFirstLoadDoesNotInvalidateFirstCompleteSnapshot() = runBlocking {
        val target = book.copy(chapters = book.chapters.mapIndexed { index, chapter -> chapter.copy(
            lazySourcePath = File(root, "$index.xhtml").apply { writeText("<p>$text</p>") }.absolutePath) })
        val cold = session(target)
        target.chapters.forEach { File("${it.lazySourcePath}.prepared.json").writeText("prepared") }
        fill(cold, target)
        assertTrue(session(target).complete)
        val prepared = File("${target.chapters.last().lazySourcePath}.prepared.json")
        revisions[prepared.absolutePath] = 1
        assertTrue(session(target).pages.isEmpty())
    }

    @Test fun fontImageAndCssChangesInvalidateWithoutLoadingTheirContents() = runBlocking {
        val font = File(root, "font.ttf").apply { writeText("font") }
        val image = File(root, "image.png").apply { writeText("image") }
        val css = File(root, "style.css").apply { writeText("p{}") }
        val raw = File(root, "source.xhtml").apply { writeText("<p>$text</p>") }
        val target = book.copy(chapters = book.chapters.map { it.copy(lazySourcePath = raw.path) })
        val style = settings.copy(fontPath = font.path)
        fill(session(target, style), target)
        for (file in listOf(font, css)) {
            revisions[file.absolutePath] = 1
            assertTrue(session(target, style).pages.isEmpty())
            revisions.remove(file.absolutePath)
        }
        val pictured = target.copy(chapters = target.chapters.map { it.copy(inlineImages = listOf(InlineImage(4, image.path))) })
        val picturePages = listOf(ReaderTextPage(0, 4), ReaderTextPage(4, 4, 0), ReaderTextPage(4, 10))
        val imageIndex = session(pictured, style)
        pictured.chapters.indices.forEach { imageIndex.record(it, picturePages, text, cacheKey = "a".repeat(64)) }
        assertTrue(session(pictured, style).complete)
        revisions[image.absolutePath] = 1
        assertTrue(session(pictured, style).pages.isEmpty())
    }

    @Test fun sourceCssProjectionAndEachSpacingFieldParticipateInChapterKeys() {
        fun key(style: SourceTextStyle, useCss: Boolean = true): String {
            val target = book.copy(chapters = listOf(book.chapters[0].copy(sourceCssStyles = listOf(style))))
            return readerLayoutCacheKey(target, settings.copy(useSourceCss = useCss), Density(2f), LayoutDirection.Ltr,
                600, 900, listOf(text), listOf(emptyList()), "test-platform")
        }
        val source = SourceTextStyle(0, text.length)
        val base = key(source)
        assertNotEquals(base, key(source, false))
        listOf(source.copy(lineHeightEm = 1.5f), source.copy(letterSpacingEm = .1f),
            source.copy(spaceBeforeEm = .5f), source.copy(spaceAfterEm = .5f)).forEach { assertNotEquals(base, key(it)) }
        assertEquals(key(source, false), key(source.copy(lineHeightEm = 1.5f), false))
    }

    @Test fun cancellationDoesNotPublishAnUnwrittenRowAndRetryCanPersistIt() = runBlocking {
        val cancel = AtomicBoolean(false)
        lateinit var work: Job
        val identity: (File) -> String? = { file ->
            if (cancel.get()) work.cancel()
            trusted(file)
        }
        val index = session(identity = identity)
        cancel.set(true)
        work = launch(start = CoroutineStart.LAZY) { index.record(0, pages, text, cacheKey = "a".repeat(64)) }
        work.start(); work.join()
        assertTrue(work.isCancelled)
        assertTrue(index.pages.isEmpty())
        assertTrue(session().pages.isEmpty())
        cancel.set(false)
        index.record(0, pages, text, cacheKey = "a".repeat(64))
        assertEquals(setOf(0), session().pages.keys)
    }

    @Test fun writeFailureLeavesRetryableSessionAndCorruptionIsIgnored() = runBlocking {
        val index = session()
        val directory = File(context.cacheDir, "reader-book-page-index").apply { writeText("blocked") }
        index.record(0, pages, text, cacheKey = "a".repeat(64))
        assertTrue(index.pages.isEmpty())
        directory.delete()
        fill(index)
        val snapshot = files().single()
        val bytes = snapshot.readBytes().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        snapshot.writeBytes(bytes)
        assertTrue(session().pages.isEmpty())
    }

    @Test fun unknownCharacterCountsUseRecordedLengthsButKnownMismatchInvalidates() = runBlocking {
        val unknown = book.copy(chapters = book.chapters.map { it.copy(characterCount = -1) })
        fill(session(unknown), unknown)
        assertTrue(session(unknown).complete)
        assertEquals(text.length, session(unknown).lengths[4])
        val mismatch = book.copy(chapters = book.chapters.map { it.copy(characterCount = text.length + 1) })
        fill(session(mismatch), mismatch)
        assertTrue(session(mismatch).pages.isEmpty())
    }

    @Test fun unavailableOrMissingSourceIdentityFallsBackWithoutPersistingIndex() = runBlocking {
        val untrusted = session(identity = { null })
        fill(untrusted)
        assertTrue(untrusted.pages.isEmpty())
        assertTrue(files().isEmpty())
        File(book.chapters[0].textPath!!).delete()
        fill(session())
        assertTrue(files().isEmpty())
    }

    @Test fun missingImagesAndFontsStillRestoreAndTheirAppearanceInvalidates() = runBlocking {
        val image = File(root, "missing-image.png")
        val font = File(root, "missing-font.ttf")
        val target = book.copy(chapters = book.chapters.map { it.copy(inlineImages = listOf(InlineImage(4, image.path))) })
        val style = settings.copy(fontPath = font.path)
        val picturePages = listOf(ReaderTextPage(0, 4), ReaderTextPage(4, 4, 0), ReaderTextPage(4, 10))
        val index = session(target, style)
        target.chapters.indices.forEach { index.record(it, picturePages, text, cacheKey = "a".repeat(64)) }
        assertTrue(session(target, style).complete)
        image.writeText("image")
        assertTrue(session(target, style).pages.isEmpty())
        image.delete()
        assertTrue(session(target, style).complete)
        font.writeText("font")
        assertTrue(session(target, style).pages.isEmpty())
    }

    @Test fun existingImagesAndFontsStillRequireReliableChangeTokens() = runBlocking {
        val image = File(root, "image.png").apply { writeText("image") }
        val font = File(root, "font.ttf").apply { writeText("font") }
        val target = book.copy(chapters = book.chapters.map { it.copy(inlineImages = listOf(InlineImage(4, image.path))) })
        val style = settings.copy(fontPath = font.path)
        val picturePages = listOf(ReaderTextPage(0, 4), ReaderTextPage(4, 4, 0), ReaderTextPage(4, 10))
        listOf(image, font).forEach { untrusted ->
            val index = session(target, style, identity = { if (it == untrusted) null else trusted(it) })
            target.chapters.indices.forEach { index.record(it, picturePages, text, cacheKey = "a".repeat(64)) }
            assertTrue(index.pages.isEmpty())
            assertTrue(files().isEmpty())
        }
    }

    @Test fun missingRequiredTextOrLazySourceCannotBeTreatedAsAnOptionalMissingAsset() = runBlocking {
        val missing = File(book.chapters.last().textPath!!).apply { delete() }
        val target = book.copy(chapters = book.chapters.mapIndexed { index, chapter ->
            if (index == 0) chapter.copy(inlineImages = listOf(InlineImage(4, missing.path))) else chapter
        })
        fill(session(target), target)
        assertTrue(files().isEmpty())
        val lazy = book.copy(chapters = book.chapters.dropLast(1).map {
            it.copy(lazySourcePath = File(root, "missing.source.json").path)
        })
        fill(session(lazy), lazy)
        assertTrue(files().isEmpty())
    }

    @Test @Config(sdk = [26]) fun android26DoesNotTrustSecondResolutionStat() {
        assertNull(readerFileChangeToken(File(book.chapters[0].textPath!!)))
    }

    @Test fun retentionAndRemovalIncludePartialIndexesAndPreserveOtherBooks() = runBlocking {
        repeat(5) { size ->
            session(style = settings.copy(fontSize = 18f + size)).record(0, pages, text, cacheKey = "a".repeat(64))
            files().forEach { it.setLastModified(1000L + size * 1000L) }
        }
        assertEquals(3, files().size)
        val other = book.copy(id = "another-book")
        fill(session(other), other)
        removeReaderPageCaches(context, book.id)
        assertEquals(1, files().size)
        assertTrue(session(other).complete)
        assertTrue(session().pages.isEmpty())
    }
}
