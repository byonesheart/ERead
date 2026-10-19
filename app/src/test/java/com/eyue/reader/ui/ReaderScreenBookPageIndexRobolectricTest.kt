package com.eyue.reader.ui

import android.os.Looper
import android.system.StructStat
import android.system.StructTimespec
import android.util.AtomicFile
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.TextUnit
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Stable host-file metadata only; real ctime revision/invalidation is tested at the index boundary. */
@Implements(className = "libcore.io.Linux", isInAndroidSdk = false, minSdk = 26)
class ReaderScreenIndexLinuxShadow : ShadowLinux() {
    @Implementation override fun stat(path: String): StructStat {
        val original = super.stat(path)
        val modified = File(path).lastModified()
        val stamp = StructTimespec(maxOf(1L, modified / 1000), (modified % 1000) * 1_000_000)
        return StructStat(original.st_dev, (path.hashCode().toLong() and 0x7fffffff) + 1, original.st_mode,
            original.st_nlink, original.st_uid, original.st_gid, original.st_rdev, original.st_size,
            stamp, stamp, stamp, original.st_blksize, original.st_blocks)
    }
}

/** Observe the existing lazy preparation read boundary without replacing its actual disk IO. */
@Implements(AtomicFile::class)
class ReaderScreenPreparedReadShadow {
    @RealObject private lateinit var original: AtomicFile
    @Implementation fun openRead(): FileInputStream {
        val file = original.baseFile
        if (file.name.endsWith(".prepared.json")) reads.getOrPut(file.absolutePath) { AtomicInteger() }.incrementAndGet()
        return Shadow.directlyOn(original, AtomicFile::class.java, "openRead")
    }
    companion object {
        private val reads = ConcurrentHashMap<String, AtomicInteger>()
        fun count(chapter: Chapter) = reads["${chapter.lazySourcePath}.prepared.json"]?.get() ?: 0
        @JvmStatic @Resetter fun reset() { reads.clear() }
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
    private var settings by mutableStateOf(ReaderSettings(readingMode = ReadingMode.PAGED, showTime = false, showBattery = false))
    private val ids = mutableListOf<String>()
    private val position = AtomicReference<Pair<Int, Int>>()
    private val readerView = AtomicReference<android.view.View>()
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun setUp() {
        root = File(context.cacheDir, "screen-page-index-${UUID.randomUUID()}").apply { mkdirs() }
        ReaderScreenPreparedReadShadow.reset()
    }
    @After fun tearDown() {
        if (::currentBook.isInitialized) { compose.runOnIdle { mounted = false }; compose.waitForIdle() }
        runBlocking { ids.forEach { removeReaderPageCaches(context, it) } }
        check(root.deleteRecursively())
    }

    @Test fun coldPagedOpeningPersistsAllBoundariesAndWarmPagedOrVerticalOpenSkipsFarChapterPreparation() {
        val book = lazyBook()
        show(book)
        awaitComplete(book)
        val expectedFooter = footer()
        assertTrue(ReaderScreenPreparedReadShadow.count(book.chapters.last()) > 0)
        val index = indexFiles(book).single()
        val snapshot = index.readBytes()
        val chapterFiles = chapterCacheFiles(book)
        assertEquals(book.chapters.size, chapterFiles.size)
        val fixed = 1_500_000_000_000L
        chapterFiles.forEach { check(it.setLastModified(fixed)) }

        listOf(ReadingMode.PAGED, ReadingMode.VERTICAL_PAGED).forEach { mode ->
            compose.runOnIdle { mounted = false }
            compose.waitForIdle()
            ReaderScreenPreparedReadShadow.reset()
            position.set(null)
            compose.runOnIdle { settings = settings.copy(readingMode = mode); session++; mounted = true }
            awaitComplete(book)
            await { ReaderScreenPreparedReadShadow.count(book.chapters[2]) > 0 }
            compose.waitForIdle()
            assertEquals(expectedFooter, footer())
            assertEquals(0 to book.characterOffset, position.get())
            assertEquals("Warm opening must not prepare an unrelated chapter", 0, ReaderScreenPreparedReadShadow.count(book.chapters.last()))
            assertTrue("Restored boundaries must bypass every per-chapter cache read", chapterFiles.all { it.lastModified() == fixed })
            assertArrayEquals(snapshot, index.readBytes())
        }
    }

    @Test fun sourceCssSwitchReflowsBodyButKeepsLegacySpecialFormattingAndRestoresItsPreviousIndex() {
        val text = "HEADING\nBODY_MARKER " + "A paragraph that wraps across real screen pages. ".repeat(30)
        val heading = SourceTextStyle(0, 7, fontScale = 1.4f, bold = true)
        val body = SourceTextStyle(8, text.length, fontScale = 1.8f, paragraph = true)
        val chapter = Chapter(title = "正文", textPath = File(root, "css.txt").apply { writeText(text) }.absolutePath,
            characterCount = text.length, sourceStyles = listOf(heading), sourceCssStyles = listOf(heading, body))
        val book = Book(title = "正文 CSS", format = BookFormat.EPUB, mode = LibraryMode.NOVEL, chapters = listOf(chapter))
        show(book)
        awaitComplete(book)
        val originalFooter = footer()
        val originalSnapshot = indexFiles(book).single().readBytes()
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
        await { ready() && fontSizeAt("BODY_MARKER") > settings.fontSize * 1.5f && indexFiles(book).size == 2 }
        assertEquals(settings.fontSize * 1.8f, fontSizeAt("BODY_MARKER"), .001f)
        assertEquals(settings.fontSize * 1.4f, fontSizeAt("HEADING"), .001f)
        assertNotEquals(originalFooter, footer())
        openPreferences()
        compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("reader_use_source_css"))).assertIsOn().performClick()
        closePreferences()
        await { ready() && fontSizeAt("BODY_MARKER") == settings.fontSize && footer() == originalFooter }
        assertTrue(indexFiles(book).any { it.readBytes().contentEquals(originalSnapshot) })
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

