package com.eyue.reader.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.eyue.reader.data.*
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
class ReaderSeekPreviewRobolectricTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rasterPreviewUsesTheSameRoundingAndChapterBoundariesAsSeeking() {
        assertNull(readerRasterSeekLocation(listOf(0, 0), .5f))
        assertEquals(ReaderPageLocation(0, 1), readerRasterSeekLocation(listOf(2), .5f))
        assertEquals(ReaderPageLocation(2, 1), readerRasterSeekLocation(listOf(0, 2, 2), 1f))
        assertEquals(ReaderPageLocation(1, 0), readerRasterSeekLocation(listOf(0, 2), -1f))
    }

    @Test fun offscreenPreviewReportsOnlyTheCurrentTargetAndDisposesThePreviousChapter() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "ebook-seek-${UUID.randomUUID()}").apply { mkdirs() }
        val source = EpubFixtures.create(context, root, "preview", paragraphs = 15)
        val book = EpubTestImporter(context).import(source, File(root, "book").apply { mkdirs() }, "preview", "preview")
        val store = EbookSourceStore(context, book)
        val target = mutableStateOf(ReaderScrollLocation(0, 30))
        val shown = mutableStateOf(true)
        val rootView = AtomicReference<View>()
        val reported = AtomicReference<ReaderSeekPage?>()
        val reads = mutableListOf<Int>()
        fun browser(): EbookWebView? {
            fun find(view: View?): EbookWebView? = if (view is EbookWebView) view else (view as? ViewGroup)?.let { group ->
                (0 until group.childCount).firstNotNullOfOrNull { find(group.getChildAt(it)) }
            }
            return find(rootView.get())
        }
        fun awaitBrowser(previous: EbookWebView? = null): EbookWebView {
            compose.waitUntil(15_000) { compose.runOnIdle { browser()?.let { it !== previous && it.onBridgeEvent != null } == true } }
            return requireNotNull(browser())
        }
        fun send(view: EbookWebView, json: String) = compose.runOnIdle {
            val bridge = shadowOf(view).getJavascriptInterface("__ERead")
            bridge.javaClass.getMethod("event", String::class.java).apply { isAccessible = true }.invoke(bridge, json)
        }
        try {
            compose.setContent {
                rootView.set(LocalView.current.rootView)
                MaterialTheme { if (shown.value) EbookSeekPreview(store, book.chapters, target.value,
                    { index -> reads += index; readChapterText(book, index) }, ReaderSettings(readingMode = ReadingMode.PAGED),
                    Color.Black, false, false, null, Modifier.fillMaxSize(), reported::set) }
            }
            val first = awaitBrowser()
            send(first, """{"type":"ready"}""")
            send(first, """{"type":"viewport","offset":0,"endOffset":60,"page":1,"pages":8}""")
            compose.runOnIdle { assertEquals("Reported=${reported.get()}, viewport=${first.viewport}, disposed=${first.disposed}", 2, reported.get()?.page) }
            compose.runOnIdle { target.value = ReaderScrollLocation(0, 100) }
            send(first, """{"type":"viewport","offset":0,"endOffset":60,"page":1,"pages":8}""")
            compose.runOnIdle { assertEquals(30, requireNotNull(reported.get()).location.offset); assertSame(first, browser()) }
            send(first, """{"type":"viewport","offset":90,"endOffset":130,"page":3,"pages":8}""")
            compose.runOnIdle { assertEquals("Reported=${reported.get()}, viewport=${first.viewport}", 100, reported.get()?.location?.offset) }
            compose.runOnIdle { assertEquals(4, requireNotNull(reported.get()).page); target.value = ReaderScrollLocation(1, 1) }
            awaitBrowser(first)
            compose.runOnIdle { assertTrue(first.disposed); assertEquals(listOf(0, 1), reads); shown.value = false }
            compose.waitForIdle()
        } finally { compose.runOnIdle { shown.value = false }; store.close(); root.deleteRecursively() }
    }
}
