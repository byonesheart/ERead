package com.eyue.reader.ui

import android.content.Context
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebResourceRequest
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.eyue.reader.data.EpubFixtures
import com.eyue.reader.data.EbookSourceStore
import com.eyue.reader.data.EpubTestImporter
import com.eyue.reader.data.ReaderSettings
import com.eyue.reader.data.ReadingMode
import com.eyue.reader.data.readChapterText
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Runs the production ReaderScreen gates, rather than just the embedded surface. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReaderLegacyEpubRobolectricTest {
    @get:Rule val compose = createComposeRule()

    @Test fun changingSingleChapterReadingModesKeepsTheLivePagingControllerConnected() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "reader-mode-controller-${UUID.randomUUID()}").apply { mkdirs() }
        val source = EpubFixtures.create(context, root, "mode-controller", paragraphs = 15)
        val book = EpubTestImporter(context).import(source, File(root, "book").apply { mkdirs() }, UUID.randomUUID().toString(), "modes")
        val settings = mutableStateOf(ReaderSettings(readingMode = ReadingMode.PAGED, continuousEbookScroll = false, volumePaging = true))
        val keys = MutableSharedFlow<Int>(extraBufferCapacity = 4)
        val session = EbookReaderSession(context)
        val view = AtomicReference<View>()
        fun browser(): EbookWebView? {
            fun find(node: View?): EbookWebView? = if (node is EbookWebView) node else (node as? ViewGroup)?.let { group ->
                (0 until group.childCount).firstNotNullOfOrNull { find(group.getChildAt(it)) }
            }
            return find(view.get())
        }
        fun send(target: EbookWebView, json: String) = compose.runOnIdle {
            val bridge = shadowOf(target).getJavascriptInterface("__ERead")
            bridge.javaClass.getMethod("event", String::class.java).apply { isAccessible = true }.invoke(bridge, json)
        }
        try {
            compose.setContent { view.set(LocalView.current.rootView)
                MaterialTheme { ReaderScreen(book, settings.value, emptyList(), {}, { _, _, _, _, _ -> },
                    { settings.value = it }, {}, {}, {}, keys, ebookSession = session) }
            }
            compose.waitUntil(15_000) { compose.runOnIdle { browser()?.onBridgeEvent != null } }
            val first = requireNotNull(browser())
            send(first, """{"type":"ready"}""")
            send(first, """{"type":"viewport","offset":120,"endOffset":180,"pages":8}""")
            for (mode in listOf(ReadingMode.PAGED, ReadingMode.SCROLL, ReadingMode.PAGED, ReadingMode.VERTICAL_PAGED)) {
                compose.runOnIdle { settings.value = settings.value.copy(readingMode = mode) }
                compose.waitUntil(5_000) { compose.runOnIdle { first.appliedConfig.orEmpty().contains("\"mode\":\"${mode.name}\"") } }
                send(first, """{"type":"viewport","offset":120,"endOffset":180,"pages":8}""")
                compose.runOnIdle { assertSame(first, browser()); keys.tryEmit(1) }
                compose.waitUntil(5_000) { compose.runOnIdle { shadowOf(first).lastEvaluatedJavascript.contains("\"type\":\"move\"") } }
            }
        } finally { compose.runOnIdle { session.close() }; root.deleteRecursively() }
    }

    @Test fun switchingBetweenPagedAndContinuousScrollKeepsTheLiveControllerConnected() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "reader-mode-controller-${UUID.randomUUID()}").apply { mkdirs() }
        val source = EpubFixtures.create(context, root, "mode-controller", paragraphs = 15)
        val book = EpubTestImporter(context).import(source, File(root, "book").apply { mkdirs() }, UUID.randomUUID().toString(), "modes")
        val settings = mutableStateOf(ReaderSettings(readingMode = ReadingMode.PAGED, continuousEbookScroll = true, volumePaging = true))
        val keys = MutableSharedFlow<Int>(extraBufferCapacity = 4)
        val session = EbookReaderSession(context)
        val view = AtomicReference<View>()
        fun browser(): EbookWebView? {
            fun find(node: View?): EbookWebView? = if (node is EbookWebView) node else (node as? ViewGroup)?.let { group ->
                (0 until group.childCount).firstNotNullOfOrNull { find(group.getChildAt(it)) }
            }
            return find(view.get())
        }
        fun send(target: EbookWebView, json: String) = compose.runOnIdle {
            val bridge = shadowOf(target).getJavascriptInterface("__ERead")
            bridge.javaClass.getMethod("event", String::class.java).apply { isAccessible = true }.invoke(bridge, json)
        }
        try {
            compose.setContent { view.set(LocalView.current.rootView)
                MaterialTheme { ReaderScreen(book, settings.value, emptyList(), {}, { _, _, _, _, _ -> },
                    { settings.value = it }, {}, {}, {}, keys, ebookSession = session) }
            }
            compose.waitUntil(15_000) { compose.runOnIdle { browser()?.onBridgeEvent != null } }
            val first = requireNotNull(browser())
            send(first, """{"type":"ready"}""")
            send(first, """{"type":"viewport","offset":120,"endOffset":180,"pages":8}""")
            var current = first
            for (mode in listOf(ReadingMode.SCROLL, ReadingMode.PAGED, ReadingMode.SCROLL, ReadingMode.PAGED)) {
                val previous = current
                compose.runOnIdle { settings.value = settings.value.copy(readingMode = mode) }
                compose.waitUntil(15000) { compose.runOnIdle { browser()?.let {
                    it !== previous && it.onBridgeEvent != null && it.appliedConfig.orEmpty().contains("\"mode\":\"${mode.name}\"")
                } == true } }
                current = requireNotNull(browser())
                send(current, """{"type":"ready","chapter":0}""")
                send(current, """{"type":"viewport","chapter":0,"offset":120,"endOffset":180,"pages":8}""")
                compose.runOnIdle { keys.tryEmit(1) }
                compose.waitUntil(5000) { compose.runOnIdle { shadowOf(current).lastEvaluatedJavascript.contains("\"type\":\"move\"") } }
            }
        } finally { compose.runOnIdle { session.close() }; root.deleteRecursively() }
    }

    @Test fun pagedReaderPreparesANeighborsDomBeforeItIsFirstOpened() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "reader-preload-${UUID.randomUUID()}").apply { mkdirs() }
        val source = EpubFixtures.create(context, root, "preload", paragraphs = 15)
        val book = EpubTestImporter(context).import(source, File(root, "book").apply { mkdirs() }, UUID.randomUUID().toString(), "preload")
        val session = EbookReaderSession(context)
        val view = AtomicReference<View>()
        val progress = mutableListOf<Int>()
        fun browsers(): List<EbookWebView> {
            fun find(node: View?): List<EbookWebView> = if (node is EbookWebView) listOf(node) else (node as? ViewGroup)?.let { group ->
                (0 until group.childCount).flatMap { find(group.getChildAt(it)) }
            }.orEmpty()
            return find(view.get())
        }
        fun send(browser: EbookWebView, event: String) = compose.runOnIdle {
            val bridge = shadowOf(browser).getJavascriptInterface("__ERead")
            bridge.javaClass.getMethod("event", String::class.java).apply { isAccessible = true }.invoke(bridge, event)
        }
        try {
            compose.setContent {
                view.set(LocalView.current.rootView)
                MaterialTheme { ReaderScreen(book, ReaderSettings(readingMode = ReadingMode.PAGED), emptyList(), {},
                    { chapter, _, _, _, _ -> progress += chapter }, {}, {}, {}, {}, emptyFlow(), ebookSession = session) }
            }
            compose.waitUntil(15_000) { compose.runOnIdle { browsers().firstOrNull()?.onBridgeEvent != null } }
            val first = browsers().first()
            send(first, """{"type":"ready"}""")
            send(first, """{"type":"viewport","offset":0,"endOffset":120,"pages":8}""")
            compose.waitUntil(15_000) { compose.runOnIdle { browsers().size == 2 && browsers().last().onBridgeEvent != null } }
            val neighbor = browsers().last()
            send(neighbor, """{"type":"ready"}""")
            send(neighbor, """{"type":"viewport","offset":0,"endOffset":4,"pages":1}""")
            compose.runOnIdle { assertTrue(progress.all { it == 0 }); assertTrue(neighbor.pageReady) }
            send(first, """{"type":"tap"}""")
            compose.onNodeWithContentDescription("下一章").performClick()
            compose.waitUntil(15_000) { compose.runOnIdle { browsers().firstOrNull() === neighbor && neighbor.onBridgeEvent != null } }
            compose.runOnIdle {
                assertTrue(shadowOf(neighbor).lastEvaluatedJavascript.contains("type:'resume'"))
                assertFalse(neighbor.disposed)
            }
            val before = progress.size
            send(neighbor, """{"type":"viewport","offset":4,"endOffset":4,"pages":1}""")
            compose.runOnIdle { assertEquals("The preloader's queued position must not become the new reader position", before, progress.size) }
            send(neighbor, """{"type":"ready"}""")
            send(neighbor, """{"type":"viewport","offset":0,"endOffset":4,"pages":1}""")
            compose.waitUntil { compose.runOnIdle { 1 in progress } }
        } finally {
            compose.runOnIdle { session.close() }
            root.deleteRecursively()
        }
    }

    @Test fun defaultContinuousReaderServesOwnedChapterFramesAndKeepsOneBrowserAcrossASeam() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "reader-continuous-${UUID.randomUUID()}").apply { mkdirs() }
        val source = EpubFixtures.create(context, root, "continuous", paragraphs = 15)
        val book = EpubTestImporter(context).import(source, File(root, "book").apply { mkdirs() }, UUID.randomUUID().toString(), "continuous")
            .copy(characterOffset = 120)
        val session = EbookReaderSession(context)
        val view = AtomicReference<View>()
        val progress = mutableListOf<Pair<Int, Int>>()
        fun browser(): EbookWebView? {
            fun find(node: View?): EbookWebView? = if (node is EbookWebView) node else (node as? ViewGroup)?.let { group ->
                (0 until group.childCount).firstNotNullOfOrNull { find(group.getChildAt(it)) }
            }
            return find(view.get())
        }
        fun send(first: EbookWebView, json: String) = compose.runOnIdle {
            val bridge = shadowOf(first).getJavascriptInterface("__ERead")
            bridge.javaClass.getMethod("event", String::class.java).apply { isAccessible = true }.invoke(bridge, json)
        }
        try {
            compose.setContent {
                view.set(LocalView.current.rootView)
                MaterialTheme {
                    ReaderScreen(book, ReaderSettings(), emptyList(), {}, { chapter, _, offset, _, _ -> progress += chapter to offset },
                        {}, {}, {}, {}, emptyFlow(), ebookSession = session)
                }
            }
            compose.waitUntil(15_000) { compose.runOnIdle { browser()?.onBridgeEvent != null } }
            val first = requireNotNull(browser())
            compose.runOnIdle {
                assertTrue(first.envelope.contains("EReadContinuous"))
                assertTrue(first.envelope.contains("\"offset\":120"))
            }
            send(first, """{"type":"chapterRequest","chapter":1,"token":2}""")
            val chapter = EbookSourceStore(context, book).use { store -> runBlocking { store.load(1, readChapterText(book, 1)) } }
            val childUrl = chapter.baseUrl + "?eread-window=${first.documentGeneration}&chapter=1&token=2"
            val request = object : WebResourceRequest {
                override fun getUrl() = Uri.parse(childUrl)
                override fun isForMainFrame() = false
                override fun isRedirect() = false
                override fun hasGesture() = false
                override fun getMethod() = "GET"
                override fun getRequestHeaders(): Map<String, String> = emptyMap()
            }
            compose.waitUntil(15_000) { compose.runOnIdle { !first.webViewClient.shouldOverrideUrlLoading(first, request) } }
            val response = requireNotNull(first.webViewClient.shouldInterceptRequest(first, request))
            val html = response.data.bufferedReader().use { it.readText() }
            assertTrue(html.contains("\"continuous\":true"))
            assertTrue(html.contains("继续正文"))
            send(first, """{"type":"ready","chapter":0}""")
            send(first, """{"type":"viewport","chapter":0,"offset":120,"endOffset":250,"pages":8}""")
            compose.waitUntil { compose.runOnIdle { progress.any { it == 0 to 120 } } }
            send(first, """{"type":"viewport","chapter":1,"offset":1,"endOffset":4,"pages":1}""")
            compose.waitUntil(15_000) { compose.runOnIdle { progress.any { it == 1 to 1 } } }
            compose.runOnIdle { assertSame(first, browser()); assertFalse(first.disposed) }
            send(first, """{"type":"chapterDiscard","chapter":1,"token":2}""")
            compose.runOnIdle { assertTrue(first.webViewClient.shouldOverrideUrlLoading(first, request)) }
        } finally {
            compose.runOnIdle { session.close() }
            root.deleteRecursively()
        }
    }

    @Test fun savedAnchorAndChapterDomSurviveTheReaderScreensNavigation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "reader-legacy-${UUID.randomUUID()}").apply { mkdirs() }
        val source = EpubFixtures.create(context, root, "reader", paragraphs = 15)
        val book = EpubTestImporter(context).import(source, File(root, "book").apply { mkdirs() }, UUID.randomUUID().toString(), "reader")
            .copy(characterOffset = 120)
        val session = EbookReaderSession(context)
        val view = AtomicReference<View>()
        val progress = mutableListOf<Pair<Int, Int>>()
        fun browser(): EbookWebView? {
            fun find(node: View?): EbookWebView? = if (node is EbookWebView) node else (node as? ViewGroup)?.let { group ->
                (0 until group.childCount).firstNotNullOfOrNull { find(group.getChildAt(it)) }
            }
            return find(view.get())
        }
        fun awaitBrowser(expected: WebView? = null): EbookWebView {
            compose.waitUntil(15_000) { compose.runOnIdle {
                browser()?.let { it.onBridgeEvent != null && (expected == null || expected === it) } == true
            } }
            return requireNotNull(browser())
        }
        fun send(browser: WebView, event: String) = compose.runOnIdle {
            val bridge = shadowOf(browser).getJavascriptInterface("__ERead")
            bridge.javaClass.getMethod("event", String::class.java).apply { isAccessible = true }.invoke(bridge, event)
        }
        try {
            compose.setContent {
                view.set(LocalView.current.rootView)
                MaterialTheme {
                    ReaderScreen(book, ReaderSettings(continuousEbookScroll = false), emptyList(), {}, { chapter, _, offset, _, _ -> progress += chapter to offset },
                        {}, {}, {}, {}, emptyFlow(), ebookSession = session)
                }
            }
            val first = awaitBrowser()
            compose.runOnIdle { assertTrue(first.envelope.contains("\"offset\":120")) }
            send(first, """{"type":"ready"}""")
            send(first, """{"type":"viewport","offset":120,"endOffset":250,"pages":8}""")
            compose.waitUntil { compose.runOnIdle { progress.any { it == 0 to 120 } } }
            compose.runOnIdle { assertFalse(progress.any { it == 0 to 0 }) }
            send(first, """{"type":"tap"}""")
            compose.onNodeWithContentDescription("下一章").performClick()
            compose.waitUntil(15_000) { compose.runOnIdle { browser() !== first && browser()?.onBridgeEvent != null } }
            val second = awaitBrowser()
            send(second, """{"type":"ready"}""")
            val end = readChapterText(book, 1).length
            send(second, """{"type":"viewport","offset":0,"endOffset":$end,"pages":1}""")
            compose.waitUntil { compose.runOnIdle { progress.any { it.first == 1 } } }
            compose.onNodeWithContentDescription("上一章").performClick()
            awaitBrowser(first)
            compose.runOnIdle {
                assertFalse(first.disposed)
                assertTrue(first.pageReady)
            }
        } finally {
            compose.runOnIdle { session.close() }
            root.deleteRecursively()
        }
    }
}
