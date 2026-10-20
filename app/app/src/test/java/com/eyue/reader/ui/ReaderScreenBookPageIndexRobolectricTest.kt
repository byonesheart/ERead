package com.eyue.reader.ui

import android.os.Looper
import android.system.StructStat
import android.system.StructTimespec
import android.util.AtomicFile
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.eyue.reader.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.RealObject
import org.robolectric.annotation.Resetter
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowLinux
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Stable host-file metadata only; real ctime revision/invalidation is tested at the index boundary. */
@Implements(className = "libcore.io.Linux", isInAndroidSdk = false, minSdk = 26)
class ReaderScreenIndexLinuxShadow : ShadowLinux() {
    @Implementation override fun stat(path: String): StructStat {
        stats.getOrPut(File(path).absolutePath) { AtomicInteger() }.incrementAndGet()
        val original = super.stat(path)
        val modified = File(path).lastModified()
        val stamp = StructTimespec(maxOf(1L, modified / 1000), (modified % 1000) * 1_000_000)
        return StructStat(original.st_dev, (path.hashCode().toLong() and 0x7fffffff) + 1, original.st_mode,
            original.st_nlink, original.st_uid, original.st_gid, original.st_rdev, original.st_size,
            stamp, stamp, stamp, original.st_blksize, original.st_blocks)
    }
    companion object {
        private val stats = ConcurrentHashMap<String, AtomicInteger>()
        fun count(path: String) = stats[File(path).absolutePath]?.get() ?: 0
        @JvmStatic @Resetter fun reset() { stats.clear() }
    }
}

