package com.eyue.reader.ui

import android.content.Context
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.eyue.reader.data.*
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.time.Duration
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ReaderProgressReturnRobolectricTest {
    @get:Rule val compose = createComposeRule()

    @Test fun consecutiveSeeksReturnToTheImmediatelyPreviousTarget() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "rapid-seek-${UUID.randomUUID()}").apply { mkdirs() }
        val chapters = (0..3).map { index ->
            val text = "Reading paragraph beside the window.\n".repeat(30)
            Chapter(title = "第${index + 1}章", characterCount = text.length,
                textPath = File(root, "$index.txt").apply { writeText(text) }.absolutePath)
        }
        val book = Book(title = "连续跳转", format = BookFormat.TXT, mode = LibraryMode.NOVEL, chapters = chapters)
        var observedChapter = -1
        fun awaitChapter(index: Int) {
            val until = System.nanoTime() + 15_000_000_000L
            while (System.nanoTime() < until) {
                compose.mainClock.advanceTimeBy(50)
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
                compose.waitForIdle()
                if (observedChapter == index) return
                Thread.sleep(10)
            }
            fail("Expected chapter $index, observed $observedChapter. ${compose.onRoot().printToString().take(2500)}")
        }
        try {
            compose.setContent { MaterialTheme {
                ReaderScreen(book, ReaderSettings(readingMode = ReadingMode.PAGED, showTime = false, showBattery = false), emptyList(), {},
                    { chapter, _, _, _, _ -> observedChapter = chapter }, {}, {}, {}, {}, emptyFlow())
            } }
            awaitChapter(0)
            compose.onNodeWithTag("reader_body").performClick()
            compose.onNodeWithTag("reader_progress").performTouchInput {
                click(Offset(width * .35f, height / 2f))
            }
            awaitChapter(1)
            compose.onNodeWithTag("reader_progress").performTouchInput {
                click(Offset(width * .82f, height / 2f))
            }
            awaitChapter(3)
            compose.onNodeWithTag("reader_jump_return").assertTextContains("返回 第2章", substring = true)
            compose.onNodeWithTag("reader_jump_return").performClick()
            awaitChapter(1)
            compose.onNodeWithTag("reader_jump_return").assertDoesNotExist()
        } finally { root.deleteRecursively() }
    }
}
