package com.eyue.reader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.eyue.reader.data.Book
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.Chapter
import com.eyue.reader.data.LibraryMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookshelfBatchBackupRobolectricTest {
    @get:Rule val compose = createComposeRule()

    @Test fun batchActionsStayBelowStatusRowAndAboveBooksWhileSelecting() {
        val local = book("local")
        val cloudOnly = book("remote").copy(cloudOnly = true)
        var uploaded = emptyList<String>()
        showShelf(listOf(local, cloudOnly)) { uploaded = it.map(Book::id) }

        compose.onNodeWithTag("shelf_backup_actions").assertDoesNotExist()
        openBatchSelection()
        compose.onNodeWithTag("shelf_cloud_menu").assertIsDisplayed()
        val filters = compose.onNodeWithTag("shelf_status_filters").fetchSemanticsNode().boundsInRoot
        val actions = compose.onNodeWithTag("shelf_backup_actions").fetchSemanticsNode().boundsInRoot
        val book = compose.onNodeWithTag("backup_select_local").fetchSemanticsNode().boundsInRoot
        assertTrue("Actions must follow the batch button row", actions.top >= filters.bottom)
        assertTrue("Actions must precede the books", actions.bottom <= book.top)

        compose.onNodeWithTag("shelf_backup_select_all").performClick()
        compose.onNodeWithTag("backup_select_local").assertIsOn()
        compose.onNodeWithTag("backup_select_remote").assertIsOff().assertIsNotEnabled()
        compose.onNodeWithText("上传备份").performClick()
        compose.runOnIdle { assertEquals(listOf("local"), uploaded) }
        compose.onNodeWithTag("shelf_backup_actions").assertDoesNotExist()
    }

    @Test fun cancellingBatchClearsSelectionBeforeReopening() {
        showShelf(listOf(book("local"))) {}
        openBatchSelection()
        compose.onNodeWithTag("backup_select_local").performClick()
        compose.onNodeWithText("已选 1 本").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("shelf_backup_actions").assertDoesNotExist()
        openBatchSelection()
        compose.onNodeWithTag("backup_select_local").assertIsOff()
        compose.onNodeWithText("上传备份").assertIsNotEnabled()
    }

    @Test fun cloudMenuUsesOnlyAnIconAndListsBackupAboveRestore() {
        showShelf(listOf(book("local")), onRestore = {}) {}
        val cloud = compose.onNodeWithTag("shelf_cloud_menu").assertIsDisplayed().assertIsEnabled()
        cloud.assert(hasContentDescription("云端备份"))
        assertFalse(cloud.fetchSemanticsNode().config.contains(SemanticsProperties.Text))
        compose.onNodeWithText("批量备份").assertDoesNotExist()
        compose.onNodeWithText("还原书籍信息").assertDoesNotExist()
        cloud.performClick()
        val backup = compose.onNodeWithTag("shelf_batch_backup").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val restore = compose.onNodeWithTag("shelf_restore_cloud_info").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("The menu must list backup and restore vertically", restore.top >= backup.bottom)
    }

    @Test fun restoringBookInformationCallsRestoreWithoutEnteringBatchSelection() {
        var restores = 0
        var uploads = 0
        showShelf(listOf(book("local")), onRestore = { restores++ }) { uploads++ }
        compose.onNodeWithTag("shelf_cloud_menu").performClick()
        compose.onNodeWithTag("shelf_restore_cloud_info").performClick()
        compose.runOnIdle { assertEquals(1, restores); assertEquals(0, uploads) }
        compose.onNodeWithTag("shelf_backup_actions").assertDoesNotExist()
        compose.onNodeWithTag("backup_select_local").assertDoesNotExist()
        compose.onNodeWithTag("shelf_restore_cloud_info").assertDoesNotExist()
    }

    @Test fun busyCloudTaskDisablesTheMenuEntry() {
        showShelf(listOf(book("local")), cloudBusy = true, onRestore = {}) {}
        compose.onNodeWithTag("shelf_cloud_menu").assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithTag("shelf_batch_backup").assertDoesNotExist()
        compose.onNodeWithTag("shelf_restore_cloud_info").assertDoesNotExist()
        compose.onNodeWithTag("shelf_backup_actions").assertDoesNotExist()
    }

    private fun openBatchSelection() {
        compose.onNodeWithTag("shelf_cloud_menu").performClick()
        compose.onNodeWithTag("shelf_batch_backup").performClick()
    }

    private fun showShelf(books: List<Book>, cloudBusy: Boolean = false, onRestore: (() -> Unit)? = null,
        onUpload: (List<Book>) -> Unit) {
        compose.setContent {
            MaterialTheme { Bookshelf(books, "", {}, {}, {}, {}, onCloudBatch = onUpload, cloudBusy = cloudBusy,
                onRestoreCloudBookInfo = onRestore) }
        }
    }

    private fun book(id: String) = Book(id = id, title = "备份书籍 $id", format = BookFormat.TXT,
        mode = LibraryMode.NOVEL, chapters = listOf(Chapter(title = "第一章")))
}