/** Observe the existing lazy preparation read boundary without replacing its actual disk IO. */
@Implements(AtomicFile::class)
class ReaderScreenPreparedReadShadow {
    @RealObject private lateinit var original: AtomicFile
    @Implementation fun openRead(): FileInputStream {
        val file = original.baseFile
        if (file.name.endsWith(".prepared.json")) reads.getOrPut(file.absolutePath) { AtomicInteger() }.incrementAndGet()
        gate.get()?.takeIf { file.parentFile?.name == "reader-chapter-page-cache" && file.name.startsWith(it.bookPrefix + "_") }
            ?.let { pending ->
                if (pending.claimed.compareAndSet(false, true)) {
                    check(Looper.myLooper() != Looper.getMainLooper())
                    pending.entered.countDown()
                    check(pending.released.await(5, TimeUnit.SECONDS)) { "The chapter cache read gate was not released" }
                }
            }
        return Shadow.directlyOn(original, AtomicFile::class.java, "openRead")
    }
    class ReadGate(val bookPrefix: String) {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val claimed = AtomicBoolean()
        fun release() = released.countDown()
    }
    companion object {
        private val reads = ConcurrentHashMap<String, AtomicInteger>()
        private val gate = AtomicReference<ReadGate?>()
        fun count(chapter: Chapter) = reads["${chapter.lazySourcePath}.prepared.json"]?.get() ?: 0
        fun pauseNextChapterRead(bookPrefix: String) = ReadGate(bookPrefix).also { gate.set(it) }
        @JvmStatic @Resetter fun reset() { gate.getAndSet(null)?.release(); reads.clear() }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi",
    shadows = [ReaderScreenIndexLinuxShadow::class, ReaderScreenPreparedReadShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderScreenBookPageIndexRobolectricTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: File
    private lateinit var currentBook: MutableState<Book>
    private var mounted by mutableStateOf(true)
    private var session by mutableIntStateOf(0)
    private var readerHeight by mutableStateOf<Dp?>(null)
    private var tabletDualPage = false
    private var settings by mutableStateOf(ReaderSettings(readingMode = ReadingMode.PAGED, showTime = false, showBattery = false))
    private val ids = mutableListOf<String>()
    private val position = AtomicReference<Pair<Int, Int>>()
    private val readingProgress = AtomicReference<Float>()
    private val readerView = AtomicReference<android.view.View>()
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun setUp() {
        root = File(context.cacheDir, "screen-page-index-${UUID.randomUUID()}").apply { mkdirs() }
        ReaderScreenPreparedReadShadow.reset()
        ReaderScreenIndexLinuxShadow.reset()
    }
    @After fun tearDown() {
        if (::currentBook.isInitialized) { compose.runOnIdle { mounted = false }; compose.waitForIdle() }
        runBlocking { ids.forEach { removeReaderPageCaches(context, it) } }
        check(root.deleteRecursively())
    }

    @Test fun coldAndWarmModesPrepareOnlyTheCurrentChapterAndItsImmediateNeighbor() {
        val book = lazyBook()
        show(book)
        awaitComplete(book)
        await { ReaderScreenPreparedReadShadow.count(book.chapters[1]) > 0 }
        val expectedPages = pageLabel()
        assertTrue("The footer must report only this chapter", footer().contains("本章"))
        assertEquals(0, ReaderScreenPreparedReadShadow.count(book.chapters.last()))
        assertTrue("Opening a book must not build its whole-book page index", indexFiles(book).isEmpty())
        val chapterFiles = chapterCacheFiles(book)
        assertTrue("Only the current chapter and nearby chapters may be paginated", chapterFiles.size in 1..3)
        val snapshots = chapterFiles.associate { it.name to it.readBytes() }

        listOf(ReadingMode.PAGED, ReadingMode.VERTICAL_PAGED, ReadingMode.SCROLL).forEach { mode ->
            compose.runOnIdle { mounted = false }
            compose.waitForIdle()
            ReaderScreenPreparedReadShadow.reset()
            position.set(null)
            compose.runOnIdle { settings = settings.copy(readingMode = mode); session++; mounted = true }
            awaitComplete(book)
            await { ReaderScreenPreparedReadShadow.count(book.chapters[1]) > 0 }
            compose.waitForIdle()
            assertEquals(expectedPages, pageLabel())
            if (mode == ReadingMode.SCROLL) {
                val text = File(requireNotNull(book.chapters.first().textPath)).readText()
                val paragraphStart = text.lastIndexOf('\n', book.characterOffset - 1) + 1
                val paragraphEnd = text.indexOf('\n', book.characterOffset).takeIf { it >= 0 } ?: text.length
                val actual = requireNotNull(position.get())
                assertEquals(0, actual.first)
                assertTrue("Scroll may align to a line within the saved paragraph", actual.second in paragraphStart..paragraphEnd)
                assertTrue("The saved source position must remain visible", visibleTextContains(
                    text.substring(book.characterOffset, minOf(book.characterOffset + 40, paragraphEnd))))
            } else assertEquals(0 to book.characterOffset, position.get())
            assertEquals("Warm opening must not prepare an unrelated chapter", 0, ReaderScreenPreparedReadShadow.count(book.chapters.last()))
            snapshots.forEach { (name, bytes) -> assertArrayEquals(bytes, chapterFiles.first { it.name == name }.readBytes()) }
            assertTrue(indexFiles(book).isEmpty())
            val distant = book.chapters.last()
            val distantText = requireNotNull(distant.textPath)
            val distantManifest = requireNotNull(distant.lazySourcePath)
            val distantStats = ReaderScreenIndexLinuxShadow.count(distantText) + ReaderScreenIndexLinuxShadow.count(distantManifest)
            repeat(2) {
                val previous = position.get()
                when (mode) {
                    ReadingMode.SCROLL -> compose.onNodeWithTag("reader_scroll").performTouchInput {
                        swipeUp(startY = height * .7f, endY = height * .45f, durationMillis = 900)
                    }
                    ReadingMode.PAGED -> compose.onNodeWithTag("reader_body").performTouchInput { swipeLeft() }
                    ReadingMode.VERTICAL_PAGED -> compose.onNodeWithTag("reader_body").performTouchInput { swipeUp() }
                }
                await { ready() && position.get() != previous && !footer().contains("统计") }
                assertEquals("Nearby navigation must remain in the current chapter", 0, requireNotNull(position.get()).first)
                assertEquals(expectedPages.substringAfter('/').trim(), pageLabel().substringAfter('/').trim())
            }
            assertEquals("Turning pages must not scan metadata for the entire book again", distantStats,
                ReaderScreenIndexLinuxShadow.count(distantText) + ReaderScreenIndexLinuxShadow.count(distantManifest))
            assertEquals("Turning nearby pages must not prepare the distant chapter", 0, ReaderScreenPreparedReadShadow.count(distant))
            assertTrue("No whole-book pagination may be scheduled after navigation", indexFiles(book).isEmpty())
            assertTrue(chapterCacheFiles(book).size in 1..3)
        }
    }

    @Test fun firstPagedPreparationKeepsTheOldScrollPageInteractiveAndUsesItsLatestPosition() {
        settings = settings.copy(readingMode = ReadingMode.SCROLL)
        val texts = (0 until 3).map { chapter -> (0 until 40).joinToString("\n") { paragraph ->
            val marker = "GATE${chapter}_P${paragraph.toString().padStart(3, '0')}"
            "$marker quiet harbor. $marker boats beside the bridge. $marker old stone steps. $marker evening light."
        } }
        val chapters = texts.mapIndexed { index, text ->
            Chapter(title = "第${index + 1}章", textPath = File(root, "gate-$index.txt").apply { writeText(text) }.absolutePath,
                characterCount = text.length)
        }
        val book = Book(title = "首次模式准备", format = BookFormat.EPUB, mode = LibraryMode.NOVEL,
            chapters = chapters, chapterIndex = 1)
        show(book)
        awaitComplete(book)
        runBlocking { removeReaderPageCaches(context, book.id) }
        val gate = ReaderScreenPreparedReadShadow.pauseNextChapterRead(prefix(book))
        try {
            val before = requireNotNull(position.get())
            compose.runOnIdle { settings = settings.copy(readingMode = ReadingMode.PAGED) }
            await { gate.entered.count == 0L }
            compose.onNodeWithTag("reader_loading").assertDoesNotExist()
            compose.onNodeWithTag("reader_scroll").assertIsDisplayed().performTouchInput {
                swipeUp(startY = height * .75f, endY = height * .4f, durationMillis = 1000)
            }
            await { ready() && position.get() != before }
            val moved = requireNotNull(position.get())
            assertEquals(1, moved.first)
            assertTrue("The previous mode must still respond to scrolling while pagination waits", moved.second > before.second)
            assertEquals("The IO gate must still be holding the requested mode", 1L, gate.released.count)
            compose.onNodeWithTag("reader_loading").assertDoesNotExist()
            compose.onNodeWithTag("reader_scroll").assertIsDisplayed()
            val text = texts[moved.first]
            val paragraphEnd = text.indexOf('\n', moved.second).takeIf { it >= 0 } ?: text.length
            val fragment = text.substring(moved.second, minOf(moved.second + 40, paragraphEnd)).trim()
            assertTrue(visibleTextContains(fragment))
            gate.release()
            await { ready() && chapterPageNumbers() != null && position.get() == moved &&
                compose.onAllNodesWithTag("reader_scroll").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("reader_scroll").assertDoesNotExist()
            assertEquals("The new mode must use the scroll position reached while it was preparing", moved, position.get())
            assertTrue("The new paged viewport must contain that same source position", visibleTextContains(fragment))
            assertTrue("Changing mode must not build a whole-book page index", indexFiles(book).isEmpty())
            assertTrue(chapterCacheFiles(book).size in 1..3)
        } finally { gate.release() }
    }

    @Test fun directoryJumpUsesDestinationChapterPagesInPagedAndScrollModes() {
        val book = lazyBook()
        show(book)
        listOf(ReadingMode.PAGED, ReadingMode.SCROLL).forEach { mode ->
            if (mode != ReadingMode.PAGED) {
                compose.runOnIdle { mounted = false }
                compose.waitForIdle()
                position.set(null)
                compose.runOnIdle { settings = settings.copy(readingMode = mode); session++; mounted = true }
            }
            awaitComplete(book)
            val firstChapterTotal = pageLabel().substringAfter('/').trim()
            compose.onNodeWithTag("reader_body").performTouchInput { click(Offset(width / 2f, height * .1f)) }
            compose.onNodeWithContentDescription("目录").performClick()
            compose.onNodeWithTag("reader_contents_entry_${book.chapters[3].id}").performScrollTo().performClick()
            await { ready() && position.get()?.first == 3 && chapterPageNumbers() != null }
            assertTrue("The first page reached through the contents is chapter page one", pageLabel().startsWith("本章 1 /"))
            assertEquals("Earlier chapter pages must not be added to this chapter's total", firstChapterTotal,
                pageLabel().substringAfter('/').trim())
            assertEquals("The unvisited last chapter must stay on disk", 0, ReaderScreenPreparedReadShadow.count(book.chapters.last()))
            assertTrue(indexFiles(book).isEmpty())
        }
    }

    @Test fun pagedAndVerticalModesMarkTheLastPageReadWithoutChangingItsSourceOffset() {
        verifyLastChapterCompletion()
    }

    @Test @Config(qualifiers = "w960dp-h640dp-land-mdpi")
    fun pagedAndVerticalTabletModesMarkTheLastSpreadReadWithoutChangingItsSourceOffset() {
        tabletDualPage = true
        verifyLastChapterCompletion()
    }

    private fun verifyLastChapterCompletion() {
        val texts = listOf("The opening chapter.", (0 until 30).joinToString("\n") { paragraph ->
            "END_$paragraph quiet harbor and boats beside the bridge. END_$paragraph old stone steps and evening light."
        })
        val chapters = texts.mapIndexed { index, text ->
            Chapter(title = "第${index + 1}章", textPath = File(root, "end-$index.txt").apply { writeText(text) }.absolutePath,
                characterCount = text.length)
        }
        val book = Book(title = "末章读完", format = BookFormat.MOBI, mode = LibraryMode.NOVEL,
            chapters = chapters, chapterIndex = chapters.lastIndex)
        val canonicalProgress = ReaderScrollIndex(texts, chapters.map { emptyList() })
        show(book)
        listOf(ReadingMode.PAGED, ReadingMode.VERTICAL_PAGED).forEach { mode ->
            if (mode != ReadingMode.PAGED) {
                compose.runOnIdle { mounted = false }
                compose.waitForIdle()
                position.set(null); readingProgress.set(null)
                compose.runOnIdle { settings = settings.copy(readingMode = mode); session++; mounted = true }
            }
            awaitComplete(book)
            var numbers = requireNotNull(chapterPageNumbers())
            assertTrue("The fixture must contain several pages", numbers.second > if (tabletDualPage) 2 else 1)
            if (tabletDualPage) assertEquals("The tablet must show a real two-page spread", 2,
                compose.onAllNodesWithContentDescription("阅读正文").fetchSemanticsNodes().size)
            while (numbers.first < numbers.second) {
                val source = requireNotNull(position.get())
                assertEquals(chapters.lastIndex, source.first)
                assertEquals("Other pages must retain canonical text progress",
                    canonicalProgress.progress(source.first, source.second, null), requireNotNull(readingProgress.get()), .00001f)
                val before = numbers.first
                compose.onNodeWithTag("reader_body").performTouchInput {
                    if (mode == ReadingMode.PAGED) swipeLeft() else swipeUp()
                }
                await { ready() && (chapterPageNumbers()?.first ?: 0) > before }
                numbers = requireNotNull(chapterPageNumbers())
            }
            await { readingProgress.get() == 1f }
            assertEquals(1f, requireNotNull(readingProgress.get()), 0f)
            val finalSource = requireNotNull(position.get())
            assertEquals(chapters.lastIndex, finalSource.first)
            assertTrue("Completion must preserve the last page's canonical start rather than forcing EOF",
                finalSource.second in 1 until texts.last().length)
        }
    }

    @Test fun scrollSourceCssSwitchesAndViewportRecompositionKeepTheLazyDocumentAndReadingPosition() {
        settings = settings.copy(readingMode = ReadingMode.SCROLL)
        readerHeight = 680.dp
        val book = lazyBook("p { line-height:1.1; }")
        show(book)
        awaitComplete(book)
        val original = position.get()
        assertEquals(settings.fontSize, fontSizeAt("BODY_0"), .001f)
        val softwareLineHeight = settings.fontSize * settings.lineHeight
        assertEquals(softwareLineHeight, lineHeightAt("BODY_0"), .001f)
        openPreferences()
        compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("reader_use_source_css"))).assertIsOff().performClick()
        closePreferences()
        repeat(8) { step ->
            compose.runOnIdle {
                settings = settings.copy(useSourceCss = step % 2 == 0)
                readerHeight = if (step % 2 == 0) 720.dp else 680.dp
            }
            compose.mainClock.advanceTimeByFrame()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
        compose.runOnIdle { settings = settings.copy(useSourceCss = true); readerHeight = 680.dp }
        await { ready() && kotlin.math.abs(lineHeightAt("BODY_0") - settings.fontSize * 1.1f) < .001f }
        assertEquals(settings.fontSize * 1.1f, lineHeightAt("BODY_0"), .001f)
        assertEquals(settings.fontSize, fontSizeAt("BODY_0"), .001f)
        assertEquals(original, position.get())
        compose.runOnIdle { settings = settings.copy(useSourceCss = false) }
        await { ready() && kotlin.math.abs(lineHeightAt("BODY_0") - softwareLineHeight) < .001f }
        assertEquals(original, position.get())
        compose.onNodeWithText("正文排版失败", substring = true).assertDoesNotExist()
    }

    @Test fun switchingModesAndViewportSizesKeepsTheMiddleChapterParagraphVisible() {
        val texts = (0 until 3).map { chapter ->
            (0 until 40).joinToString("\n") { paragraph ->
                val marker = "CH${chapter}_P${paragraph.toString().padStart(3, '0')}"
                "$marker quiet harbor. $marker boats beside the bridge. $marker old stone steps. $marker evening light."
            }
        }
        val chapters = texts.mapIndexed { index, text ->
            Chapter(title = "第${index + 1}章", textPath = File(root, "mode-$index.txt").apply { writeText(text) }.absolutePath,
                characterCount = text.length)
        }
        val book = Book(title = "模式切换位置", format = BookFormat.MOBI, mode = LibraryMode.NOVEL,
            chapters = chapters, chapterIndex = 1)
        readerHeight = 720.dp
        show(book)
        awaitComplete(book)
        repeat(2) {
            val previous = position.get()
            compose.onNodeWithTag("reader_body").performTouchInput { swipeLeft() }
            await { ready() && position.get() != previous && position.get()?.first == 1 }
        }
        val original = requireNotNull(position.get())
        assertTrue("The starting position must be beyond the first page", original.second > 0)
        val text = texts[1]
        val paragraphStart = text.lastIndexOf('\n', original.second - 1) + 1
        val paragraphEnd = text.indexOf('\n', original.second).takeIf { it >= 0 } ?: text.length
        val visibleFragment = text.substring(original.second, minOf(original.second + 40, paragraphEnd)).trim()
        assertTrue("The starting page must expose its saved source position", visibleTextContains(visibleFragment))

        listOf(ReadingMode.SCROLL to 680.dp, ReadingMode.PAGED to 720.dp,
            ReadingMode.VERTICAL_PAGED to 660.dp, ReadingMode.SCROLL to 700.dp).forEach { (mode, height) ->
            compose.runOnIdle { settings = settings.copy(readingMode = mode); readerHeight = height }
            await {
                val current = position.get()
                ready() && current?.first == 1 && current.second in paragraphStart..paragraphEnd &&
                    visibleTextContains(visibleFragment)
            }
            val current = requireNotNull(position.get())
            assertEquals("$mode must keep the original chapter", 1, current.first)
            assertTrue("$mode moved from ${original.second} to ${current.second}, outside its source paragraph",
                current.second in paragraphStart..paragraphEnd)
            assertTrue("$mode must show the original source paragraph rather than the final page", visibleTextContains(visibleFragment))
        }
    }

    @Test fun sourceCssSwitchReflowsBodyButKeepsLegacySpecialFormattingAndRestoresItsChapterCache() {
        val text = "HEADING\nBODY_MARKER " + "A paragraph that wraps across real screen pages. ".repeat(30)
        val heading = SourceTextStyle(0, 7, fontScale = 1.4f, bold = true)
        val body = SourceTextStyle(8, text.length, fontScale = 1.8f, paragraph = true)
        val chapter = Chapter(title = "正文", textPath = File(root, "css.txt").apply { writeText(text) }.absolutePath,
            characterCount = text.length, sourceStyles = listOf(heading), sourceCssStyles = listOf(heading, body))
        val book = Book(title = "正文 CSS", format = BookFormat.EPUB, mode = LibraryMode.NOVEL, chapters = listOf(chapter))
        show(book)
        awaitComplete(book)
        val originalFooter = footer()
        val originalSnapshot = chapterCacheFiles(book).single().readBytes()
        assertEquals(settings.fontSize, fontSizeAt("BODY_MARKER"), .001f)
        assertEquals(settings.fontSize * 1.4f, fontSizeAt("HEADING"), .001f)
        openPreferences()
        System.getenv("EREAD_TEST_ARTIFACTS")?.let { directory ->
            val bitmap = compose.runOnIdle { readerView.get().let { view ->
                android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888).also {
                    view.draw(android.graphics.Canvas(it))
                }
            } }
            try { File(directory, "reader-source-css.png").apply { parentFile!!.mkdirs() }.outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            } } finally { bitmap.recycle() }
        }
        val toggle = compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("reader_use_source_css")))
        toggle.assertIsOff().performClick()
        closePreferences()
        await { ready() && fontSizeAt("BODY_MARKER") > settings.fontSize * 1.5f && chapterCacheFiles(book).size == 2 }
        assertEquals(settings.fontSize * 1.8f, fontSizeAt("BODY_MARKER"), .001f)
        assertEquals(settings.fontSize * 1.4f, fontSizeAt("HEADING"), .001f)
        assertNotEquals(originalFooter, footer())
        openPreferences()
        compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("reader_use_source_css"))).assertIsOn().performClick()
        closePreferences()
        await { ready() && fontSizeAt("BODY_MARKER") == settings.fontSize && footer() == originalFooter }
        assertTrue(chapterCacheFiles(book).any { it.readBytes().contentEquals(originalSnapshot) })
        assertTrue(indexFiles(book).isEmpty())
    }

    @Test fun originalCssOptionIsShownForEpubAndMobiButNotTxt() {
        val text = "FORMAT_VISIBILITY 正文"
        val chapter = Chapter(title = "第一章", textPath = File(root, "formats.txt").apply { writeText(text) }.absolutePath, characterCount = text.length)
        val first = Book(title = "格式设置", format = BookFormat.EPUB, mode = LibraryMode.NOVEL, chapters = listOf(chapter))
        show(first)
        listOf(BookFormat.EPUB, BookFormat.MOBI, BookFormat.TXT).forEach { format ->
            if (format != BookFormat.EPUB) {
                compose.runOnIdle { mounted = false }
                compose.waitForIdle()
                val next = first.copy(id = UUID.randomUUID().toString(), format = format)
                ids += next.id
                compose.runOnIdle { currentBook.value = next; session++; mounted = true }
            }
            await { ready() }
            openPreferences()
            if (format == BookFormat.TXT) compose.onNodeWithTag("reader_use_source_css").assertDoesNotExist()
            else compose.onNodeWithTag("reader_use_source_css").assertIsDisplayed()
            closePreferences()
        }
    }

    private fun lazyBook(css: String = ""): Book {
        val directory = File(root, "lazy").apply { mkdirs() }
        val chapters = (0 until 6).map { index ->
            val html = "<body><h2>CHAPTER_$index</h2>" + (0 until 20).joinToString("") {
                "<p>BODY_$index A quiet harbor and the boats beside the old stone bridge.</p>"
            } + "</body>"
            val parsed = HtmlChapterParser.readPlain("第${index + 1}章", html) { null }
            saveLazyChapter(directory, index, parsed, html, css, emptyMap())
        }
        return Book(title = "按章分页缓存", format = BookFormat.EPUB, mode = LibraryMode.NOVEL, chapters = chapters, characterOffset = 25)
    }
    private fun show(book: Book) {
        currentBook = mutableStateOf(book); ids += book.id
        val speech = SilentSpeech()
        compose.setContent { readerView.set(LocalView.current); if (mounted) key(session) {
            CompositionLocalProvider(LocalReaderSpeechFactory provides { speech }) { MaterialTheme {
                Box(Modifier.fillMaxWidth().then(readerHeight?.let { Modifier.height(it) } ?: Modifier.fillMaxHeight())) {
                ReaderScreen(currentBook.value, settings, emptyList(), {},
                    { chapter, _, offset, progress, _ -> position.set(chapter to offset); readingProgress.set(progress) },
                    { settings = it }, {}, {}, {}, emptyFlow(), tabletDualPage = tabletDualPage)
                }
            } }
        } }
    }
    private fun ready() = compose.onAllNodesWithTag("reader_loading").fetchSemanticsNodes().isEmpty() &&
        compose.onAllNodesWithContentDescription("阅读正文").fetchSemanticsNodes().isNotEmpty()
    private fun footer() = compose.onNodeWithTag("reader_page_progress").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
    private fun pageLabel() = Regex("本章\\s*\\d+\\s*/\\s*\\d+\\s*页").find(footer())?.value ?: error("Chapter page numbers are unavailable: ${footer()}")
    private fun chapterPageNumbers() = Regex("本章\\s*(\\d+)\\s*/\\s*(\\d+)\\s*页").find(footer())
        ?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
    private fun awaitComplete(book: Book) = await {
        ready() && chapterCacheFiles(book).isNotEmpty() && chapterPageNumbers() != null && position.get() != null
    }
    private fun prefix(book: Book) = MessageDigest.getInstance("SHA-256").digest(book.id.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun indexFiles(book: Book) = File(context.cacheDir, "reader-book-page-index").listFiles().orEmpty()
        .filter { it.name.startsWith(prefix(book) + "_") && it.extension == "bin" }
    private fun chapterCacheFiles(book: Book) = File(context.cacheDir, "reader-chapter-page-cache").listFiles().orEmpty()
        .filter { it.name.startsWith(prefix(book) + "_") && it.extension == "bin" }
    private fun openPreferences() {
        compose.onNodeWithTag("reader_body").performTouchInput { click(Offset(width / 2f, height * .1f)) }
        compose.onNodeWithContentDescription("阅读设置").performClick()
    }
    private fun closePreferences() = compose.onNodeWithTag("reader_body").performTouchInput { click(Offset(width / 2f, height * .1f)) }
    private fun visibleTextContains(fragment: String): Boolean {
        val body = compose.onNodeWithTag("reader_body").fetchSemanticsNode().boundsInRoot
        val nodes = compose.onAllNodesWithContentDescription("阅读正文")
        return nodes.fetchSemanticsNodes().mapIndexedNotNull { index, node ->
            val bounds = node.boundsInRoot
            if (bounds.width <= 0f || bounds.height <= 0f || bounds.right <= body.left || bounds.left >= body.right ||
                bounds.bottom <= body.top || bounds.top >= body.bottom) return@mapIndexedNotNull null
            val layouts = mutableListOf<TextLayoutResult>()
            nodes[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            layouts
        }.flatten().any { it.layoutInput.text.text.contains(fragment) }
    }
    private fun fontSizeAt(word: String): Float {
        val results = mutableListOf<TextLayoutResult>()
        val nodes = compose.onAllNodesWithContentDescription("阅读正文")
        nodes.fetchSemanticsNodes().indices.forEach { index ->
            nodes[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        }
        val layout = results.firstOrNull { it.layoutInput.text.text.contains(word) } ?: return -1f
        val offset = layout.layoutInput.text.text.indexOf(word)
        if (offset < 0) return -1f
        return layout.layoutInput.text.spanStyles.lastOrNull { it.start <= offset && it.end > offset && it.item.fontSize != TextUnit.Unspecified }
            ?.item?.fontSize?.value ?: layout.layoutInput.style.fontSize.value
    }
    private fun lineHeightAt(word: String): Float {
        val results = mutableListOf<TextLayoutResult>()
        val nodes = compose.onAllNodesWithContentDescription("阅读正文")
        nodes.fetchSemanticsNodes().indices.forEach { index ->
            nodes[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        }
        val layout = results.firstOrNull { it.layoutInput.text.text.contains(word) } ?: return -1f
        val offset = layout.layoutInput.text.text.indexOf(word)
        return layout.layoutInput.text.paragraphStyles.lastOrNull {
            it.start <= offset && it.end > offset && it.item.lineHeight != TextUnit.Unspecified
        }?.item?.lineHeight?.value ?: layout.layoutInput.style.lineHeight.value
    }
    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (System.nanoTime() < deadline) {
            compose.mainClock.advanceTimeBy(32)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            compose.waitForIdle()
            if (predicate()) return
            Thread.sleep(10)
        }
        assertTrue("Reader did not restore its expected page index or source style", predicate())
    }
    private class SilentSpeech : ReaderSpeechController {
        override val playing = MutableStateFlow(false)
        override val error = MutableStateFlow<String?>(null)
        override fun start(source: List<ReaderSpeechSegment>, speed: Float, onLocation: (ReaderSpeechSegment, Int) -> Unit) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun clearError() = Unit
        override fun pause() = false
        override fun resume() = false
        override fun stop() = Unit
        override fun release() = Unit
    }
}
