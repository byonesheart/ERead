package com.eyue.reader.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.eyue.reader.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w392dp-h852dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookshelfThemeActionsRobolectricTest {
    @get:Rule val compose = createComposeRule()
    private val host = AtomicReference<android.view.View>()

    @Test fun classicUsesThreeColumnsAndLongPressActions() = checkTheme(AppThemeStyle.CLASSIC)
    @Test fun pixelUsesThreeColumnsAndLongPressActions() = checkTheme(AppThemeStyle.PIXEL)
    @Test fun xingcaiUsesTheSameShelfAndLongPressActions() = checkTheme(AppThemeStyle.XINGCAI)

    private fun checkTheme(style: AppThemeStyle) {
        val books = (1..4).map { book("book$it").copy(title = if (it == 2) "这是一个需要两行甚至更多行显示的长书名" else "短书名$it",
            sourcePath = "/storage/emulated/0/Books/book$it.txt") }
        var opened = 0
        var renamed = ""
        var dark by mutableStateOf(false)
        compose.setContent {
            host.set(LocalView.current)
            EReadTheme(dark, false, style) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground) {
                    Bookshelf(books, "", {}, { opened++ }, {}, {}, onRenameBook = { id, title ->
                        assertEquals("book1", id); renamed = title; true
                    }, onVoiceRoles = {})
                }
            }
        }
        val bounds = (1..4).map { compose.onNodeWithTag("shelf_book_cover_book$it", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot }
        assertEquals(bounds[0].top, bounds[1].top)
        assertEquals(bounds[0].top, bounds[2].top)
        assertTrue(bounds[3].top > bounds[0].bottom)
        assertEquals(.8f, bounds[0].width / bounds[0].height, .01f)
        val authors = (1..3).map { compose.onNodeWithTag("shelf_book_author_book$it").fetchSemanticsNode().boundsInRoot }
        val chapters = (1..3).map { compose.onNodeWithTag("shelf_book_chapter_book$it").fetchSemanticsNode().boundsInRoot }
        assertEquals(authors[0].top, authors[1].top, .1f)
        assertEquals(authors[0].top, authors[2].top, .1f)
        assertEquals(chapters[0].top, chapters[1].top, .1f)
        val chapter = compose.onNodeWithTag("shelf_book_chapter_book1").fetchSemanticsNode().boundsInRoot
        val percent = compose.onNodeWithTag("shelf_book_percent_book1").assertTextEquals("25%").fetchSemanticsNode().boundsInRoot
        assertTrue(percent.top >= chapter.bottom)
        assertEquals(chapter.left, percent.left, .1f)
        val titleLayouts = (1..2).map { index ->
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithTag("shelf_book_title_book$index", useUnmergedTree = true)
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            layouts.single()
        }
        assertEquals("Short and long titles reserve the same two-line height", titleLayouts[0].size.height, titleLayouts[1].size.height)
        assertEquals(2, titleLayouts[1].lineCount)
        capture("shelf-${style.name.lowercase()}")
        compose.onNodeWithTag("shelf_book_cover_book1", useUnmergedTree = true).performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, opened) }
        compose.onNodeWithTag("shelf_book_cover_book1", useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithTag("book_action_sheet").assertIsDisplayed()
        compose.onNodeWithTag("sheet_book_location").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, opened) }
        capture("shelf-actions-${style.name.lowercase()}", dialog = true)
        val sheetTop = compose.onNodeWithTag("book_action_sheet").fetchSemanticsNode().boundsInRoot.top
        compose.onNode(isDialog()).performTouchInput { swipeUp(startY = height * .95f, endY = height * .7f) }
        assertEquals("Scrolling more actions must not move the sheet", sheetTop,
            compose.onNodeWithTag("book_action_sheet").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.onNode(isDialog()).performTouchInput { swipeDown(startY = height * .7f, endY = height * .95f) }
        assertEquals("Dragging down must not move or dismiss more actions", sheetTop,
            compose.onNodeWithTag("book_action_sheet").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.onNodeWithTag("shelf_book_location_book1").performScrollTo().performClick()
        compose.onNodeWithTag("book_location_dialog").assertIsDisplayed()
        compose.onNodeWithText("/storage/emulated/0/Books/book1.txt").assertIsDisplayed()
        compose.onNodeWithTag("book_location_copy").performClick()
        compose.onNodeWithText("已复制").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithTag("shelf_book_cover_book1", useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithTag("shelf_book_rename_book1").performScrollTo().performClick()
        compose.onNodeWithTag("book_action_sheet").assertDoesNotExist()
        compose.onNodeWithTag("shelf_rename_title").performTextReplacement("新的书名")
        capture("shelf-rename-${style.name.lowercase()}", dialog = true)
        compose.onNodeWithTag("shelf_rename_save").performClick()
        compose.runOnIdle { assertEquals("新的书名", renamed); dark = true }
        compose.onNodeWithTag("shelf_book_title_book1").performTouchInput { longClick() }
        compose.onNodeWithTag("shelf_rename_title").assertIsDisplayed()
        compose.onNodeWithTag("book_action_sheet").assertDoesNotExist()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("shelf_book_cover_book1", useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithTag("book_action_sheet").assertIsDisplayed()
        capture("shelf-actions-${style.name.lowercase()}-dark", dialog = true)
        compose.onNodeWithText("移动分组").performClick()
        compose.onNodeWithTag("book_action_sheet").assertDoesNotExist()
        compose.onNodeWithTag("shelf_move_dialog").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithContentDescription("切换书架布局").performClick()
        capture("shelf-list-${style.name.lowercase()}-dark")
        compose.onNodeWithTag("shelf_list_row_book1").performTouchInput { longClick() }
        compose.onNodeWithTag("book_action_sheet").assertIsDisplayed()
    }

    @Test fun remoteListRowOpensActionsAndCloudActionsRespectTheCurrentTransfer() {
        val book = book("remote").copy(cloudOnly = true, cloudEnabled = true)
        var busy by mutableStateOf(true)
        var opened = 0
        var downloaded = 0
        compose.setContent { EReadTheme(true, false, AppThemeStyle.XINGCAI) {
            Bookshelf(listOf(book), "", {}, { opened++ }, {}, {}, onCloud = { downloaded++ },
                onDeleteCloud = {}, cloudBusy = busy, onClearAiSpeechCache = {}, onGenerateSpeech = {})
        } }
        compose.onNodeWithContentDescription("切换书架布局").performClick()
        compose.onNodeWithTag("shelf_list_row_remote").performTouchInput { longClick() }
        compose.onNodeWithTag("book_action_sheet").assertIsDisplayed()
        compose.onNodeWithTag("shelf_book_cloud_remote").assertIsNotEnabled()
        compose.onNodeWithTag("shelf_book_delete_cloud_remote").assertIsNotEnabled()
        compose.onNodeWithTag("shelf_book_generate_speech_remote").assertDoesNotExist()
        compose.onNodeWithTag("shelf_book_clear_ai_speech_remote").assertDoesNotExist()
        compose.runOnIdle { busy = false }
        compose.onNodeWithTag("shelf_book_cloud_remote").assertIsEnabled().performClick()
        compose.onNodeWithTag("book_action_sheet").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, downloaded); assertEquals(0, opened) }
    }

    @Test fun batchSelectionLongPressDoesNotOpenActionsOrReadTheBook() {
        var opened = 0
        compose.setContent { EReadTheme(false, false, AppThemeStyle.CLASSIC) {
            Bookshelf(listOf(book("local")), "", {}, { opened++ }, {}, {}, onCloudBatch = {})
        } }
        compose.onNodeWithTag("shelf_batch_manage").performClick()
        compose.onNodeWithTag("shelf_book_cover_local", useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithTag("book_action_sheet").assertDoesNotExist()
        compose.onNodeWithTag("shelf_book_more_local").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, opened) }
        compose.onNodeWithTag("shelf_book_title_local").performClick()
        compose.onNodeWithTag("backup_select_local").assertIsOn()
    }

    @Test @Config(qualifiers = "w392dp-h520dp-mdpi")
    fun compactMenuStillScrollsInternallyOnAShortScreen() {
        var chosen = false
        compose.setContent { EReadTheme(false, false, AppThemeStyle.CLASSIC) {
            ShelfBookActions(book = book("compact"), transfer = null, cloudConfigured = true, cloudBusy = false, cloudDirty = false,
                onDismiss = {}, onDetails = {}, onMove = {}, onRename = {}, onGenerateSpeech = {}, onVoiceRoles = {},
                onCloud = {}, onDeleteCloud = {}, onClearSpeech = { chosen = true }, onDelete = {}, onLocation = {})
        } }
        compose.onNodeWithTag("book_action_sheet").assertIsDisplayed()
        capture("shelf-actions-short-screen", dialog = true)
        val scroll = compose.onNode(hasScrollAction()).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("The short-screen fixture must actually need internal scrolling", scroll.maxValue() > 0)
        compose.onNodeWithTag("shelf_book_clear_ai_speech_compact").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue("Internal menu scrolling must preserve access to the last action", chosen) }
    }

    private fun book(id: String) = Book(id = id, title = "测试书籍 $id", author = "佚名", format = BookFormat.EPUB,
        mode = LibraryMode.NOVEL, progress = .25f, addedAt = 100L, chapters = listOf(Chapter(title = "第一章")))

    private fun capture(name: String, dialog: Boolean = false) {
        val output = System.getenv("EREAD_TEST_ARTIFACTS")?.let(::File)
        compose.waitForIdle()
        val bitmap = compose.runOnIdle {
            val view = if (dialog) checkNotNull(ShadowDialog.getLatestDialog()?.window?.decorView) else host.get()
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        // A clipped menu row may paint its own color; a floating panel leaves only the translucent scrim here.
        if (dialog && name.startsWith("shelf-actions-")) assertEquals("The painted panel must reach the bottom of the full dialog",
            255, android.graphics.Color.alpha(bitmap.getPixel(bitmap.width / 2, bitmap.height - 1)))
        output?.let { it.mkdirs(); File(it, "$name.png").outputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) } }
        bitmap.recycle()
    }
}