    private fun lazyBook(): Book {
        val directory = File(root, "lazy").apply { mkdirs() }
        val chapters = (0 until 6).map { index ->
            val html = "<body><h2>CHAPTER_$index</h2>" + (0 until 6).joinToString("") {
                "<p>BODY_$index A quiet harbor and the boats beside the old stone bridge.</p>"
            } + "</body>"
            val parsed = HtmlChapterParser.readPlain("第${index + 1}章", html) { null }
            saveLazyChapter(directory, index, parsed, html, "", emptyMap())
        }
        return Book(title = "持久分页索引", format = BookFormat.EPUB, mode = LibraryMode.NOVEL, chapters = chapters, characterOffset = 25)
    }
    private fun show(book: Book) {
        currentBook = mutableStateOf(book); ids += book.id
        val speech = SilentSpeech()
        compose.setContent { readerView.set(LocalView.current); if (mounted) key(session) {
            CompositionLocalProvider(LocalReaderSpeechFactory provides { speech }) { MaterialTheme {
                ReaderScreen(currentBook.value, settings, emptyList(), {},
                    { chapter, _, offset, _, _ -> position.set(chapter to offset) }, { settings = it }, {}, {}, {}, emptyFlow())
            } }
        } }
    }
    private fun ready() = compose.onAllNodesWithTag("reader_loading").fetchSemanticsNodes().isEmpty() &&
        compose.onAllNodesWithContentDescription("阅读正文").fetchSemanticsNodes().isNotEmpty()
    private fun footer() = compose.onNodeWithTag("reader_page_progress").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
    private fun awaitComplete(book: Book) = await {
        ready() && indexFiles(book).size == 1 && !footer().contains("统计") && position.get() != null
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
    private fun fontSizeAt(word: String): Float {
        val results = mutableListOf<TextLayoutResult>()
        compose.onAllNodesWithContentDescription("阅读正文").onFirst().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single()
        val offset = layout.layoutInput.text.text.indexOf(word)
        if (offset < 0) return -1f
        return layout.layoutInput.text.spanStyles.lastOrNull { it.start <= offset && it.end > offset && it.item.fontSize != TextUnit.Unspecified }
            ?.item?.fontSize?.value ?: layout.layoutInput.style.fontSize.value
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
