package com.eyue.reader.ui

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eyue.reader.data.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs

/** Exercises the source DOM route inside the existing reader, using Android's actual WebView. */
@RunWith(AndroidJUnit4::class)
class ReaderEbookIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var host: View
    private var settings by mutableStateOf(ReaderSettings(readingMode = ReadingMode.PAGED, showTime = false, showBattery = false))
    private var shown by mutableStateOf(true)
    private data class Position(val chapter: Int, val page: Int, val offset: Int)
    private val position = AtomicReference<Position?>()
    private val positionReports = AtomicInteger()

    @Before fun setup() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        root = File(base.cacheDir, "ebook-integration-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        }
        position.set(null)
        positionReports.set(0)
    }

    @After fun cleanup() {
        compose.runOnIdle { shown = false }
        compose.waitForIdle()
        check(root.canonicalFile.parentFile == ApplicationProvider.getApplicationContext<Context>().cacheDir.canonicalFile)
        root.deleteRecursively()
    }

    @Test fun sourceReaderRestoresTheCanonicalAnchorAcrossModesAndContentsNavigation() {
        val imported = fixture()
        val text = File(requireNotNull(imported.chapters.first().textPath)).readText()
        val anchor = text.indexOf("ANCHOR_0_20").also { assertTrue(it > 0) }
        show(imported.copy(characterOffset = anchor, pageIndex = 20))
        compose.waitUntil(30_000) { position.get()?.let { it.chapter == 0 && it.offset in 1..anchor } == true }
        compose.onNodeWithTag("reader_ebook").assertIsDisplayed()
        val restored = requireNotNull(position.get()).offset
        assertTrue(anchor - restored < text.length / 2)
        val reportsBeforeModeChange = positionReports.get()
        compose.runOnIdle { settings = settings.copy(readingMode = ReadingMode.SCROLL) }
        compose.waitUntil(10_000) { positionReports.get() > reportsBeforeModeChange &&
            position.get()?.let { it.chapter == 0 && abs(it.offset - restored) < 500 } == true }
        for ((conversion, phrase) in listOf(ChineseConversion.TRADITIONAL to "調整閱讀模式", ChineseConversion.ORIGINAL to "调整阅读模式")) {
            val reportsBeforeConversion = positionReports.get()
            compose.runOnIdle { settings = settings.copy(chineseConversion = conversion) }
            waitForSourceText(phrase)
            compose.waitUntil(30_000) { positionReports.get() > reportsBeforeConversion &&
                position.get()?.let { it.chapter == 0 && abs(it.offset - restored) < 500 } == true }
        }
        assertEquals("Display conversion must preserve the canonical source", text,
            File(requireNotNull(imported.chapters.first().textPath)).readText())
        compose.onNodeWithTag("reader_ebook").performTouchInput { click() }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("目录").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("目录").performClick()
        compose.onNodeWithText(imported.chapters[1].title).performClick()
        compose.waitUntil(30_000) { position.get()?.chapter == 1 }
        compose.onNodeWithTag("reader_ebook").assertIsDisplayed()
        compose.onNodeWithTag("reader_retry").assertDoesNotExist()
    }

    @Test fun unreadableOriginalShowsAnExplicitRetryWithoutSavingAnEmptyPosition() {
        val imported = fixture()
        show(imported.copy(sourcePath = File(root, "missing.epub").absolutePath, sourceUri = null,
            sourceLinked = false, characterOffset = 800, pageIndex = 3))
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("reader_retry").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("reader_retry").assertIsDisplayed()
        assertNull(position.get())
    }

    private fun show(book: Book) {
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                val currentView = LocalView.current
                SideEffect { host = currentView }
                MaterialTheme {
                    if (shown) ReaderScreen(book, settings, emptyList(), {},
                        { chapter, page, offset, _, _ -> position.set(Position(chapter, page, offset)); positionReports.incrementAndGet() },
                        { settings = it }, {}, {}, {}, emptyFlow())
                }
            }
        }
    }

    private fun waitForSourceText(phrase: String) {
        val rendered = AtomicReference<String?>()
        fun webView(view: View): WebView? = if (view is WebView) view else (view as? ViewGroup)?.let { group ->
            (0 until group.childCount).firstNotNullOfOrNull { webView(group.getChildAt(it)) }
        }
        compose.waitUntil(30_000) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                webView(host.rootView)?.evaluateJavascript("document.body.innerText") { rendered.set(it) }
            }
            rendered.get()?.contains(phrase) == true
        }
    }

    private fun fixture(): Book {
        val source = File(root, "source.epub")
        ZipOutputStream(source.outputStream()).use { zip ->
            fun entry(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip")
            entry("META-INF/container.xml", """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            entry("OEBPS/book.opf", """<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>原书集成测试</dc:title><dc:identifier id="id">integration</dc:identifier><dc:language>zh</dc:language></metadata><manifest><item id="one" href="one.xhtml" media-type="application/xhtml+xml"/><item id="two" href="two.xhtml" media-type="application/xhtml+xml"/><item id="style" href="style.css" media-type="text/css"/></manifest><spine><itemref idref="one"/><itemref idref="two"/></spine></package>""")
            entry("OEBPS/style.css", "h1{font-size:1.4em} blockquote{font-style:italic; margin-left:2em}")
            listOf("one", "two").forEachIndexed { chapter, name ->
                entry("OEBPS/$name.xhtml", "<html><head><link rel=\"stylesheet\" href=\"style.css\"/></head><body><h1>第${chapter + 1}章</h1>" +
                    (0 until 70).joinToString("") { paragraph -> "<p>ANCHOR_${chapter}_${paragraph} 原书的正文与章节阅读记录应当保持一致，调整阅读模式后也应停留在当前句子附近。</p>" } + "</body></html>")
            }
        }
        return runBlocking { DocumentImporter(context).importFiles(listOf(Uri.fromFile(source)), LibraryMode.NOVEL, DEFAULT_CHAPTER_RULE).single() }
    }
}
