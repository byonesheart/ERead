package com.eyue.reader.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import com.eyue.reader.data.BitmapLoader
import com.eyue.reader.data.Book
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.LibraryMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-mdpi", shadows = [BookCoverRegionDecoderShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookCoverNavigationRobolectricTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: File
    @Before fun setup() { root = File(RuntimeEnvironment.getApplication().cacheDir, "cover-navigation-${UUID.randomUUID()}").apply { mkdirs() } }
    @After fun cleanup() { root.deleteRecursively() }

    @Test fun returningThroughSettingsShowsTheCachedCoverBeforeFileValidationFinishes() {
        val file = File(root, "cover.png")
        val original = Bitmap.createBitmap(16, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        try { file.outputStream().use { check(original.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { original.recycle() }
        val book = Book(id = "navigation-cover", title = "封面切换", format = BookFormat.EPUB,
            mode = LibraryMode.NOVEL, coverPath = file.path, chapters = emptyList())
        val decodes = AtomicInteger()
        val pending = AtomicReference<ValidationGate?>()
        val gates = mutableListOf<ValidationGate>()
        val cache = BookCoverCache(decode = { source -> decodes.incrementAndGet(); BitmapLoader.decodeThumbnail(source) },
            version = { source ->
                pending.getAndSet(null)?.let { gate ->
                    gate.entered.countDown()
                    check(gate.release.await(15, TimeUnit.SECONDS)) { "Cover validation was not released" }
                }
                if (source.isFile) "${source.path}:${source.length()}:${source.lastModified()}" else null
            })
        val screen = mutableIntStateOf(0)
        compose.setContent {
            CompositionLocalProvider(LocalBookCoverCache provides cache) {
                MaterialTheme {
                    when (screen.intValue) {
                        0 -> ReadingHome(listOf(book), 0, {}, {}, {})
                        1 -> Bookshelf(listOf(book), "", {}, {}, {}, {})
                        else -> Text("设置")
                    }
                }
            }
        }
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("《封面切换》封面").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, decodes.get())
            for (destination in listOf(1, 0, 1)) {
                compose.runOnIdle { screen.intValue = 2 }
                compose.onNodeWithText("设置").assertIsDisplayed()
                val gate = ValidationGate().also(gates::add)
                pending.set(gate)
                compose.runOnIdle { screen.intValue = destination }
                // Drain Android layout too: the lazy shelf has no item effects before measurement.
                compose.waitForIdle()
                compose.onNodeWithTag(if (destination == 0) "reading_home" else "bookshelf").assertIsDisplayed()
                compose.waitUntil(10_000) { gate.entered.count == 0L }
                // The IO result is deliberately unavailable; this is the remounted screen's initial image.
                compose.onNodeWithContentDescription("《封面切换》封面").assertIsDisplayed()
                assertEquals(1, decodes.get())
                gate.release.countDown()
                compose.waitForIdle()
            }
        } finally {
            gates.forEach { it.release.countDown() }
            pending.set(null)
            compose.runOnIdle { screen.intValue = 2 }
            // Join any validation already in flight before Robolectric tears down its sandbox.
            runBlocking { cache.load(BookCoverKey(file.path, book.contentVersion, false)) }
        }
    }

    private class ValidationGate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
    }
}
