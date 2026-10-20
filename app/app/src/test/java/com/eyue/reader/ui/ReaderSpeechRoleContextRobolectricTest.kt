package com.eyue.reader.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.time.Duration
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderSpeechRoleContextRobolectricTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var root: File
    private val voice = CapturingSpeech()
    private var audio: ServiceController<ReaderAudioService>? = null
    private var audioStart = 0

    @Before fun setup() {
        root = File(RuntimeEnvironment.getApplication().cacheDir, "reader-role-context-${UUID.randomUUID()}").apply { mkdirs() }
    }
    @After fun cleanup() {
        compose.runOnUiThread { ReaderAudioService.owner?.stop(); audio?.destroy(); audio = null }
        root.deleteRecursively()
    }

    @Test fun pagedAiStartedMidParagraphKeepsFullChapterContextWithoutReplayingEarlierText() {
        verifyContext(ReadingMode.PAGED)
    }

    @Test fun scrollingAiStartedMidParagraphKeepsTheRoleMentionBeforeItsReadingAnchor() {
        verifyContext(ReadingMode.SCROLL)
    }

    @Test fun oldSystemSegmentsRemainCompatibleWithoutRoleContext() {
        val segment = ReaderSpeechSegment(2, 3, 40, "正文")
        assertNull(segment.roleContext)
        assertEquals("正文", segment.text)
        assertEquals(40, segment.offset)
    }

    @Test fun lateAiStartUsesCurrentChapterAndDefersTheFollowingChapterWithUnavailableEarlierText() {
        val current = "张三说：“你好。”\n" + "当前章节正文。".repeat(180)
        val next = "NEXT_CHAPTER_BODY"
        val book = Book(title = "末尾章节朗读", format = BookFormat.TXT, mode = LibraryMode.NOVEL,
            chapterIndex = 98, characterOffset = current.length - 180,
            chapters = (0 until 100).map { index ->
                val text = when (index) { 98 -> current; 99 -> next; else -> "相邻章节。".repeat(80) }
                val path = File(root, "$index.txt")
                if (index >= 97) path.writeText(text)
                Chapter(title = "第 $index 章", characterCount = text.length, textPath = path.absolutePath)
            })
        var observedChapter = -1
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f), LocalReaderSpeechFactory provides { voice }) {
                MaterialTheme { Box(Modifier.width(360.dp).height(640.dp)) {
                    ReaderScreen(book, ReaderSettings(readingMode = ReadingMode.SCROLL, showTime = false, showBattery = false),
                        emptyList(), {}, { chapter, _, _, _, _ -> observedChapter = chapter }, {}, {}, {}, {}, emptyFlow())
                } }
            }
        }
        await { observedChapter == 98 && compose.onAllNodesWithTag("reader_loading").fetchSemanticsNodes().isEmpty() &&
            compose.onAllNodesWithContentDescription("阅读正文").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("reader_body").performTouchInput { click(Offset(width / 2f, height * .18f)) }
        compose.onNodeWithContentDescription("朗读").performClick()
        await { voice.source.isNotEmpty() }
        val source = requireNotNull(voice.chapterSource)
        assertEquals(98, source.firstChapter)
        assertEquals(100, source.chapterCount)
        assertEquals(listOf(98), voice.source.map { it.chapter }.distinct())
        assertEquals(current, voice.source.single().roleContext)
        assertTrue(voice.source.single().offset > 0)
        val following = runBlocking { source.loadChapter(99) }.single()
        assertEquals(99, following.chapter)
        assertEquals(next, following.text)
    }

    private fun verifyContext(mode: ReadingMode) {
        val first = "张三走来。" + "甲".repeat(500) + "开始位置。" + "乙".repeat(600)
        val second = "李四轻声说：明日再见。"
        val book = Book(title = "段中角色上下文", format = BookFormat.TXT, mode = LibraryMode.NOVEL,
            characterOffset = first.indexOf("开始位置"), chapters = listOf(first, second).mapIndexed { index, text ->
                Chapter(title = "第 ${index + 1} 章", characterCount = text.length,
                    textPath = File(root, "$index.txt").apply { writeText(text) }.absolutePath)
            })
        var offset = -1
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f), LocalReaderSpeechFactory provides { voice }) {
                MaterialTheme { Box(Modifier.width(360.dp).height(640.dp)) {
                    ReaderScreen(book, ReaderSettings(readingMode = mode, fontSize = 24f, lineHeight = 1.4f,
                        showTime = false, showBattery = false), emptyList(), {}, { _, _, at, _, _ -> offset = at }, {}, {}, {}, {}, emptyFlow())
                } }
            }
        }
        await { offset > 100 && compose.onAllNodesWithTag("reader_loading").fetchSemanticsNodes().isEmpty() &&
            compose.onAllNodesWithContentDescription("阅读正文").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("reader_body").performTouchInput { click(Offset(width / 2f, height * .18f)) }
        compose.onNodeWithContentDescription("朗读").performClick()
        await { voice.source.isNotEmpty() }
        val segment = voice.source.first()
        assertEquals(0, segment.chapter)
        assertTrue("The test must start after the role mention", segment.offset > 100)
        assertFalse(segment.text.contains("张三"))
        assertEquals(first.substring(segment.offset), segment.text)
        assertEquals(first, segment.roleContext)
        assertEquals(listOf(0), voice.source.map { it.chapter }.distinct())
        val nextChapter = runBlocking { requireNotNull(voice.chapterSource).loadChapter(1) }.single()
        assertEquals(second, nextChapter.roleContext)
        assertEquals(second, nextChapter.text)
        val hint = RoleVoicePromptResolver(listOf(BookVoiceRole("张三", "张三", "沉稳青年男声")))
            .prompt(requireNotNull(segment.roleContext), segment.offset, segment.offset + minOf(240, segment.text.length))
        assertTrue("The full paragraph must still match its earlier role mention", hint.contains("沉稳青年男声"))
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            compose.runOnUiThread {
                if (ReaderAudioService.owner?.pending != null) {
                    if (audio == null) audio = Robolectric.buildService(ReaderAudioService::class.java).create()
                    audio!!.get().onStartCommand(null, 0, ++audioStart)
                }
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Timed out waiting for reader role context")
    }

    private class CapturingSpeech : ReaderSpeechController {
        override val playing = MutableStateFlow(false)
        override val error = MutableStateFlow<String?>(null)
        override val usesChapterSegments = true
        var source = emptyList<ReaderSpeechSegment>()
        var chapterSource: ReaderSpeechSource? = null
        override fun start(source: ReaderSpeechSource, speed: Float, onLocation: (ReaderSpeechSegment, Int) -> Unit) {
            start(source.initial, speed, onLocation)
            chapterSource = source
        }
        override fun start(source: List<ReaderSpeechSegment>, speed: Float, onLocation: (ReaderSpeechSegment, Int) -> Unit) {
            chapterSource = null
            this.source = source
            playing.value = true
        }
        override fun setSpeed(speed: Float) = Unit
        override fun clearError() = Unit
        override fun stop() { playing.value = false }
        override fun release() = stop()
    }
}
