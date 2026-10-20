package com.eyue.reader

import android.view.KeyEvent
import android.os.Handler
import android.os.Looper
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eyue.reader.data.Book
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.Chapter
import com.eyue.reader.data.InlineImage
import com.eyue.reader.data.Annotation
import com.eyue.reader.data.SourceTextStyle
import com.eyue.reader.data.SourceTextAlignment
import com.eyue.reader.data.PageSource
import com.eyue.reader.data.ComicFixtureDocumentsProvider
import com.eyue.reader.data.AnnotationKind
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.LibraryRepository
import com.eyue.reader.data.ReaderSettings
import com.eyue.reader.data.ReadingMode
import com.eyue.reader.ui.ReaderScreen
import com.eyue.reader.ui.readerPageText
import com.eyue.reader.ui.readerParagraphText
import com.eyue.reader.ui.readerPaginate
import com.eyue.reader.ui.ReaderSourceStyles
import com.eyue.reader.ui.ReaderPaginationMeasurements
import com.eyue.reader.ui.ReaderImagePage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.rules.RuleChain
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ReaderInteractionTest {
    val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(ConsentGrantRule()).around(compose)
    private lateinit var vm: ReaderViewModel
    private var originalSettings: ReaderSettings? = null
    private var originalComicSettings: ReaderSettings? = null
    private var originalMode: LibraryMode? = null
    private var originalBook: Book? = null
    private val addedAnnotationIds = mutableListOf<String>()

    private fun awaitHome() {
        compose.runOnIdle {
            vm = ViewModelProvider(compose.activity)[ReaderViewModel::class.java]
        }
        compose.waitUntil(30_000) { !vm.loading && vm.state.books.isNotEmpty() }
        compose.runOnIdle {
            originalSettings = vm.state.settings
            originalComicSettings = vm.state.comicSettings
            originalMode = vm.mode
        }
    }

    private fun currentBook(id: String): Book = vm.state.books.first { it.id == id }

    private fun awaitReader(id: String) {
        compose.waitForIdle()
        compose.waitUntil(30_000) {
            vm.activeBookId == id && currentBook(id).progress > 0f &&
                compose.onAllNodesWithTag("reader_body").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("reader_loading").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun revealTools() {
        if (compose.onAllNodesWithContentDescription("目录").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("reader_body").performTouchInput { click() }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("目录").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun closeReader() {
        revealTools()
        compose.onNodeWithContentDescription("返回书架").performClick()
        compose.waitUntil(10_000) { vm.activeBookId == null }
        compose.waitForIdle()
    }

    @After fun restoreSettings() {
        originalSettings?.let { settings ->
            compose.runOnIdle {
                vm.close()
                originalBook?.let { vm.open(it); vm.close() }
                addedAnnotationIds.forEach(vm::deleteAnnotation)
                vm.mode = LibraryMode.COMIC
                originalComicSettings?.let { vm.settings(it.copy(nightMode = settings.nightMode)) }
                vm.mode = LibraryMode.NOVEL
                vm.settings(settings)
                originalMode?.let { vm.mode = it }
                vm.flush()
            }
            compose.waitUntil(10_000) {
                LibraryRepository(compose.activity).load().let { state ->
                    state.settings == settings && state.comicSettings == originalComicSettings?.copy(nightMode = settings.nightMode)
                }
            }
        }
    }

    @Test fun novelPagingFontReflowAndSearchRetainExactPassage() {
        awaitHome()
        lateinit var novel: Book
        compose.runOnIdle {
            novel = vm.state.books.first { it.mode == LibraryMode.NOVEL && it.isDemo }
            originalBook = novel
            vm.mode = LibraryMode.NOVEL
            vm.settings(vm.readerSettings.copy(readingMode = ReadingMode.PAGED, fontSize = 30f,
                lineHeight = 1.85f, fontPath = null, fontName = "系统黑体", volumePaging = false))
            vm.open(novel.copy(chapterIndex = 0, pageIndex = 0, characterOffset = 0, progress = 0f))
        }
        awaitReader(novel.id)
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitUntil(10_000) {
            currentBook(novel.id).pageIndex == 1 && currentBook(novel.id).characterOffset > 0
        }
        val beforeReflow = currentBook(novel.id)
        compose.runOnIdle { vm.settings(vm.readerSettings.copy(fontSize = 23f)) }
        awaitReader(novel.id)
        assertEquals(beforeReflow.chapterIndex, currentBook(novel.id).chapterIndex)
        assertEquals(beforeReflow.characterOffset, currentBook(novel.id).characterOffset)

        val query = "车票上的日期已经模糊"
        val targetChapter = 1
        val targetText = File(novel.chapters[targetChapter].textPath!!).readText()
        val targetOffset = targetText.indexOf(query)
        assertTrue(targetOffset > 0)
        revealTools()
        compose.onNodeWithContentDescription("全文搜索与替换").performClick()
        compose.onNodeWithTag("reader_search_query").performTextInput(query)
        val result = hasText(query, substring = true) and !hasSetTextAction()
        compose.waitUntil(10_000) { compose.onAllNodes(result).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(result).performClick()
        compose.waitUntil(10_000) {
            currentBook(novel.id).chapterIndex == targetChapter &&
                currentBook(novel.id).characterOffset == targetOffset
        }
        awaitReader(novel.id)
        compose.onNodeWithContentDescription("阅读正文").assert(hasText(query, substring = true))
        closeReader()
        compose.waitUntil(10_000) {
            LibraryRepository(compose.activity).load().books.first { it.id == novel.id }.let {
                it.chapterIndex == targetChapter && it.characterOffset == targetOffset
            }
        }
        val persisted = LibraryRepository(compose.activity).load().books.first { it.id == novel.id }
        compose.runOnIdle { vm.open(persisted) }
        awaitReader(novel.id)
        assertEquals(targetOffset, currentBook(novel.id).characterOffset)
        compose.onNodeWithContentDescription("阅读正文").assert(hasText(query, substring = true))
    }

    @Test fun comicSwipeVolumePagingAndBookmarkSurviveReopen() {
        awaitHome()
        lateinit var comic: Book
        var previousAnnotations = 0
        compose.runOnIdle {
            comic = vm.state.books.first { it.mode == LibraryMode.COMIC && it.isDemo }
            originalBook = comic
            previousAnnotations = vm.state.annotations.count { it.bookId == comic.id }
            vm.mode = LibraryMode.COMIC
            vm.settings(vm.readerSettings.copy(readingMode = ReadingMode.PAGED, volumePaging = true))
            vm.open(comic.copy(chapterIndex = 0, pageIndex = 0, characterOffset = 0, progress = 0f))
        }
        awaitReader(comic.id)
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("漫画或 PDF 页面").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitUntil(10_000) { currentBook(comic.id).pageIndex == 1 }
        awaitReader(comic.id)
        compose.runOnIdle {
            assertTrue(compose.activity.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)))
            assertTrue(compose.activity.onKeyUp(KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_DOWN)))
        }
        compose.waitUntil(10_000) { currentBook(comic.id).pageIndex == 2 }
        assertEquals(1f, currentBook(comic.id).progress, 0.0001f)
        revealTools()
        compose.onNodeWithContentDescription("快速添加书签").performClick()
        compose.waitUntil(10_000) { vm.state.annotations.count { it.bookId == comic.id } == previousAnnotations + 1 }
        addedAnnotationIds += vm.state.annotations.last { it.bookId == comic.id }.id
        assertEquals(2, vm.state.annotations.last { it.bookId == comic.id }.pageIndex)
        compose.onNodeWithTag("reader_bookmark_marker").assertExists()
        closeReader()
        compose.waitUntil(10_000) {
            LibraryRepository(compose.activity).load().let { state ->
                state.books.first { it.id == comic.id }.pageIndex == 2 &&
                    state.annotations.count { it.bookId == comic.id } == previousAnnotations + 1
            }
        }
        val persisted = LibraryRepository(compose.activity).load().books.first { it.id == comic.id }
        compose.runOnIdle { vm.open(persisted) }
        awaitReader(comic.id)
        assertEquals(2, currentBook(comic.id).pageIndex)
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("漫画或 PDF 页面").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun tapChromeHalfSheetGlobalSeekAndRibbonDoNotRepageText() {
        awaitHome()
        lateinit var novel: Book
        var initialBrightness = -1f
        compose.runOnIdle {
            novel = vm.state.books.first { it.mode == LibraryMode.NOVEL && it.isDemo }
            originalBook = novel
            initialBrightness = compose.activity.window.attributes.screenBrightness
            vm.mode = LibraryMode.NOVEL
            vm.settings(vm.readerSettings.copy(readingMode = ReadingMode.PAGED, fontSize = 12f,
                nightMode = false, fontPath = null, fontName = "跟随系统", brightness = -1f))
            vm.open(novel.copy(chapterIndex = 0, pageIndex = 0, characterOffset = 0, progress = 0f))
        }
        awaitReader(novel.id)
        compose.onNodeWithContentDescription("阅读设置").assertDoesNotExist()
        compose.onNodeWithTag("reader_settings_sheet").assertDoesNotExist()
        val bodyBefore = compose.onNodeWithTag("reader_body").fetchSemanticsNode().boundsInRoot
        revealTools()
        compose.onNodeWithTag("reader_settings_sheet").assertDoesNotExist()
        assertEquals(bodyBefore, compose.onNodeWithTag("reader_body").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("reader_body").performTouchInput { click() }
        compose.onNodeWithContentDescription("目录").assertDoesNotExist()
        compose.runOnIdle { vm.settings(vm.readerSettings.copy(fontSize = 30f)) }
        awaitReader(novel.id)
        assertEquals(0, currentBook(novel.id).pageIndex)
        compose.onNodeWithTag("reader_body").performTouchInput { swipeLeft() }
        compose.waitUntil(10_000) { currentBook(novel.id).chapterIndex == 0 && currentBook(novel.id).pageIndex == 1 }
        revealTools()
        compose.onNodeWithContentDescription("快速添加书签").performClick()
        compose.waitUntil(10_000) { vm.state.annotations.any { it.bookId == novel.id && it.kind == AnnotationKind.BOOKMARK && it.characterOffset == currentBook(novel.id).characterOffset } }
        val bookmark = vm.state.annotations.last { it.bookId == novel.id && it.kind == AnnotationKind.BOOKMARK }
        addedAnnotationIds += bookmark.id
        compose.onNodeWithTag("reader_body").performTouchInput { click() }
        compose.onNodeWithTag("reader_bookmark_marker").assertExists()
        compose.runOnIdle { vm.settings(vm.readerSettings.copy(fontSize = 12f)) }
        awaitReader(novel.id)
        assertEquals(bookmark.characterOffset, currentBook(novel.id).characterOffset)
        assertTrue(currentBook(novel.id).pageIndex != bookmark.pageIndex)
        compose.onNodeWithTag("reader_bookmark_marker").assertExists()
        revealTools()
        val chapterBeforeDrag = currentBook(novel.id).chapterIndex
        compose.onNodeWithTag("reader_progress").performTouchInput {
            down(Offset(width * 0.1f, centerY))
            advanceEventTime(200)
            moveTo(Offset(width * 0.9f, centerY))
        }
        compose.runOnIdle { assertEquals(chapterBeforeDrag, currentBook(novel.id).chapterIndex) }
        compose.onNodeWithTag("reader_progress").performTouchInput { up() }
        compose.waitUntil(10_000) { currentBook(novel.id).chapterIndex > chapterBeforeDrag }
        awaitReader(novel.id)
        revealTools()
        val toolbarBottom = compose.onNodeWithContentDescription("界面设置").fetchSemanticsNode().boundsInRoot.bottom
        compose.onNodeWithContentDescription("阅读设置").assertIsDisplayed()
        compose.onNodeWithContentDescription("界面设置").performClick()
        compose.onNodeWithTag("reader_settings_sheet").assertIsDisplayed()
        val sheetHeight = compose.onNodeWithTag("reader_settings_sheet").fetchSemanticsNode().boundsInRoot.height
        assertTrue(sheetHeight <= compose.activity.resources.displayMetrics.heightPixels / 2f + 2f)
        val sheetBounds = compose.onNodeWithTag("reader_settings_sheet").fetchSemanticsNode().boundsInRoot
        assertTrue(sheetBounds.bottom >= toolbarBottom)
        assertEquals(bodyBefore, compose.onNodeWithTag("reader_body").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("reader_interface_title").assertIsDisplayed()
        compose.onNodeWithTag("reader_save_theme").assertDoesNotExist()
        compose.onNodeWithTag("reader_new_theme").assertIsDisplayed()
        compose.onNodeWithTag("reader_settings_tab_interface").assertDoesNotExist()
        compose.onNodeWithTag("reader_settings_tab_preferences").assertDoesNotExist()
        compose.runOnIdle { vm.settings(vm.readerSettings.copy(brightness = 0.45f)) }
        compose.waitForIdle()
        assertEquals(0.45f, compose.activity.window.attributes.screenBrightness, 0.01f)
        compose.onNodeWithTag("reader_body").performTouchInput { click(Offset(centerX, height * 0.2f)) }
        compose.onNodeWithTag("reader_settings_sheet").assertDoesNotExist()
        revealTools()
        compose.onNodeWithContentDescription("阅读设置").performClick()
        compose.onNodeWithText("音量键翻页").assertIsDisplayed()
        compose.onNodeWithTag("reader_save_theme").assertDoesNotExist()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("reader_settings_sheet").fetchSemanticsNodes().isEmpty() }
        closeReader()
        assertEquals(initialBrightness, compose.activity.window.attributes.screenBrightness, 0.01f)
    }

    @Test fun longPressTextSelectsExactRangeAndPersistsColoredHighlight() {
        awaitHome()
        lateinit var novel: Book
        val initialIds = mutableSetOf<String>()
        compose.runOnIdle {
            novel = vm.state.books.first { it.mode == LibraryMode.NOVEL && it.isDemo }
            originalBook = novel
            initialIds += vm.state.annotations.map { it.id }
            vm.mode = LibraryMode.NOVEL
            vm.settings(vm.readerSettings.copy(readingMode = ReadingMode.PAGED, fontSize = 23f,
                fontPath = null, fontName = "跟随系统", nightMode = false))
            vm.open(novel.copy(chapterIndex = 0, pageIndex = 0, characterOffset = 0, progress = 0f))
        }
        awaitReader(novel.id)
        compose.onNodeWithContentDescription("阅读正文").performTouchInput { longClick(Offset(width * 0.4f, height * 0.2f)) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("标记").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("标记").performClick()
        compose.onNodeWithContentDescription("蓝色 标记颜色").performClick()
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10_000) { vm.state.annotations.any { it.bookId == novel.id && it.kind == AnnotationKind.HIGHLIGHT && it.id !in initialIds } }
        val highlight = vm.state.annotations.last { it.bookId == novel.id && it.kind == AnnotationKind.HIGHLIGHT && it.id !in initialIds }
        addedAnnotationIds += highlight.id
        assertTrue(highlight.endOffset > highlight.characterOffset)
        assertEquals(File(novel.chapters[highlight.chapterIndex].textPath!!).readText().substring(highlight.characterOffset, highlight.endOffset), highlight.quote)
        assertEquals(0xFFA9C8F0L, highlight.color)
        compose.runOnIdle { vm.settings(vm.readerSettings.copy(fontSize = 26f)) }
        awaitReader(novel.id)
        val text = compose.onNodeWithContentDescription("阅读正文").fetchSemanticsNode().config[SemanticsProperties.EditableText]
        assertTrue(text.spanStyles.any { it.item.background != Color.Unspecified })
        compose.onNodeWithTag("reader_bookmark_marker").assertDoesNotExist()
        closeReader()
        compose.waitUntil(10_000) { LibraryRepository(compose.activity).load().annotations.any { it.id == highlight.id && it.endOffset == highlight.endOffset && it.color == highlight.color } }
    }

    @Test fun imageLongPressMarksThePressedPageWithoutCreatingBookmark() {
        awaitHome()
        lateinit var comic: Book
        val initialIds = mutableSetOf<String>()
        compose.runOnIdle {
            comic = vm.state.books.first { it.mode == LibraryMode.COMIC && it.isDemo }
            originalBook = comic
            initialIds += vm.state.annotations.map { it.id }
            vm.mode = LibraryMode.COMIC
            vm.settings(vm.readerSettings.copy(readingMode = ReadingMode.PAGED, nightMode = false))
            vm.open(comic.copy(chapterIndex = 0, pageIndex = 1, characterOffset = 0, progress = 0f))
        }
        awaitReader(comic.id)
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("漫画或 PDF 页面").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("reader_image_0_1").performTouchInput { longClick() }
        compose.onNodeWithText("第 2 页").assertIsDisplayed()
        compose.onNodeWithContentDescription("粉色 标记颜色").performClick()
        compose.onNodeWithTag("reader_note_input").performTextInput("这一页的云很有趣")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10_000) { vm.state.annotations.any { it.bookId == comic.id && it.kind == AnnotationKind.NOTE && it.id !in initialIds } }
        val note = vm.state.annotations.last { it.bookId == comic.id && it.kind == AnnotationKind.NOTE && it.id !in initialIds }
        addedAnnotationIds += note.id
        assertEquals(1, note.pageIndex)
        assertEquals(0xFFFFB5BAL, note.color)
        compose.onNodeWithTag("reader_image_mark").assertExists()
        compose.onNodeWithTag("reader_bookmark_marker").assertDoesNotExist()
        closeReader()
        compose.waitUntil(10_000) { LibraryRepository(compose.activity).load().annotations.any { it.id == note.id && it.pageIndex == 1 && it.kind == AnnotationKind.NOTE } }
    }
}

@RunWith(AndroidJUnit4::class)
class ReaderLargeBookTest {
    @get:Rule val compose = createComposeRule()

    @Test fun completeShortStyledChunksReuseTheirExactNativeLayout() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val text = "标题\n引用正文。"
        val density = Density(1f, 1f)
        val style = TextStyle(fontSize = 19.sp, lineHeight = 30.sp)
        val source = ReaderSourceStyles(text, listOf(SourceTextStyle(0, 2, fontScale = 1.4f, bold = true,
            alignment = SourceTextAlignment.CENTER, paragraph = true)))
        val measurements = ReaderPaginationMeasurements()
        val pages = readerPaginate(context, listOf(text), style, density, LayoutDirection.Ltr, 320, 480,
            sourceStyles = listOf(source), measurements = measurements).single()
        assertEquals(1, measurements.chunks)
        assertEquals("同内容、样式和约束不应重复做整页native排版", 0, measurements.pages)
        assertEquals(0, pages.single().start)
        assertEquals(text.length, pages.single().end)
        withContext(Dispatchers.Default) {
            val verifier = TextMeasurer(createFontFamilyResolver(context), density, LayoutDirection.Ltr, cacheSize = 0)
            val layout = verifier.measure(readerParagraphText(source.apply(text, 0, text.length, style), style).text, style = style, constraints = Constraints(maxWidth = 320))
            assertTrue(layout.size.height <= 480)
        }
        val newlineMeasurements = ReaderPaginationMeasurements()
        val withNewline = "$text\n"
        readerPaginate(context, listOf(withNewline), style, density, LayoutDirection.Ltr, 320, 480,
            measurements = newlineMeasurements)
        assertTrue("页尾换行会改变测量文本，必须保留fresh-page验证", newlineMeasurements.pages > 0)
    }

    @Test fun styledShortParagraphChunksRestartAfterTheLastLaidOutParagraph() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val density = Density(3f, 1f)
        val style = TextStyle(fontSize = 19.sp, lineHeight = 35.15.sp)
        val ranges = mutableListOf<SourceTextStyle>()
        val text = buildString {
            append("标题😀\n")
            ranges += SourceTextStyle(0, length - 1, fontScale = 4f, bold = true,
                alignment = SourceTextAlignment.CENTER, paragraph = true)
            repeat(600) { paragraph ->
                val start = length
                append("第${paragraph}段😀短文。\n")
                ranges += SourceTextStyle(start, length - 1, italic = paragraph % 3 == 0,
                    alignment = if (paragraph % 2 == 0) SourceTextAlignment.START else SourceTextAlignment.END,
                    firstLineIndentEm = 0.75f, restLineIndentEm = 0.25f, paragraph = true)
            }
        }
        val source = withContext(Dispatchers.Default) { ReaderSourceStyles(text, ranges) }
        withContext(Dispatchers.Default) {
            val verifier = TextMeasurer(createFontFamilyResolver(context), density, LayoutDirection.Ltr, cacheSize = 0)
            // The first production chunk is 256 characters and its scaled line budget is 37.
            val truncated = verifier.measure(readerParagraphText(source.apply(text, 0, 256, style, trimLastNewline = false), style).text, style = style,
                constraints = Constraints(maxWidth = 320, maxHeight = 16_384), maxLines = 37)
            assertTrue("回归必须覆盖仍有未布局文本的多段落chunk", truncated.didOverflowHeight)
            assertTrue(truncated.getLineEnd(truncated.lineCount - 1, visibleEnd = false) < truncated.layoutInput.text.length)
        }
        val pages = readerPaginate(context, listOf(text), style, density, LayoutDirection.Ltr, 320, 480,
            sourceStyles = listOf(source)).single()
        withContext(Dispatchers.Default) {
            val verifier = TextMeasurer(createFontFamilyResolver(context), density, LayoutDirection.Ltr, cacheSize = 0)
            var cursor = 0
            for (page in pages) {
                assertEquals(cursor, page.start)
                assertTrue(page.end > page.start)
                val layout = verifier.measure(readerParagraphText(source.apply(text, page.start, page.end, style), style).text, style = style,
                    constraints = Constraints(maxWidth = 320))
                assertTrue("重开chunk后每页仍必须适合视口", layout.size.height <= 481)
                if (page.end < text.length) assertFalse(Character.isHighSurrogate(text[page.end - 1]) && Character.isLowSurrogate(text[page.end]))
                cursor = page.end
            }
            assertEquals(text.length, cursor)
        }
    }

    @Test fun linkedContentImageAndPdfRenderWithoutFilePathCopies() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.contentResolver.call(Uri.parse("content://${ComicFixtureDocumentsProvider.AUTHORITY}"), "grant_fixture", context.packageName, null)
        val image = DocumentsContract.buildDocumentUri(ComicFixtureDocumentsProvider.AUTHORITY, "root1").toString()
        val pdf = DocumentsContract.buildDocumentUri(ComicFixtureDocumentsProvider.AUTHORITY, "pdf3").toString()
        var source by mutableStateOf(PageSource(image))
        compose.setContent {
            MaterialTheme { ReaderImagePage(source, 600, true, Color.Black, onTap = {}, onLongPress = {}, description = "链接源页面") }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("链接源页面").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("链接源页面").assertIsDisplayed()
        compose.runOnIdle { source = PageSource(pdf, pdfPage = 1) }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("链接源页面").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("链接源页面").assertIsDisplayed()
        assertEquals(pdf, source.path)
    }

    @Test fun tenMegabyteSingleChapterKeepsMainThreadAndExitResponsive() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("reader-large-", ".txt", context.cacheDir)
        val paragraph = "一段没有目录的长篇正文，mixed English text with words and spaces。\n"
        // More than 10 MiB of UTF-8 content, in one chapter as a MOBI without a TOC can produce.
        val text = paragraph.repeat(10 * 1024 * 1024 / paragraph.toByteArray(Charsets.UTF_8).size + 1)
        file.writeText(text, Charsets.UTF_8)
        var closed by mutableStateOf(false)
        val book = Book(title = "大单章响应回归", mode = LibraryMode.NOVEL, format = BookFormat.MOBI,
            chapters = listOf(Chapter(title = "正文", textPath = file.absolutePath)))
        try {
            compose.setContent {
                MaterialTheme {
                    if (closed) Text("已退出大书")
                    else ReaderScreen(book, ReaderSettings(readingMode = ReadingMode.PAGED), emptyList(),
                        onClose = { closed = true }, onProgress = { _, _, _, _, _ -> }, onSettings = {},
                        onAddAnnotation = {}, onDeleteAnnotation = {}, onImportFont = {}, volumeEvents = emptyFlow())
                }
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("reader_loading").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("正在排版", substring = true).fetchSemanticsNodes().isNotEmpty() }
            val ping = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post { ping.countDown() }
            assertTrue("大单章后台排版期间主线程应能及时处理事件", ping.await(2, TimeUnit.SECONDS))
            compose.onNodeWithText("退出阅读").performClick()
            compose.waitUntil(2_000) { compose.onAllNodesWithText("已退出大书").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("reader_loading").assertDoesNotExist()
        } finally {
            compose.runOnIdle { closed = true }
            file.delete()
        }
    }

    @Test fun boundedChunkPaginationPreservesAllCharactersAndFitsFreshPageLayouts() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val paragraph = "春风穿过山林，the long English sentence should wrap naturally. 😀继续前行。\n\n"
        val chapters = listOf(paragraph.repeat(400), "", paragraph.repeat(120))
        val density = Density(1f, 1f)
        val style = TextStyle(fontSize = 19.sp, lineHeight = 35.15.sp, letterSpacing = 0.7.sp)
        val reports = mutableListOf<Float>()
        val images = listOf(listOf(InlineImage(paragraph.length, "same.png"), InlineImage(paragraph.length * 2, "same.png")),
            listOf(InlineImage(0, "only.png")), emptyList())
        val styles = withContext(Dispatchers.Default) { listOf(
            ReaderSourceStyles(chapters[0], listOf(SourceTextStyle(0, paragraph.length - 1, fontScale = 1.7f,
                bold = true, alignment = SourceTextAlignment.CENTER, paragraph = true),
                SourceTextStyle(paragraph.length * 3, paragraph.length * 20, fontScale = 1.2f, italic = true,
                    alignment = SourceTextAlignment.JUSTIFY, firstLineIndentEm = 2f, restLineIndentEm = 1f, paragraph = true))),
            ReaderSourceStyles("", emptyList()), ReaderSourceStyles(chapters[2], emptyList())) }
        val pages = readerPaginate(context, chapters, style, density, LayoutDirection.Ltr, 320, 480, inlineImages = images, sourceStyles = styles) {
            assertFalse("分页不应在主线程运行", Looper.myLooper() == Looper.getMainLooper())
            reports += it
        }
        assertEquals(1f, reports.last(), 0f)
        assertTrue(reports.zipWithNext().all { (first, second) -> second >= first })
        assertEquals(listOf(0, 1), pages[0].filter { it.imageIndex >= 0 }.map { it.imageIndex })
        assertEquals(0, pages[1].single().imageIndex)
        withContext(Dispatchers.Default) {
            val verifier = TextMeasurer(createFontFamilyResolver(context), density, LayoutDirection.Ltr, cacheSize = 0)
            for (chapter in chapters.indices) {
                var offset = 0
                for (page in pages[chapter]) {
                    assertEquals(offset, page.start)
                    assertTrue(page.end >= page.start)
                    if (page.imageIndex >= 0) {
                        assertEquals(images[chapter][page.imageIndex].offset, page.start)
                        assertEquals(page.start, page.end)
                    }
                    val layout = verifier.measure(readerParagraphText(styles[chapter].apply(chapters[chapter], page.start, page.end, style), style).text, style = style,
                        constraints = Constraints(maxWidth = 320))
                    assertTrue("新页面实际排版应适合视口", layout.size.height <= 481)
                    if (page.end in 1 until chapters[chapter].length) {
                        assertFalse(Character.isHighSurrogate(chapters[chapter][page.end - 1]) && Character.isLowSurrogate(chapters[chapter][page.end]))
                    }
                    offset = page.end
                }
                assertEquals(chapters[chapter].length, offset)
            }
        }
    }

    @Test fun inlineNovelImagesKeepBookmarksAndNotesAcrossReflowAndResume() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "reader-inline-${System.nanoTime()}").apply { mkdirs() }
        val firstImage = File(directory, "first.png")
        val secondImage = File(directory, "second.png")
        for (file in listOf(firstImage, secondImage)) {
            val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.GREEN)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val text = "第一章\n图片之前是一段正文。\n图片之后的正文仍按小说阅读。\n"
        val offset = text.indexOf("图片之后")
        val textFile = File(directory, "chapter.txt").apply { writeText(text) }
        val lastFile = File(directory, "last.txt").apply { writeText("第二章\n下一章的文字。") }
        var book by mutableStateOf(Book(title = "混排小说", format = BookFormat.MOBI, mode = LibraryMode.NOVEL,
            chapters = listOf(Chapter(title = "第一章", textPath = textFile.path,
                inlineImages = listOf(InlineImage(offset, firstImage.path), InlineImage(offset, secondImage.path), InlineImage(text.length, secondImage.path))),
                Chapter(title = "第二章", textPath = lastFile.path))))
        var settings by mutableStateOf(ReaderSettings(readingMode = ReadingMode.PAGED, fontSize = 22f))
        var annotations by mutableStateOf(emptyList<Annotation>())
        var visible by mutableStateOf(true)
        fun awaitLoaded() = compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("reader_loading").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithTag("reader_body").fetchSemanticsNodes().isNotEmpty()
        }
        fun tools() {
            if (compose.onAllNodesWithContentDescription("目录").fetchSemanticsNodes().isEmpty())
                compose.onNodeWithTag("reader_body").performTouchInput { click() }
            compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("目录").fetchSemanticsNodes().isNotEmpty() }
        }
        try {
            compose.setContent {
                MaterialTheme {
                    if (visible) ReaderScreen(book, settings, annotations, onClose = { visible = false },
                        onProgress = { chapter, page, character, percent, image -> book = book.copy(chapterIndex = chapter,
                            pageIndex = page, characterOffset = character, progress = percent, positionImagePath = image) },
                        onSettings = { settings = it }, onAddAnnotation = { annotations = annotations + it },
                        onDeleteAnnotation = { id -> annotations = annotations.filterNot { it.id == id } }, onImportFont = {}, volumeEvents = emptyFlow())
                    else Text("已暂停混排小说")
                }
            }
            awaitLoaded()
            compose.onNodeWithContentDescription("阅读正文").assert(hasText("图片之前", substring = true))
            compose.onNodeWithTag("reader_body").performTouchInput { swipeLeft() }
            compose.waitUntil(5_000) { book.positionImagePath == firstImage.path }
            compose.onNodeWithContentDescription("正文插图").assertExists()
            compose.onNodeWithContentDescription("漫画或 PDF 页面").assertDoesNotExist()
            compose.onNodeWithTag("reader_body").performTouchInput { swipeLeft() }
            compose.waitUntil(5_000) { book.positionImagePath == secondImage.path }
            assertEquals(offset, book.characterOffset)
            tools()
            compose.onNodeWithContentDescription("快速添加书签").performClick()
            compose.waitUntil(5_000) { annotations.any { it.kind == AnnotationKind.BOOKMARK && it.imagePath == secondImage.path } }
            compose.onNodeWithTag("reader_body").performTouchInput { click() }
            compose.onNodeWithTag("reader_inline_image_0_1").performTouchInput { longClick() }
            compose.onNodeWithTag("reader_note_input").performTextInput("第二幅图的笔记")
            compose.onNodeWithText("保存").performClick()
            compose.waitUntil(5_000) { annotations.any { it.kind == AnnotationKind.NOTE && it.imagePath == secondImage.path && it.characterOffset == offset } }
            compose.runOnIdle { settings = settings.copy(fontSize = 30f) }
            awaitLoaded()
            assertEquals(secondImage.path, book.positionImagePath)
            compose.onNodeWithTag("reader_bookmark_marker").assertExists()
            compose.onNodeWithTag("reader_image_mark").assertExists()
            tools()
            compose.onNodeWithContentDescription("返回书架").performClick()
            compose.waitUntil(5_000) { !visible }
            compose.runOnIdle { visible = true }
            awaitLoaded()
            compose.onNodeWithTag("reader_inline_image_0_1").assertExists()
            assertEquals(secondImage.path, book.positionImagePath)
            compose.onNodeWithTag("reader_body").performTouchInput { swipeLeft() }
            compose.waitUntil(5_000) { book.positionImagePath == null && book.characterOffset == offset }
            compose.onNodeWithContentDescription("阅读正文").assert(hasText("图片之后", substring = true))
            compose.onNodeWithTag("reader_body").performTouchInput { swipeLeft() }
            compose.waitUntil(5_000) { book.positionImagePath == secondImage.path && book.characterOffset == text.length }
            compose.onNodeWithTag("reader_inline_image_0_2").assertExists()
            compose.onNodeWithTag("reader_bookmark_marker").assertDoesNotExist()
            compose.onNodeWithTag("reader_image_mark").assertDoesNotExist()
            compose.runOnIdle { settings = settings.copy(fontSize = 26f) }
            awaitLoaded()
            assertEquals(text.length, book.characterOffset)
            compose.onNodeWithTag("reader_inline_image_0_2").assertExists()
            compose.onNodeWithTag("reader_body").performTouchInput { swipeLeft() }
            compose.waitUntil(5_000) { book.chapterIndex == 1 }
            tools()
            compose.onNodeWithContentDescription("上一章").performClick()
            compose.waitUntil(5_000) { book.chapterIndex == 0 && book.pageIndex == 0 }
            compose.onNodeWithContentDescription("下一章").performClick()
            compose.waitUntil(5_000) { book.chapterIndex == 1 && book.pageIndex == 0 }
        } finally {
            compose.runOnIdle { visible = false }
            directory.deleteRecursively()
        }
    }

    @Test fun scrollNovelPassesChapterBoundaryInOneContinuousList() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "reader-continuous-${System.nanoTime()}").apply { mkdirs() }
        val first = File(directory, "first.txt").apply { writeText("第一章\n" + "沿着河岸，继续向前阅读。\n".repeat(80)) }
        val second = File(directory, "second.txt").apply { writeText("第二章\n" + "无需点击下一章，也能继续阅读。\n".repeat(80) + "第二章结束标记。") }
        var book by mutableStateOf(Book(title = "连续小说", format = BookFormat.EPUB, mode = LibraryMode.NOVEL,
            chapters = listOf(Chapter(title = "第一章", textPath = first.path), Chapter(title = "第二章", textPath = second.path))))
        var visible by mutableStateOf(true)
        try {
            compose.setContent {
                MaterialTheme {
                    if (visible) ReaderScreen(book, ReaderSettings(readingMode = ReadingMode.SCROLL), emptyList(),
                        onClose = { visible = false }, onProgress = { chapter, page, offset, percent, image ->
                            book = book.copy(chapterIndex = chapter, pageIndex = page, characterOffset = offset, progress = percent, positionImagePath = image) },
                        onSettings = {}, onAddAnnotation = {}, onDeleteAnnotation = {}, onImportFont = {}, volumeEvents = emptyFlow())
                }
            }
            compose.waitUntil(10_000) { book.progress > 0 && compose.onAllNodesWithTag("reader_loading").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithContentDescription("下一章").assertDoesNotExist()
            compose.onNodeWithTag("reader_scroll").performScrollToNode(hasText("第二章结束标记", substring = true))
            compose.waitUntil(5_000) { book.chapterIndex == 1 && book.characterOffset > 0 }
            compose.onNodeWithText("第二章结束标记", substring = true).assertExists()
            compose.onNodeWithContentDescription("下一章").assertDoesNotExist()
        } finally {
            compose.runOnIdle { visible = false }
            directory.deleteRecursively()
        }
    }
}
