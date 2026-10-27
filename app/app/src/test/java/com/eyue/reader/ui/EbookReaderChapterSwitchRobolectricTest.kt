package com.eyue.reader.ui

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.eyue.reader.data.EbookSourceStore
import com.eyue.reader.data.ReaderSettings
import com.eyue.reader.data.readChapterText
import com.eyue.reader.data.EpubFixtures
import com.eyue.reader.data.EpubTestImporter
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EbookReaderChapterSwitchRobolectricTest {
    @get:Rule val compose = createComposeRule()

    @Test fun delayedFontRequestsReadTheirOwnSelectionInsteadOfTheMostRecentFont() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder = File(context.cacheDir, "font-urls-${UUID.randomUUID()}").apply { mkdirs() }
        val first = File(folder, "first.ttf").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val second = File(folder, "second.ttf").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val settings = mutableStateOf(ReaderSettings(fontPath = first.path))
        val firstUrl = ebookReaderFontUrl(settings.value)
        var open: ((String) -> java.io.InputStream?)? = null
        try {
            compose.setContent { open = rememberEbookFontOpener(settings.value) }
            compose.runOnIdle { settings.value = settings.value.copy(fontPath = second.path) }
            compose.runOnIdle {
                val secondUrl = ebookReaderFontUrl(settings.value)
                assertNotEquals(firstUrl, secondUrl)
                assertArrayEquals(first.readBytes(), open!!(firstUrl)!!.use { it.readBytes() })
                assertArrayEquals(second.readBytes(), open!!(secondUrl)!!.use { it.readBytes() })
                assertNull(open!!("https://eread.local/reader-font?v=unknown"))
            }
        } finally { folder.deleteRecursively() }
    }

    @Test fun revisitingChaptersAndReopeningTheBookReusesTheirDomAndRejectsOldCallbacks() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "ebook-switch-${UUID.randomUUID()}").apply { mkdirs() }
        val source = EpubFixtures.create(context, root, "switch")
        val book = EpubTestImporter(context).import(source, File(root, "book").apply { mkdirs() }, "switch", "switch")
        val stores = mutableListOf(EbookSourceStore(context, book))
        val currentStore = mutableStateOf(stores.first())
        val chapter = mutableIntStateOf(0)
        val shown = mutableStateOf(true)
        val session = EbookReaderSession(context)
        val rootView = AtomicReference<View>()
        val observed = AtomicReference<EbookViewportState>()
        val conversion = mutableStateOf<com.eyue.reader.data.ChineseTextConverter?>(null)
        val controller = EbookReaderController()
        fun browser() = rootView.get()?.findBrowser()
        fun awaitBrowser(expected: WebView? = null): WebView {
            compose.waitUntil(15_000) { compose.runOnIdle {
                browser()?.let { it.onBridgeEventReady() && (expected == null || it === expected) } == true
            } }
            return requireNotNull(browser())
        }
        try {
            compose.setContent {
                rootView.set(LocalView.current.rootView)
                MaterialTheme {
                    if (shown.value) {
                        val index = chapter.intValue
                        EbookReaderSurface(currentStore.value, index, book.chapters[index], readChapterText(book, index), ReaderSettings(),
                            emptyList(), controller, Modifier.fillMaxSize(), foreground = Color.Black, session = session,
                            chineseConverter = conversion.value,
                            onViewport = observed::set, onTap = {}, onManualStart = {}, onManualEnd = {}, onChapterStep = {},
                            onSelectionActive = {}, onAction = { _, _ -> }, onAnnotationTap = { _, _ -> }, onFootnote = { _, _ -> })
                    }
                }
            }
            val first = awaitBrowser()
            val previous = shadowOf(first).getJavascriptInterface("__ERead")
            send(previous, """{"type":"ready"}""")
            send(previous, """{"type":"viewport","offset":1,"endOffset":4,"pages":1}""")
            compose.waitUntil { observed.get()?.ready == true }
            val documentGeneration = (first as EbookWebView).documentGeneration
            val traditional = runBlocking { com.eyue.reader.data.ChineseTextConverter.load(context, com.eyue.reader.data.ChineseConversion.TRADITIONAL) }
            compose.runOnIdle { conversion.value = traditional }
            compose.runOnIdle {
                assertSame(first, browser())
                assertEquals(documentGeneration, first.documentGeneration)
                assertTrue(shadowOf(first).lastEvaluatedJavascript.contains("convert"))
                assertTrue(observed.get().ready)
            }
            compose.runOnIdle { chapter.intValue = 1 }
            compose.waitUntil(15_000) { compose.runOnIdle { browser() !== first && browser()?.onBridgeEventReady() == true } }
            val second = requireNotNull(browser())
            send(previous, """{"type":"viewport","offset":999,"pages":1}""")
            compose.runOnIdle { assertFalse(observed.get().ready) }
            val current = shadowOf(second).getJavascriptInterface("__ERead")
            send(current, """{"type":"ready"}""")
            send(current, """{"type":"viewport","offset":2,"endOffset":5,"pages":1}""")
            compose.waitUntil { observed.get()?.ready == true }
            compose.runOnIdle { chapter.intValue = 0 }
            awaitBrowser(first)
            compose.runOnIdle { assertTrue(shadowOf(first).lastEvaluatedJavascript.contains("type:'resume'")) }
            send(previous, """{"type":"ready"}""")
            send(previous, """{"type":"viewport","offset":1,"endOffset":4,"pages":1}""")
            compose.runOnIdle { shown.value = false }
            compose.runOnIdle {
                stores += EbookSourceStore(context, book)
                currentStore.value = stores.last()
                shown.value = true
            }
            awaitBrowser(first)
            compose.runOnIdle { assertTrue(shadowOf(first).lastEvaluatedJavascript.contains("type:'resume'")) }
        } finally {
            compose.runOnIdle { shown.value = false }
            compose.runOnIdle { session.close() }
            stores.forEach { it.close() }
            root.deleteRecursively()
        }
    }

    private fun WebView.onBridgeEventReady(): Boolean = (this as? EbookWebView)?.onBridgeEvent != null

    @Test fun chapterDomRetentionStaysBoundedAndDoesNotReuseAnotherBooksPage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.runOnIdle {
            val session = EbookReaderSession(context)
            try {
                val first = session.acquire("book:1:0:text", 0).apply { pageReady = true }
                val second = session.acquire("book:1:1:text", 1).apply { pageReady = true }
                session.acquire("book:1:2:text", 2).pageReady = true
                assertTrue(first.disposed)
                assertFalse(second.disposed)
                assertSame(second, session.acquire("book:1:1:text", 1))
                val changed = session.acquire("book:2:1:edited", 1).apply { pageReady = true }
                assertTrue(second.disposed)
                session.acquire("other-book:1:0:text", 0)
                assertTrue(changed.disposed)
            } finally { session.close() }
        }
    }

    @Test fun incompleteOrFailedPagesAreRecreatedInsteadOfReusingTheirBrowser() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.runOnIdle {
            val session = EbookReaderSession(context)
            try {
                val failed = session.acquire("book:1:0:text", 0)
                session.release(failed)
                val retried = session.acquire("book:1:0:text", 0)
                assertNotSame(failed, retried)
                assertTrue(failed.disposed)
            } finally { session.close() }
        }
    }

    @Test fun adjacentPreloadsDoNotEvictOneAnotherOrLetTheOldOwnerPauseTheAdoptedPage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.runOnIdle {
            val session = EbookReaderSession(context)
            try {
                val reader = Any(); val nextOwner = Any(); val previousOwner = Any()
                val current = session.acquire("book:1:5:current", 5, 5, reader).apply { pageReady = true }
                val next = session.acquire("book:1:6:next", 6, 5, nextOwner)
                val previous = session.acquire("book:1:4:previous", 4, 5, previousOwner)
                assertFalse(current.disposed); assertFalse(next.disposed); assertFalse(previous.disposed)
                next.pageReady = true; previous.pageReady = true
                val adoptedOwner = Any()
                assertSame(next, session.acquire("book:1:6:next", 6, 6, adoptedOwner))
                next.onBridgeEvent = {}
                session.release(next, nextOwner)
                assertNotNull(next.onBridgeEvent)
                assertTrue(previous.disposed)
                session.release(next, adoptedOwner)
                assertNull(next.onBridgeEvent)
            } finally { session.close() }
        }
    }

    private fun send(bridge: Any, json: String) {
        compose.runOnIdle {
            bridge.javaClass.getMethod("event", String::class.java).apply { isAccessible = true }.invoke(bridge, json)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun pageStartupTimeoutDoesNotRunWhileTheReaderIsInTheBackground() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "ebook-timeout-${UUID.randomUUID()}").apply { mkdirs() }
        val source = EpubFixtures.create(context, root, "timeout")
        val book = runBlocking {
            EpubTestImporter(context).import(source, File(root, "book").apply { mkdirs() }, "timeout", "timeout")
                .copy(sourcePath = source.absolutePath)
        }
        val store = EbookSourceStore(context, book)
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this)
        }
        val rootView = AtomicReference<View>()
        val observed = AtomicReference<EbookViewportState>()
        val controller = EbookReaderController()
        try {
            compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
            compose.setContent {
                rootView.set(LocalView.current.rootView)
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    MaterialTheme {
                        EbookReaderSurface(store, 0, book.chapters[0], readChapterText(book, 0), ReaderSettings(),
                            emptyList(), controller, Modifier.fillMaxSize(), foreground = Color.Black,
                            onViewport = observed::set, onTap = {}, onManualStart = {}, onManualEnd = {}, onChapterStep = {},
                            onSelectionActive = {}, onAction = { _, _ -> }, onAnnotationTap = { _, _ -> }, onFootnote = { _, _ -> })
                    }
                }
            }
            compose.waitUntil(15_000) { compose.runOnIdle {
                rootView.get()?.findBrowser()?.let { shadowOf(it).getJavascriptInterface("__ERead") != null } == true
            } }
            compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.CREATED }
            compose.mainClock.advanceTimeBy(70_000)
            compose.runOnIdle { assertNull(observed.get()?.error) }
            compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
            compose.mainClock.advanceTimeBy(29_000)
            compose.runOnIdle { assertNull(observed.get()?.error) }
            compose.mainClock.advanceTimeBy(2_000)
            compose.waitUntil { observed.get()?.error?.contains("页面启动超时") == true }
        } finally {
            compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.DESTROYED }
            store.close()
            root.deleteRecursively()
        }
    }

    private fun View.findBrowser(): WebView? = if (this is WebView) this else (this as? ViewGroup)?.let { group ->
        (0 until group.childCount).firstNotNullOfOrNull { group.getChildAt(it).findBrowser() }
    }
}
