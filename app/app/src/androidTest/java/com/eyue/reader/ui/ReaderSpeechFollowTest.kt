package com.eyue.reader.ui

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eyue.reader.data.Book
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.Chapter
import com.eyue.reader.data.InlineImage
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.ReaderSettings
import com.eyue.reader.data.ReadingMode
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ReaderSpeechFollowTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: File
    private lateinit var context: Context
    private val controller = HeldSpeech()
    private val volume = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    private data class Position(val chapter: Int, val page: Int, val offset: Int)
    private val position = AtomicReference<Position?>()
    private val line = "The reader follows original characters and preserves every paragraph.\n"

    @Before fun setUp() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        root = File(base.cacheDir, "speech-follow-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        }
    }

    @After fun tearDown() { root.deleteRecursively() }

    @Test fun scrollFollowsLatestCrossChapterRangeAndResumesOnlyAfterManualDragSettles() {
        val first = line.repeat(100)
        val second = line.repeat(160)
        val book = book(first, second)
        show(book, ReadingMode.SCROLL)
        startListening()
        val initialScroll = scrollPosition()
        val destination = line.length * 25
        compose.runOnIdle {
            // These callbacks arrive before the previous scroll request can finish.
            controller.range(0, first.length)
            controller.range(1, 0)
            controller.range(1, destination)
        }
        awaitPosition { it.chapter == 1 && it.offset == destination }
        assertTrue(scrollPosition() > initialScroll)
        val oldCallback = requireNotNull(controller.callback)
        compose.onNodeWithTag("reader_scroll").performTouchInput {
            down(center)
            moveBy(Offset(0f, -200f))
            advanceEventTime(60)
        }
        compose.waitUntil(10_000) { !controller.playing.value }
        assertEquals(1, controller.starts.get())
        compose.runOnIdle { oldCallback(ReaderSpeechSegment(0, 0, 0, first), 0) }
        assertEquals(1, requireNotNull(position.get()).chapter)
        compose.onNodeWithTag("reader_scroll").performTouchInput { moveBy(Offset(0f, -80f)); up() }
        compose.waitUntil(10_000) { controller.starts.get() == 2 && controller.playing.value }
        compose.runOnIdle {
            val resumed = requireNotNull(controller.source.get().firstOrNull())
            val actual = requireNotNull(position.get())
            assertEquals(actual.chapter, resumed.chapter)
            assertEquals(actual.offset, resumed.offset)
            assertTrue(resumed.offset > destination)
        }
        revealTools()
        compose.onNodeWithContentDescription("朗读").performClick()
        compose.waitUntil(10_000) { !controller.playing.value }
        compose.onNodeWithTag("reader_scroll").performTouchInput { swipeUp() }
        compose.runOnIdle { assertEquals(2, controller.starts.get()); assertFalse(controller.playing.value) }
    }

    @Test fun pagedSpeechFollowsRangesAndManualSwipeVolumeAndContentsContinueAtTheirNewPage() {
        val first = line.repeat(160)
        val second = line.repeat(100)
        show(book(first, second), ReadingMode.PAGED)
        startListening()
        val destination = line.length * 30
        compose.runOnIdle { controller.range(0, destination) }
        awaitPosition { it.chapter == 0 && it.page > 0 && it.offset == destination }
        val spokenPage = requireNotNull(position.get()).page
        compose.onNodeWithTag("reader_body").performTouchInput { swipeLeft() }
        awaitPosition { it.chapter == 0 && it.page == spokenPage + 1 }
        compose.waitUntil(10_000) { controller.starts.get() == 2 }
        assertRestartMatchesPosition()
        compose.runOnIdle { assertTrue(volume.tryEmit(1)) }
        awaitPosition { it.chapter == 0 && it.page == spokenPage + 2 }
        compose.waitUntil(10_000) { controller.starts.get() == 3 }
        assertRestartMatchesPosition()
        revealTools()
        compose.onNodeWithContentDescription("目录").performClick()
        compose.onNodeWithText("第二章").performClick()
        awaitPosition { it.chapter == 1 && it.page == 0 && it.offset == 0 }
        compose.waitUntil(10_000) { controller.starts.get() == 4 }
        assertRestartMatchesPosition()
        assertTrue(controller.source.get().first().text.startsWith(line))
        assertTrue(controller.source.get().none { it.text.contains('\u200B') })
    }

    @Test fun naturalCompletionKeepsTheFinalQueuedChapterAnchorAfterScrollRestoration() {
        val first = line.repeat(100)
        val second = line.repeat(100)
        show(book(first, second), ReadingMode.SCROLL)
        startListening()
        compose.runOnIdle {
            controller.range(0, first.length)
            controller.range(1, second.length)
            controller.playing.value = false
        }
        awaitPosition { it.chapter == 1 && it.offset == second.length }
        compose.runOnIdle { assertFalse(controller.playing.value); assertEquals(1, controller.starts.get()) }
    }

    @Test fun pagedSpeechWithoutRangeTimingStopsAtActualPageEndsAndSkipsInlineImages() {
        val text = (0 until 70).joinToString(" ") { "Harbor route ${it.toString().padStart(3, '0')} follows the quiet road." }
        val image = File(root, "inline.png")
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try { image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
        val chapter = chapter("第一章", text).copy(inlineImages = listOf(InlineImage(110, image.absolutePath)))
        val book = Book(title = "无逐字计时的翻页朗读", format = BookFormat.MOBI, mode = LibraryMode.NOVEL,
            chapters = listOf(chapter), characterOffset = 7, contentVersion = 4)
        val settings = mutableStateOf(ReaderSettings(readingMode = ReadingMode.PAGED, ttsFollowPage = true, fontSize = 40f,
            showTime = false, showBattery = false))
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalDensity provides Density(1f, 1f),
                LocalReaderSpeechFactory provides { controller }) {
                MaterialTheme {
                    Box(Modifier.width(360.dp).height(640.dp)) {
                        ReaderScreen(book, settings.value, emptyList(), onClose = {},
                            onProgress = { chapterIndex, page, offset, _, _ -> position.set(Position(chapterIndex, page, offset)) },
                            onSettings = {}, onAddAnnotation = {}, onDeleteAnnotation = {}, onImportFont = {}, volumeEvents = volume)
                    }
                }
            }
        }
        awaitPosition { it.offset == 7 }
        val displayed = mutableListOf<TextLayoutResult>()
        compose.onNodeWithContentDescription("阅读正文").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(displayed) }
        val pageLength = displayed.single().layoutInput.text.length
        assertTrue("大字号测试页应少于240个字符", pageLength < 240)
        startListening()
        val segments = controller.source.get()
        assertTrue(segments.size > 3)
        assertEquals(7, segments.first().offset)
        assertEquals("首segment不可读到显示页以外", pageLength, segments.first().offset + segments.first().text.length)
        assertEquals(text.substring(7), segments.joinToString("") { it.text })
        assertTrue(segments.zipWithNext().any { (previous, next) -> next.page > previous.page + 1 })
        segments.zipWithNext().forEach { (previous, next) ->
            assertEquals(next.offset, previous.offset + previous.text.length)
        }
        val next = segments[1]
        compose.runOnIdle { controller.completeSegment(0) }
        awaitPosition { it.chapter == next.chapter && it.page == next.page && it.offset == next.offset }
        compose.runOnIdle { assertEquals(1, controller.starts.get()); assertTrue(controller.playing.value) }
        val anchor = requireNotNull(position.get()).offset
        compose.runOnIdle { settings.value = settings.value.copy(fontSize = 50f) }
        compose.waitUntil(30_000) {
            if (compose.onAllNodesWithContentDescription("阅读正文").fetchSemanticsNodes().isEmpty()) false
            else {
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithContentDescription("阅读正文").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                layouts.singleOrNull()?.layoutInput?.style?.fontSize?.value == 50f
            }
        }
        compose.waitForIdle()
        val afterReflow = mutableListOf<TextLayoutResult>()
        compose.onNodeWithContentDescription("阅读正文").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(afterReflow) }
        val newLayout = afterReflow.single()
        assertEquals(50f, newLayout.layoutInput.style.fontSize.value, .01f)
        val shownText = newLayout.layoutInput.text.text
        val pageStart = text.indexOf(shownText)
        assertTrue(pageStart >= 0)
        assertEquals("编号原文使真实页范围可独立定位", pageStart, text.lastIndexOf(shownText))
        assertEquals(anchor, requireNotNull(position.get()).offset)
        assertTrue(anchor in pageStart until pageStart + shownText.length)
        assertSame("重排保留正在播放的原队列", segments, controller.source.get())
        assertEquals(1, controller.starts.get())
        assertTrue(controller.playing.value)
        val following = segments[2]
        compose.runOnIdle { controller.completeSegment(1) }
        awaitPosition { it.chapter == following.chapter && it.offset == following.offset }
        val followingLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithContentDescription("阅读正文").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(followingLayouts) }
        val followingText = followingLayouts.single().layoutInput.text.text
        val followingPageStart = text.indexOf(followingText)
        assertTrue("新回调应跟随重排后的当前页", followingPageStart >= 0 &&
            following.offset in followingPageStart until followingPageStart + followingText.length)
        assertEquals(1, controller.starts.get())
    }

    private fun book(first: String, second: String): Book = Book(title = "朗读跟随测试", format = BookFormat.MOBI,
        mode = LibraryMode.NOVEL, chapters = listOf(chapter("第一章", first), chapter("第二章", second)), contentVersion = 4)

    private fun chapter(title: String, text: String): Chapter {
        val file = File(root, "$title.txt").apply { writeText(text, Charsets.UTF_8) }
        return Chapter(title = title, textPath = file.absolutePath)
    }

    @Test fun independentSpeechHidesItsBarAndOffersTemporaryConfirmOrReturnAfterManualScrolling() {
        show(book(line.repeat(120), line.repeat(80)), ReadingMode.SCROLL, follow = false)
        startListening()
        val initial = requireNotNull(position.get())
        compose.runOnIdle { controller.range(1, line.length * 10) }
        compose.runOnIdle { assertEquals(initial, position.get()) }
        compose.onNodeWithTag("reader_body").performTouchInput { click(Offset(width / 2f, height * .18f)) }
        compose.onNodeWithContentDescription("目录").assertDoesNotExist()
        compose.onNodeWithTag("reader_speech_bar").assertDoesNotExist()
        compose.mainClock.autoAdvance = false
        scrollWithoutFling()
        compose.onNodeWithTag("reader_speech_prompt").assertIsDisplayed()
        compose.onNodeWithText("从当前页面朗读？").assertIsDisplayed()
        compose.runOnIdle { assertTrue(controller.playing.value); assertEquals(1, controller.starts.get()) }
        compose.onNodeWithText("返回原位置").performClick()
        awaitPosition { it.chapter == 1 && it.offset == line.length * 10 }
        assertEquals("返回原位置只移动页面", 1, controller.starts.get())
        assertTrue(controller.playing.value)
        compose.onNodeWithTag("reader_speech_prompt").assertDoesNotExist()
        scrollWithoutFling()
        compose.onNodeWithText("确认", useUnmergedTree = true).performClick()
        awaitReader { controller.starts.get() == 2 }
        assertRestartMatchesPosition()
        compose.onNodeWithTag("reader_speech_bar").assertDoesNotExist()
        scrollWithoutFling()
        compose.onNodeWithTag("reader_speech_prompt").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(1_200)
        compose.onNodeWithTag("reader_speech_prompt").assertDoesNotExist()
        assertTrue(controller.playing.value)
        assertEquals(2, controller.starts.get())
        compose.mainClock.autoAdvance = true
        revealTools()
        compose.onNodeWithContentDescription("暂停朗读").performClick()
        compose.onNodeWithTag("reader_speech_bar").assertIsDisplayed()
        compose.runOnIdle { assertFalse(controller.playing.value) }
    }

    @Test fun systemMediaControlsPauseResumeAndSecondHeadphoneClickEndsTheSession() {
        show(book(line.repeat(120), line.repeat(80)), ReadingMode.SCROLL, follow = false)
        startListening()
        val base = ApplicationProvider.getApplicationContext<Context>()
        val notifications = base.getSystemService(android.app.NotificationManager::class.java)
        compose.waitUntil(10_000) { notifications.activeNotifications.any { it.id == 5711 } }
        val notice = notifications.activeNotifications.first { it.id == 5711 }.notification
        @Suppress("DEPRECATION")
        val token = requireNotNull(notice.extras.getParcelable<android.media.session.MediaSession.Token>(android.app.Notification.EXTRA_MEDIA_SESSION))
        val media = android.media.session.MediaController(base, token)
        media.transportControls.pause()
        compose.waitUntil(10_000) { !controller.playing.value }
        compose.onNodeWithTag("reader_speech_bar").assertIsDisplayed()
        media.transportControls.play()
        compose.waitUntil(10_000) { controller.starts.get() == 2 && controller.playing.value }
        compose.onNodeWithContentDescription("悬浮栏朗读语速").assertIsDisplayed()
        revealTools()
        compose.onNodeWithContentDescription("朗读").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("reader_speech_bar").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { assertFalse(controller.playing.value) }
    }

    @Test fun verticalPagedModeTurnsUpwardsAndStillFollowsSpeechWhenEnabled() {
        show(book(line.repeat(120), line.repeat(80)), ReadingMode.VERTICAL_PAGED)
        val before = requireNotNull(position.get())
        compose.onNodeWithTag("reader_body").performTouchInput { swipeUp() }
        awaitPosition { it.page == before.page + 1 }
        startListening()
        compose.runOnIdle { controller.range(1, line.length * 5) }
        awaitPosition { it.chapter == 1 && it.offset == line.length * 5 }
    }

    private fun show(book: Book, mode: ReadingMode, follow: Boolean = true) {
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalReaderSpeechFactory provides { controller }) {
                MaterialTheme {
                    ReaderScreen(book, ReaderSettings(readingMode = mode, ttsFollowPage = follow, volumePaging = true, showTime = false, showBattery = false),
                        emptyList(), onClose = {}, onProgress = { chapter, page, offset, _, _ -> position.set(Position(chapter, page, offset)) },
                        onSettings = {}, onAddAnnotation = {}, onDeleteAnnotation = {}, onImportFont = {}, volumeEvents = volume)
                }
            }
        }
        awaitPosition { true }
    }

    private fun startListening() {
        revealTools()
        compose.onNodeWithContentDescription("朗读").performClick()
        compose.waitUntil(10_000) { controller.starts.get() == 1 && controller.playing.value }
    }

    private fun revealTools() {
        if (compose.onAllNodesWithContentDescription("目录").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("reader_body").performTouchInput { click(Offset(width / 2f, height * .18f)) }
        }
        compose.onNodeWithContentDescription("目录").assertExists()
    }

    private fun scrollPosition(): Float = compose.onNodeWithTag("reader_scroll").fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun scrollWithoutFling() {
        compose.onNodeWithTag("reader_scroll").performTouchInput {
            down(Offset(width / 2f, height * .6f))
            moveBy(Offset(0f, -64f), delayMillis = 16)
            moveBy(Offset(0f, -16f), delayMillis = 600)
            up()
        }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun awaitPosition(predicate: (Position) -> Boolean) {
        awaitReader { position.get()?.let(predicate) == true }
    }

    private fun awaitReader(predicate: () -> Boolean) {
        compose.waitUntil(30_000) {
            if (!compose.mainClock.autoAdvance) compose.mainClock.advanceTimeByFrame()
            predicate()
        }
        compose.waitForIdle()
    }

    private fun assertRestartMatchesPosition() = compose.runOnIdle {
        val actual = requireNotNull(position.get())
        val resumed = controller.source.get().first()
        assertEquals(actual.chapter, resumed.chapter)
        assertEquals(actual.offset, resumed.offset)
        assertTrue(controller.playing.value)
    }

    private class HeldSpeech : ReaderSpeechController {
        override val playing = MutableStateFlow(false)
        override val error = MutableStateFlow<String?>(null)
        val starts = AtomicInteger(0)
        val source = AtomicReference<List<ReaderSpeechSegment>>(emptyList())
        var callback: ((ReaderSpeechSegment, Int) -> Unit)? = null
        override fun start(source: List<ReaderSpeechSegment>, speed: Float, onLocation: (ReaderSpeechSegment, Int) -> Unit) {
            this.source.set(source)
            callback = onLocation
            starts.incrementAndGet()
            playing.value = true
            source.firstOrNull()?.let { onLocation(it, it.offset) }
        }
        fun range(chapter: Int, offset: Int) { callback?.invoke(source.get().first { it.chapter == chapter }, offset) }
        fun completeSegment(index: Int) {
            val segments = source.get()
            val current = segments[index]
            callback?.invoke(current, current.offset + current.text.length)
            segments.getOrNull(index + 1)?.let { callback?.invoke(it, it.offset) }
        }
        override fun setSpeed(speed: Float) = Unit
        override fun clearError() { error.value = null }
        override fun stop() { playing.value = false }
        override fun release() { stop() }
    }
}
