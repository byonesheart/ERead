package com.eyue.reader.ui

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eyue.reader.data.Book
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.Chapter
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.PageSource
import com.eyue.reader.data.ReaderSettings
import com.eyue.reader.data.ReadingMode
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ReaderSeekReturnTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: File
    private lateinit var context: Context
    private var settings by mutableStateOf(ReaderSettings())
    private data class Position(val chapter: Int, val page: Int, val offset: Int, val progress: Float, val imagePath: String?)
    private val position = AtomicReference<Position?>()

    @Before fun setUp() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        root = File(base.cacheDir, "seek-return-test-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        }
        settings = ReaderSettings(showTime = false, showBattery = false)
        position.set(null)
    }

    @After fun tearDown() { root.deleteRecursively() }

    @Test fun scrollSeekReturnPreservesOriginalPassageAcrossFontChanges() {
        val marker = "ORIGINAL_PASSAGE_ANCHOR"
        val line = "An ordinary paragraph keeps the exact reading position.\n"
        val prefix = line.repeat(35)
        val first = "$prefix$marker\n" + line.repeat(140)
        val second = "第二章正文\n" + line.repeat(170)
        val third = "第三章正文\n" + line.repeat(170)
        val book = Book(title = "文字跳转返回", format = BookFormat.MOBI, mode = LibraryMode.NOVEL,
            chapters = listOf(textChapter("第一章：出发", first), textChapter("第二章：途中", second), textChapter("第三章：终点", third)),
            characterOffset = prefix.length, progress = .1f, contentVersion = 4)
        showReader(book)
        awaitPosition(0)
        compose.onNodeWithText(marker, substring = true).assertIsDisplayed()
        val original = requireNotNull(position.get())
        assertEquals(prefix.length, original.offset)
        val returnLabel = "返回 第一章：出发 · ${(original.progress * 100).toInt()}%"
        compose.onNodeWithTag("reader_jump_return").assertDoesNotExist()

        seek(.9f)
        awaitPosition(2)
        compose.onNodeWithTag("reader_jump_return").assertIsDisplayed().assert(hasText(returnLabel))
        compose.onNodeWithContentDescription("返回较前位置").assertExists()

        compose.runOnIdle { settings = settings.copy(fontSize = 27f, lineHeight = 1.65f) }
        awaitTypography(27f)
        compose.onNodeWithTag("reader_jump_return").assert(hasText(returnLabel))
        compose.onNodeWithTag("reader_jump_return").performClick()
        awaitPosition(original.chapter, offset = original.offset)
        compose.onNodeWithText(marker, substring = true).assertIsDisplayed()
        assertEquals(original.imagePath, requireNotNull(position.get()).imagePath)
        compose.onNodeWithTag("reader_jump_return").assertDoesNotExist()
    }

    @Test fun comicSeekReturnRestoresOriginalImagePageAndBothArrowDirections() {
        settings = settings.copy(readingMode = ReadingMode.PAGED)
        val pages = (0 until 4).map { index ->
            val image = File(root, "comic-$index.png")
            val bitmap = Bitmap.createBitmap(48, 64, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(listOf(0xff779966.toInt(), 0xffaa7766.toInt(), 0xff6688aa.toInt(), 0xffaa9966.toInt())[index])
                image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
            PageSource(image.absolutePath)
        }
        val book = Book(title = "图片跳转返回", format = BookFormat.IMAGES, mode = LibraryMode.COMIC,
            chapters = listOf(Chapter(title = "第一卷", pages = pages.take(2)), Chapter(title = "第二卷", pages = pages.drop(2))))
        showReader(book)
        awaitPosition(0, page = 0)
        awaitImage(0, 0)
        assertEquals(.25f, requireNotNull(position.get()).progress, .0001f)

        seek(.95f)
        awaitPosition(1, page = 1)
        awaitImage(1, 1)
        compose.onNodeWithTag("reader_jump_return").assertIsDisplayed().assert(hasText("返回 第一卷 · 25%"))
        compose.onNodeWithContentDescription("返回较前位置").assertExists()
        compose.onNodeWithTag("reader_jump_return").performClick()
        awaitPosition(0, page = 0)
        awaitImage(0, 0)
        compose.onNodeWithTag("reader_jump_return").assertDoesNotExist()

        repeat(2) { step ->
            compose.onNodeWithTag("reader_body").performTouchInput { swipeLeft() }
            if (step == 0) awaitPosition(0, page = 1) else awaitPosition(1, page = 0)
        }
        awaitImage(1, 0)
        seek(.05f)
        awaitPosition(0, page = 0)
        awaitImage(0, 0)
        compose.onNodeWithTag("reader_jump_return").assert(hasText("返回 第二卷 · 75%"))
        compose.onNodeWithContentDescription("返回较后位置").assertExists()
        compose.onNodeWithTag("reader_jump_return").performClick()
        awaitPosition(1, page = 0)
        awaitImage(1, 0)
        compose.onNodeWithTag("reader_jump_return").assertDoesNotExist()

        seek(.05f)
        awaitPosition(0, page = 0)
        awaitImage(0, 0)
        compose.onNodeWithTag("reader_jump_return").assertIsDisplayed()
        compose.onNodeWithTag("reader_body").performTouchInput { click() }
        compose.onNodeWithTag("reader_progress").assertDoesNotExist()
        compose.onNodeWithTag("reader_jump_return").assertDoesNotExist()
        compose.onNodeWithTag("reader_body").performTouchInput { click() }
        compose.onNodeWithTag("reader_progress").assertIsDisplayed()
        compose.onNodeWithTag("reader_jump_return").assertDoesNotExist()
    }

    private fun textChapter(title: String, text: String) = Chapter(title = title,
        textPath = File(root, "${UUID.randomUUID()}.txt").apply { writeText(text) }.absolutePath)

    private fun showReader(book: Book) {
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme {
                    ReaderScreen(book, settings, emptyList(), {}, { chapter, page, offset, progress, imagePath ->
                        position.set(Position(chapter, page, offset, progress, imagePath))
                    }, { settings = it }, {}, {}, {}, emptyFlow())
                }
            }
        }
    }

    private fun seek(fraction: Float) {
        if (compose.onAllNodesWithTag("reader_progress").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("reader_body").performTouchInput { click() }
        }
        compose.onNodeWithTag("reader_progress").performTouchInput { click(Offset(width * fraction, centerY)) }
    }

    private fun awaitPosition(chapter: Int, page: Int? = null, offset: Int? = null) {
        compose.waitUntil(10_000) {
            position.get()?.let { it.chapter == chapter && (page == null || it.page == page) && (offset == null || it.offset == offset) } == true &&
                compose.onAllNodesWithTag("reader_loading").fetchSemanticsNodes().isEmpty()
        }
        compose.waitForIdle()
    }

    private fun awaitImage(chapter: Int, page: Int) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("漫画或 PDF 页面").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("reader_image_${chapter}_$page").assertIsDisplayed()
    }

    private fun awaitTypography(fontSize: Float) {
        compose.waitUntil(10_000) {
            val fields = compose.onAllNodesWithContentDescription("阅读正文")
            if (fields.fetchSemanticsNodes().isEmpty()) false else {
                val layouts = mutableListOf<TextLayoutResult>()
                fields[0].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                layouts.any { it.layoutInput.style.fontSize.value == fontSize }
            }
        }
        compose.waitForIdle()
    }
}
