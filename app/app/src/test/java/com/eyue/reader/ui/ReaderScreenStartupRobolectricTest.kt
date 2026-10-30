package com.eyue.reader.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.eyue.reader.data.Book
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.Chapter
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.PageSource
import com.eyue.reader.data.ReaderSettings
import com.eyue.reader.data.ReadingMode
import com.eyue.reader.data.TapPagingMode
import com.eyue.reader.readerPageKeyDirection
import android.view.KeyEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Executes the actual reader composition and startup effects, including system TTS setup. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [ReaderNarrationInteractionRobolectricTest.NoSurfaceMagnifier::class])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderScreenStartupRobolectricTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: File
    private data class Position(val chapter: Int, val offset: Int, val progress: Float)
    private val position = AtomicReference<Position?>()
    private var windowHeight = 0
    private var audioService: ServiceController<ReaderAudioService>? = null

    @Before fun setUp() {
        root = File(RuntimeEnvironment.getApplication().cacheDir, "reader-startup-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After fun tearDown() {
        if (audioService != null) compose.runOnIdle {
            ReaderAudioService.owner?.stop()
            audioService?.destroy()
        }
        root.deleteRecursively()
    }

    @Test fun automaticPagingStartsFromSettingsAndManualControlsPauseIt() {
        val marker = "AUTO_PAGING_READER_BODY"
        showReader(textBook(BookFormat.TXT, "$marker\n" + "This is a paragraph on the next automatically turned page.\n".repeat(120)),
            ReadingMode.PAGED, { SilentSpeech() }, initialSettings = ReaderSettings(autoPageSeconds = 2f))
        awaitText(marker)
        revealToolbar()
        val toolbarTop = compose.onNodeWithTag("reader_toolbar").fetchSemanticsNode().boundsInRoot.top
        assertTrue(compose.onNodeWithTag("reader_auto_floating_action").fetchSemanticsNode().boundsInRoot.bottom <= toolbarTop)
        assertTrue(compose.onNodeWithTag("reader_speech_floating_action").fetchSemanticsNode().boundsInRoot.bottom <= toolbarTop)
        compose.onNodeWithTag("reader_auto_floating_action").performClick()
        compose.onNodeWithText("自动翻页").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("reader_auto_start").performClick()
        fun advance(milliseconds: Long) {
            compose.runOnUiThread { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnUiThread { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(milliseconds)) }
            compose.mainClock.advanceTimeBy(milliseconds)
            compose.runOnUiThread { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() }
            compose.waitForIdle()
        }
        advance(2300)
        compose.waitUntil(15_000) { advance(32); (position.get()?.offset ?: 0) > 0 }
        compose.onNodeWithTag("reader_body").performTouchInput { click() }
        advance(32)
        compose.onNodeWithTag("reader_auto_floating_action").performClick()
        advance(32)
        compose.onNodeWithTag("reader_auto_bar").assertIsDisplayed()
        val pausedOffset = position.get()?.offset
        advance(5000)
        assertEquals(pausedOffset, position.get()?.offset)
        compose.onNodeWithTag("reader_auto_stop").performClick()
        advance(32)
        compose.onNodeWithTag("reader_auto_bar").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
    }

    @Test fun defaultSystemSpeechFactoryOpensTxtWithoutStartingNarration() {
        val marker = "DEFAULT_SYSTEM_SPEECH_STARTUP_BODY"
        showReader(textBook(BookFormat.TXT, marker), ReadingMode.SCROLL)
        awaitText(marker)
        compose.onNodeWithTag("reader_speech_bar").assertDoesNotExist()
    }

    @Test fun leftRightClicksAndCommonEreaderKeyPairsTurnTheActualPage() {
        val keys = MutableSharedFlow<Int>(extraBufferCapacity = 8)
        val text = "CLICK_AND_KEYS_BODY\n" + "An ordinary paragraph fills part of a real reader page.\n".repeat(160)
        showReader(textBook(BookFormat.TXT, text), ReadingMode.PAGED, { SilentSpeech() },
            initialSettings = ReaderSettings(volumePaging = true), keyEvents = keys)
        awaitText("CLICK_AND_KEYS_BODY")
        compose.waitUntil(15_000) { position.get() != null }
        revealToolbar()
        compose.onNodeWithContentDescription("界面设置").performClick()
        compose.onNodeWithTag("reader_section_翻页方式").performScrollTo().performClick()
        compose.onNodeWithTag("reader_tap_horizontal").performScrollTo().performClick()
        compose.onNodeWithTag("reader_body").performTouchInput { click(Offset(width * .5f, height * .2f)) }
        compose.onNodeWithTag("reader_settings_sheet").assertDoesNotExist()
        compose.onNodeWithTag("reader_body").performTouchInput { click(Offset(width * .9f, height * .5f)) }
        compose.waitUntil(15_000) { (position.get()?.offset ?: 0) > 0 }
        compose.onNodeWithTag("reader_body").performTouchInput { click(Offset(width * .1f, height * .5f)) }
        compose.waitUntil(15_000) { position.get()?.offset == 0 }
        for ((next, previous) in listOf(KeyEvent.KEYCODE_PAGE_DOWN to KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_DPAD_RIGHT to KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_VOLUME_DOWN to KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_BUTTON_2 to KeyEvent.KEYCODE_BUTTON_1)) {
            compose.runOnIdle { assertTrue(keys.tryEmit(requireNotNull(readerPageKeyDirection(next)))) }
            compose.waitUntil(15_000) { (position.get()?.offset ?: 0) > 0 }
            compose.runOnIdle { assertTrue(keys.tryEmit(requireNotNull(readerPageKeyDirection(previous)))) }
            compose.waitUntil(15_000) { position.get()?.offset == 0 }
        }
        compose.onNodeWithTag("reader_body").performTouchInput { click() }
        compose.onNodeWithContentDescription("目录").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, position.get()?.offset) }
    }

    @Test fun topBottomClickLayoutPagesVerticallyAndKeepsTheMiddleForControls() {
        val text = "VERTICAL_CLICK_BODY\n" + "Another paragraph fills a real reader page.\n".repeat(100)
        showReader(textBook(BookFormat.TXT, text), ReadingMode.VERTICAL_PAGED, { SilentSpeech() },
            initialSettings = ReaderSettings(tapPaging = TapPagingMode.VERTICAL))
        awaitText("VERTICAL_CLICK_BODY")
        compose.waitUntil(15_000) { position.get() != null }
        compose.onNodeWithTag("reader_body").performTouchInput { click(Offset(width * .5f, height * .9f)) }
        compose.waitUntil(15_000) { (position.get()?.offset ?: 0) > 0 }
        compose.onNodeWithTag("reader_body").performTouchInput { click(Offset(width * .5f, height * .1f)) }
        compose.waitUntil(15_000) { position.get()?.offset == 0 }
        compose.onNodeWithTag("reader_body").performTouchInput { click() }
        compose.onNodeWithContentDescription("目录").assertIsDisplayed()
    }

    @Test fun holdingTheActualReaderEdgeTurnsSeveralPagesAndReleaseKeepsTheToolbarClosed() {
        val text = "HOLD_PAGING_BODY\n" + "A paragraph fills another page without changing the canonical text.\n".repeat(200)
        showReader(textBook(BookFormat.TXT, text), ReadingMode.PAGED, { SilentSpeech() },
            initialSettings = ReaderSettings(tapPaging = TapPagingMode.HORIZONTAL))
        awaitText("HOLD_PAGING_BODY")
        compose.waitUntil(15_000) { position.get() != null }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("reader_body").performTouchInput { down(Offset(width * .9f, center.y)) }
        advanceHoldBy(1200)
        val stopped = position.get()!!.offset
        compose.runOnIdle { assertTrue("offset=$stopped", stopped > 0) }
        compose.onNodeWithTag("reader_body").performTouchInput { up() }
        advanceHoldBy(1500)
        compose.runOnIdle { assertEquals(stopped, position.get()!!.offset) }
        compose.onNodeWithTag("reader_toolbar").assertDoesNotExist()
    }

    @Test fun holdingAtTheBookBoundaryDoesNotLeaveQueuedNoticesAfterRelease() {
        showReader(textBook(BookFormat.TXT, "HOLD_LAST_PAGE"), ReadingMode.PAGED, { SilentSpeech() },
            initialSettings = ReaderSettings(tapPaging = TapPagingMode.HORIZONTAL))
        awaitText("HOLD_LAST_PAGE")
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("reader_body").performTouchInput { down(Offset(width * .9f, center.y)) }
        advanceHoldBy(1500)
        compose.onNodeWithText("已到全书末尾").assertDoesNotExist()
        compose.onNodeWithTag("reader_body").performTouchInput { up() }
        advanceHoldBy(1500)
        compose.onNodeWithText("已到全书末尾").assertDoesNotExist()
        compose.onNodeWithTag("reader_toolbar").assertDoesNotExist()
    }

    private fun advanceHoldBy(milliseconds: Long) {
        compose.runOnUiThread { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(milliseconds)
        compose.runOnUiThread { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(milliseconds)) }
        compose.waitForIdle()
    }

    @Test fun pagedNovelOpensWithSpeechFactoryIsolated() {
        val marker = "PAGED_NOVEL_STARTUP_BODY"
        showReader(textBook(BookFormat.MOBI, marker), ReadingMode.PAGED, { SilentSpeech() })
        awaitText(marker)
    }

    @Test fun finalSaveProgressRemainsVisibleAfterTheDraftFlagClears() {
        val marker = "FINAL_SAVE_PROGRESS_BODY"
        showReader(textBook(BookFormat.TXT, marker), ReadingMode.SCROLL, { SilentSpeech() },
            saveProgress = com.eyue.reader.data.BookSaveProgress("清理保存缓存", .99f))
        awaitText(marker)
        compose.onNodeWithTag("reader_save_book").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("reader_save_progress").assertIsDisplayed()
        compose.onNodeWithText("99%").assertIsDisplayed()
        val progress = compose.onNodeWithTag("reader_save_progress").fetchSemanticsNode().boundsInRoot
        val bounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("Save progress must fit within the phone width", progress.right <= bounds.right)
    }

    @Test fun imageComicOpensWithSpeechFactoryIsolated() {
        val image = File(root, "page.png")
        val bitmap = Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.GREEN)
            image.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        val book = Book(title = "图片冷启动", format = BookFormat.IMAGES, mode = LibraryMode.COMIC,
            chapters = listOf(Chapter(title = "第一章", pages = listOf(PageSource(image.absolutePath)))))
        showReader(book, ReadingMode.SCROLL, { SilentSpeech() })
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("reader_loading").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithContentDescription("漫画或 PDF 页面").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("reader_body").assertIsDisplayed()
        compose.onNodeWithContentDescription("漫画或 PDF 页面").assertIsDisplayed()
        compose.onNodeWithText("这一页读取失败", substring = true).assertDoesNotExist()
    }

    @Test fun toolbarPreviousAndNextChapterNavigateTheActualReader() {
        showReader(navigationBook(), ReadingMode.PAGED, { SilentSpeech() })
        awaitText("TOOLBAR_CHAPTER_0")
        revealToolbar()
        clickChapterNavigation("下一章")
        awaitChapter(1)
        compose.onNodeWithText("TOOLBAR_CHAPTER_1", substring = true).assertIsDisplayed()
        clickChapterNavigation("上一章")
        awaitChapter(0)
        compose.onNodeWithText("TOOLBAR_CHAPTER_0", substring = true).assertIsDisplayed()
    }

    @Test fun draggingProgressOffersReturnButClosingToolbarClearsIt() {
        showReader(navigationBook(), ReadingMode.PAGED, { SilentSpeech() })
        awaitText("TOOLBAR_CHAPTER_0")
        revealToolbar()
        compose.onNodeWithTag("reader_progress").performTouchInput {
            down(Offset(width * .05f, height / 2f))
            moveTo(Offset(width * .8f, height / 2f))
            up()
        }
        compose.waitUntil(15_000) {
            val loading = compose.onAllNodesWithTag("reader_loading").fetchSemanticsNodes().isNotEmpty()
            val hasReturn = compose.onAllNodesWithTag("reader_jump_return").fetchSemanticsNodes().isNotEmpty()
            !loading && hasReturn && position.get()?.chapter?.let { it > 0 } == true
        }
        compose.onNodeWithTag("reader_jump_return").assertIsDisplayed()
        compose.onNodeWithTag("reader_body").performTouchInput { click() }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("目录").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithTag("reader_jump_return").fetchSemanticsNodes().isEmpty()
        }
        revealToolbar()
        compose.onNodeWithTag("reader_jump_return").assertDoesNotExist()
    }

    @Test fun compactSettingsStayWithinHalfScreenAndKeepFontAndSpacingInline() {
        showReader(navigationBook(), ReadingMode.PAGED, { SilentSpeech() })
        awaitText("TOOLBAR_CHAPTER_0")
        revealToolbar()
        compose.onNodeWithContentDescription("阅读设置").assertIsDisplayed()
        compose.onNodeWithContentDescription("界面设置").performClick()
        val sheet = compose.onNodeWithTag("reader_settings_sheet")
        sheet.assertIsDisplayed()
        assertTrue(windowHeight > 0)
        assertTrue(sheet.fetchSemanticsNode().boundsInRoot.height <= windowHeight / 2f + 1f)
        compose.onNodeWithTag("reader_section_文字与排版").performScrollTo().performClick()
        compose.onNodeWithText("行距").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("字距").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("黑体").performScrollTo().performClick()
        compose.onNodeWithText("黑体").assertIsSelected()
        sheet.assertIsDisplayed()
    }

    @Test fun interfaceAndPreferencesOpenDirectlyFromSeparateToolbarActions() {
        showReader(navigationBook(), ReadingMode.PAGED, { SilentSpeech() })
        awaitText("TOOLBAR_CHAPTER_0")
        revealToolbar()
        compose.onNodeWithContentDescription("界面设置").assertIsDisplayed()
        compose.onNodeWithContentDescription("阅读设置").performClick()
        compose.onNodeWithText("音量键与实体键翻页").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("reader_save_theme").assertDoesNotExist()
        compose.onNodeWithTag("reader_settings_tab_interface").assertDoesNotExist()
        compose.onNodeWithTag("reader_body").performTouchInput { click(Offset(center.x, height * .1f)) }
        compose.onNodeWithTag("reader_settings_sheet").assertDoesNotExist()
        revealToolbar()
        compose.onNodeWithContentDescription("界面设置").performClick()
        compose.onNodeWithTag("reader_save_theme").assertDoesNotExist()
        compose.onNodeWithTag("reader_new_theme").assertIsDisplayed()
        compose.onNodeWithText("音量键翻页").assertDoesNotExist()
        compose.onNodeWithTag("reader_settings_tab_preferences").assertDoesNotExist()
    }

    @Test fun editingAThemeSurvivesTheMoreFontsDialogAndAutosavesBackToTheSameProfile() {
        showReader(navigationBook(), ReadingMode.PAGED, { SilentSpeech() })
        awaitText("TOOLBAR_CHAPTER_0")
        revealToolbar()
        compose.onNodeWithContentDescription("界面设置").performClick()
        compose.onNodeWithText("配置1", substring = false).performTouchInput { longClick() }
        compose.onNodeWithTag("reader_section_文字与排版").performScrollTo().performClick()
        compose.onNodeWithText("字体", substring = false).performScrollTo()
        compose.onNodeWithTag("reader_more_fonts").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("等宽字体", substring = false).performClick()
        compose.onNodeWithText("完成", substring = false).performClick()
        compose.onNodeWithText("界面 · 配置1").assertIsDisplayed()
        compose.onNodeWithTag("reader_save_theme").assertDoesNotExist()
        compose.onNodeWithText("配置2", substring = false).assertDoesNotExist()
        compose.onNodeWithText("配置", substring = false).performScrollTo()
        compose.onNodeWithText("配置1", substring = false).assertIsDisplayed()
    }

    @Test fun narrationBarAdjustsSpeedAndSyncAndCanPauseOrStopFromTheHeadphonesButton() {
        val speech = SilentSpeech()
        showReader(textBook(BookFormat.TXT, "NARRATION_TOOLBAR_BODY"), ReadingMode.PAGED, { speech })
        awaitText("NARRATION_TOOLBAR_BODY")
        revealToolbar()
        compose.onNodeWithContentDescription("朗读").performClick()
        compose.waitUntil(15_000) { ReaderAudioService.owner?.pending != null }
        compose.runOnIdle {
            audioService = Robolectric.buildService(ReaderAudioService::class.java).create()
            audioService!!.get().onStartCommand(null, 0, 1)
        }
        compose.waitUntil(10_000) { speech.playing.value }
        compose.onNodeWithTag("reader_speech_bar").assertIsDisplayed()
        val brightnessBounds = compose.onNodeWithTag("reader_brightness").fetchSemanticsNode().boundsInRoot
        val speechBounds = compose.onNodeWithTag("reader_speech_bar").fetchSemanticsNode().boundsInRoot
        assertTrue("Brightness stays to the left and above floating narration controls", brightnessBounds.bottom <= speechBounds.top)
        val speedSlider = compose.onNodeWithContentDescription("悬浮栏朗读语速")
        speedSlider.performTouchInput {
            down(Offset(width / 3f, height / 2f))
            moveTo(Offset(width * 2f / 3f, height / 2f))
            up()
        }
        val targetSpeed = speedSlider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
        assertTrue("Drag must change speed: controller=${speech.observedSpeed}, UI=$targetSpeed, bounds=${speedSlider.fetchSemanticsNode().boundsInRoot}",
            abs(targetSpeed - 1f) > .001f)
        try {
            compose.waitUntil(5_000) { abs(speech.observedSpeed - targetSpeed) < .001f }
        } catch (failure: ComposeTimeoutException) {
            val actualUiSpeed = speedSlider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
            throw AssertionError("Released slider speed was not applied: controller=${speech.observedSpeed}, UI=$actualUiSpeed, target=$targetSpeed", failure)
        }
        compose.onNodeWithText("同步").performClick().assertIsSelected()
        val syncLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("同步").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(syncLayouts) }
        assertEquals(Color(0xFFE35858), syncLayouts.single().layoutInput.style.color)
        compose.onNodeWithContentDescription("暂停朗读").performClick()
        compose.waitUntil(5_000) { !speech.playing.value }
        compose.onNodeWithTag("reader_speech_bar").assertIsDisplayed()
        compose.onNodeWithContentDescription("继续朗读").assertIsDisplayed()
        compose.onNodeWithContentDescription("朗读").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("reader_speech_bar").fetchSemanticsNodes().isEmpty()
        }
        assertFalse(speech.playing.value)
        assertFalse(speech.released)
    }

    private fun textBook(format: BookFormat, marker: String): Book {
        val text = "$marker\n普通正文在首次打开时应正常显示。"
        val path = File(root, "${UUID.randomUUID()}.txt").apply { writeText(text) }
        return Book(title = "小说冷启动", format = format, mode = LibraryMode.NOVEL,
            chapters = listOf(Chapter(title = "第一章", textPath = path.absolutePath, characterCount = text.length)))
    }

    private fun navigationBook(): Book = Book(title = "工具栏交互", format = BookFormat.MOBI, mode = LibraryMode.NOVEL,
        chapters = (0..2).map { index ->
            val text = "TOOLBAR_CHAPTER_$index\n" + "A paragraph is long enough to fill several real reader pages.\n".repeat(18)
            Chapter(title = "第 ${index + 1} 章", textPath = File(root, "$index.txt").apply { writeText(text) }.absolutePath,
                characterCount = text.length)
        })

    @Test fun repeatedInterfaceSpacingChangesKeepTheOriginalMidChapterReadingPosition() {
        val text = "ANCHOR_TEXT_START\n" + "This paragraph has enough text to wrap into many pages and stay readable.\n".repeat(200)
        val book = textBook(BookFormat.TXT, text).copy(characterOffset = 4000, progress = .3f)
        showReader(book, ReadingMode.PAGED, { SilentSpeech() })
        awaitChapter(0)
        val original = position.get()!!.offset
        assertTrue(original > 2000)
        revealToolbar(); compose.onNodeWithContentDescription("界面设置").performClick()
        compose.onNodeWithTag("reader_section_文字与排版").performScrollTo().performClick()
        repeat(8) { index ->
            compose.onNodeWithContentDescription("阅读行间距").performScrollTo()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(if (index % 2 == 0) 2.2f else 1.85f) }
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("reader_loading").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithContentDescription("阅读字间距").performScrollTo()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(if (index % 2 == 0) 1.5f else .5f) }
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("reader_loading").fetchSemanticsNodes().isEmpty() }
            compose.runOnIdle { assertEquals("Spacing change $index drifted toward chapter start", original, position.get()!!.offset) }
        }
    }

    @Test fun paperEdgeDragPreviewsTheNextNativePageAndCommitsOnlyOnRelease() {
        showReader(textBook(BookFormat.TXT, "PAPER_DRAG_BODY\n" + "A long paragraph for dragging across physical book pages.\n".repeat(150)),
            ReadingMode.PAGED, { SilentSpeech() })
        awaitText("PAPER_DRAG_BODY")
        val original = position.get()!!.offset
        val body = compose.onNodeWithTag("reader_body")
        body.performTouchInput { down(Offset(width - 2f, center.y)); moveBy(Offset(-width * .4f, 0f)) }
        compose.onNodeWithTag("reader_paper_drag").assertExists()
        compose.runOnIdle { assertEquals(original, position.get()!!.offset) }
        body.performTouchInput { up() }
        compose.waitUntil(15_000) { position.get()!!.offset > original }
        compose.onNodeWithTag("reader_paper_drag").assertDoesNotExist()
        compose.onNodeWithTag("reader_toolbar").assertDoesNotExist()
    }

    private fun showReader(book: Book, mode: ReadingMode, factory: ((Context) -> ReaderSpeechController)? = null,
        saveProgress: com.eyue.reader.data.BookSaveProgress? = null,
        initialSettings: ReaderSettings = ReaderSettings(), keyEvents: Flow<Int> = emptyFlow()) {
        compose.setContent {
            MaterialTheme {
                windowHeight = LocalWindowInfo.current.containerSize.height
                var settings by remember { mutableStateOf(initialSettings.copy(readingMode = mode, showTime = false, showBattery = false)) }
                val reader: @Composable () -> Unit = {
                    ReaderScreen(book, settings,
                        emptyList(), {}, { chapter, _, offset, progress, _ -> position.set(Position(chapter, offset, progress)) },
                        { settings = it }, {}, {}, {}, keyEvents, saveProgress = saveProgress)
                }
                if (factory == null) reader()
                else CompositionLocalProvider(LocalReaderSpeechFactory provides factory) { reader() }
            }
        }
    }

    private fun revealToolbar() {
        if (compose.onAllNodesWithContentDescription("目录").fetchSemanticsNodes().isEmpty())
            compose.onNodeWithTag("reader_body").performTouchInput { click() }
        compose.onNodeWithContentDescription("目录").assertIsDisplayed()
    }

    private fun awaitChapter(chapter: Int) {
        compose.waitUntil(15_000) {
            val loading = compose.onAllNodesWithTag("reader_loading").fetchSemanticsNodes().isNotEmpty()
            !loading && position.get()?.chapter == chapter
        }
        assertEquals(chapter, position.get()!!.chapter)
    }

    private fun clickChapterNavigation(description: String) {
        val button = compose.onNodeWithContentDescription(description)
        compose.waitUntil(15_000) { !button.fetchSemanticsNode().config.contains(SemanticsProperties.Disabled) }
        button.performClick()
    }

    private fun awaitText(marker: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("reader_loading").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText(marker, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("reader_body").assertIsDisplayed()
        compose.onNodeWithText(marker, substring = true).assertIsDisplayed()
        compose.onNodeWithText("正文排版失败", substring = true).assertDoesNotExist()
    }

    private class SilentSpeech : ReaderSpeechController {
        override val playing = MutableStateFlow(false)
        override val error = MutableStateFlow<String?>(null)
        var observedSpeed = 1f
        var released = false
        override fun start(source: List<ReaderSpeechSegment>, speed: Float, onLocation: (ReaderSpeechSegment, Int) -> Unit) {
            observedSpeed = speed
            playing.value = source.isNotEmpty()
            source.firstOrNull()?.let { onLocation(it, it.offset) }
        }
        override fun setSpeed(speed: Float) { observedSpeed = speed }
        override fun clearError() { error.value = null }
        override fun pause(): Boolean { playing.value = false; return true }
        override fun stop() { playing.value = false }
        override fun release() { released = true; stop() }
    }
}
