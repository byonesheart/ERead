package com.eyue.reader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.eyue.reader.data.Book
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.Chapter
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.cloud.CloudOperationProgress
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookshelfBatchBackupRobolectricTest {
    @get:Rule val compose = createComposeRule()
    private val ownedFolders = mutableListOf<File>()
    private val host = AtomicReference<android.view.View>()

    @After fun cleanFiles() {
        val books = File(RuntimeEnvironment.getApplication().filesDir, "books").canonicalFile
        ownedFolders.forEach { folder ->
            check(folder.canonicalFile.parentFile == books)
            check(folder.deleteRecursively())
        }
    }

    @Test fun batchActionsStayBelowStatusRowAndAboveBooksWhileSelecting() {
        val local = book("local")
        val cloudOnly = book("remote").copy(cloudOnly = true)
        var uploaded = emptyList<String>()
        showShelf(listOf(local, cloudOnly)) { uploaded = it.map(Book::id) }

        compose.onNodeWithTag("shelf_backup_actions").assertDoesNotExist()
        openBatchSelection()
        compose.onNodeWithTag("shelf_batch_manage").assertIsDisplayed()
        val filters = compose.onNodeWithTag("shelf_status_filters").fetchSemanticsNode().boundsInRoot
        val actions = compose.onNodeWithTag("shelf_backup_actions").fetchSemanticsNode().boundsInRoot
        val book = compose.onNodeWithTag("backup_select_local").fetchSemanticsNode().boundsInRoot
        assertTrue("Actions must follow the batch button row", actions.top >= filters.bottom)
        assertTrue("Actions must precede the books", actions.bottom <= book.top)

        compose.onNodeWithTag("shelf_backup_select_all").performClick()
        compose.onNodeWithTag("backup_select_local").assertIsOn()
        compose.onNodeWithTag("backup_select_remote").assertIsOn().assertIsEnabled()
        compose.onNodeWithTag("shelf_book_title_${cloudOnly.id}").performClick()
        compose.onNodeWithTag("backup_select_remote").assertIsOff()
        compose.onNodeWithTag("shelf_book_title_${cloudOnly.id}").performClick()
        compose.onNodeWithTag("backup_select_remote").assertIsOn()
        capture("bookshelf-batch-actions")
        compose.onNodeWithTag("shelf_batch_transfer").performClick()
        compose.runOnIdle { assertEquals(listOf("local", "remote"), uploaded) }
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
        compose.onNodeWithTag("shelf_batch_transfer").assertIsNotEnabled()
    }

    @Test fun managementAndRestoreUseIndependentIconsInTheStatusRow() {
        showShelf(listOf(book("local")), onRestore = {}) {}
        val manage = compose.onNodeWithTag("shelf_batch_manage").assertIsDisplayed().assertIsEnabled()
        val restore = compose.onNodeWithTag("shelf_restore_cloud_info").assertIsDisplayed().assertIsEnabled()
        manage.assert(hasContentDescription("批量管理"))
        restore.assert(hasContentDescription("还原书籍信息"))
        assertFalse(manage.fetchSemanticsNode().config.contains(SemanticsProperties.Text))
        assertFalse(restore.fetchSemanticsNode().config.contains(SemanticsProperties.Text))
        compose.onNodeWithText("批量备份").assertDoesNotExist()
        compose.onNodeWithText("还原书籍信息").assertDoesNotExist()
        val manageBounds = manage.fetchSemanticsNode().boundsInRoot
        val restoreBounds = restore.fetchSemanticsNode().boundsInRoot
        assertTrue(restoreBounds.left >= manageBounds.right)
        assertEquals(manageBounds.top, restoreBounds.top)
        compose.onNodeWithTag("shelf_cloud_menu").assertDoesNotExist()
    }

    @Test fun restoringBookInformationCallsRestoreWithoutEnteringBatchSelection() {
        var restores = 0
        var uploads = 0
        showShelf(listOf(book("local")), onRestore = { restores++ }) { uploads++ }
        compose.onNodeWithTag("shelf_restore_cloud_info").performClick()
        compose.runOnIdle { assertEquals(1, restores); assertEquals(0, uploads) }
        compose.onNodeWithTag("shelf_backup_actions").assertDoesNotExist()
        compose.onNodeWithTag("backup_select_local").assertDoesNotExist()
        compose.onNodeWithTag("shelf_restore_cloud_info").assertIsDisplayed()
    }

    @Test fun busyCloudTaskDisablesRestoreAndTransferWhileSelectionRemainsAvailable() {
        showShelf(listOf(book("local")), cloudBusy = true, onRestore = {}) {}
        compose.onNodeWithTag("shelf_restore_cloud_info").assertIsNotEnabled()
        openBatchSelection()
        compose.onNodeWithTag("backup_select_local").performClick()
        compose.onNodeWithTag("shelf_batch_transfer").assertIsNotEnabled()
    }

    @Test fun restorationProgressStaysBelowItsEntryAndLeavesOtherBookTransfersAvailable() {
        val progress = mutableStateOf<CloudOperationProgress?>(CloudOperationProgress("已读取 1 / 4 本书籍", .25f))
        var transferred = 0
        compose.setContent { MaterialTheme {
            Bookshelf(listOf(book("local")), "", {}, {}, {}, {}, onCloud = { transferred++ }, onCloudBatch = {},
                onRestoreCloudBookInfo = {}, restoreProgress = progress.value)
        } }
        compose.onNodeWithTag("shelf_restore_cloud_info").assertIsNotEnabled()
        compose.onNodeWithTag("shelf_restore_progress_percent").assertTextEquals("25%")
        compose.onNodeWithTag("shelf_restore_progress_bar").assertRangeInfoEquals(ProgressBarRangeInfo(.25f, 0f..1f))
        val filters = compose.onNodeWithTag("shelf_status_filters").fetchSemanticsNode().boundsInRoot
        val indicator = compose.onNodeWithTag("shelf_restore_progress").fetchSemanticsNode().boundsInRoot
        assertTrue("Restore progress must stay below the entry row", indicator.top >= filters.bottom)
        compose.onNodeWithTag("cloud_book_local").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, transferred) }
        openBatchSelection()
        compose.onNodeWithTag("backup_select_local").performClick()
        compose.onNodeWithTag("shelf_batch_transfer").assertIsEnabled()
        compose.runOnIdle { progress.value = null }
        compose.onNodeWithTag("shelf_restore_progress").assertDoesNotExist()
        compose.onNodeWithTag("shelf_restore_cloud_info").assertIsEnabled()
    }

    @Test fun mixedBatchDeletionListsEachLocalSizeAndRequiresCloudMeteredConfirmation() {
        val local = storedBook("local", 2048)
        val remote = storedBook("remote", 128).copy(cloudOnly = true)
        var removed: Pair<List<String>, Boolean>? = null
        showShelf(listOf(local, remote), cloudConfigured = true, meteredNetwork = true,
            onRemove = { selected, cloud -> removed = selected.map(Book::id) to cloud }) {}
        openBatchSelection()
        compose.onNodeWithTag("shelf_backup_select_all").performClick()
        compose.onNodeWithTag("shelf_batch_delete").performClick()
        waitForSizes(local, remote)
        compose.onNodeWithTag("shelf_batch_remove_${local.id}_space").assertTextEquals("本地占用：" + size(local))
        compose.onNodeWithTag("shelf_batch_remove_${remote.id}_space").assertTextEquals("本地占用：" + size(remote))
        compose.onNodeWithText("待下载占位：仅统计本地占位缓存，云端原文件未计入本地占用。").assertExists()
        capture("bookshelf-batch-delete", dialog = true)
        compose.onNodeWithTag("shelf_batch_remove_cloud").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithTag("shelf_batch_remove_confirm").assertIsNotEnabled()
        compose.onNodeWithText("同时删除所选书籍的云端正文和阅读记录，并关闭其自动备份。").assertExists()
        compose.onNodeWithTag("shelf_batch_remove_metered").performScrollTo().performClick()
        compose.onNodeWithTag("shelf_batch_remove_confirm").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(local.id, remote.id) to true, removed) }
        compose.onNodeWithTag("shelf_backup_actions").assertDoesNotExist()
    }

    @Test fun cancellingDeletionKeepsSelectionAndDoesNotCallRemovalOrCloud() {
        var calls = 0
        val local = storedBook("cancel", 1024)
        showShelf(listOf(local), onRemove = { _, _ -> calls++ }) { calls++ }
        openBatchSelection()
        compose.onNodeWithTag("backup_select_${local.id}").performClick()
        compose.onNodeWithTag("shelf_batch_delete").performClick()
        waitForSizes(local)
        compose.onNodeWithTag("shelf_batch_remove_cloud").performScrollTo().assertIsOff().assertIsNotEnabled()
        compose.onNodeWithTag("shelf_batch_remove_cancel").performClick()
        compose.onNodeWithTag("shelf_batch_remove_dialog").assertDoesNotExist()
        compose.onNodeWithTag("backup_select_${local.id}").assertIsOn()
        compose.runOnIdle { assertEquals(0, calls) }
    }

    @Test fun sizeFailureNeverAppearsAsZeroOrEnablesDeletion() {
        val invalid = book("../outside")
        var calls = 0
        showShelf(listOf(invalid), onRemove = { _, _ -> calls++ }) {}
        openBatchSelection()
        compose.onNodeWithTag("shelf_backup_select_all").performClick()
        compose.onNodeWithTag("shelf_batch_delete").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag("shelf_batch_remove_${invalid.id}_space") and hasText("本地占用：统计失败")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("shelf_batch_remove_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("shelf_batch_remove_cancel").performClick()
        compose.runOnIdle { assertEquals(0, calls) }
    }

    @Test fun selectingAllAfterFilteringDoesNotAddHiddenBooksAndConfirmationListsSelectionsAcrossFilters() {
        val reading = book("reading").copy(lastReadAt = 1, progress = .2f)
        val finished = book("finished").copy(progress = 1f, cloudOnly = true)
        val hidden = book("hidden")
        var removed = false
        showShelf(listOf(reading, finished, hidden), onRemove = { _, _ -> removed = true }) {}
        openBatchSelection()
        compose.onNodeWithText("在读", substring = false).performClick()
        compose.onNodeWithTag("shelf_backup_select_all").performClick()
        compose.onNodeWithText("已选 1 本").assertIsDisplayed()
        compose.onNodeWithText("读完", substring = false).performClick()
        compose.onNodeWithTag("shelf_backup_select_all").performClick()
        compose.onNodeWithText("已选 2 本").assertIsDisplayed()
        compose.onNodeWithTag("shelf_batch_delete").performClick()
        compose.onNode(hasText(reading.title) and hasAnyAncestor(hasTestTag("shelf_batch_remove_dialog"))).assertExists()
        compose.onNode(hasText(finished.title) and hasAnyAncestor(hasTestTag("shelf_batch_remove_dialog"))).assertExists()
        compose.onNodeWithTag("shelf_batch_remove_hidden_space").assertDoesNotExist()
        compose.onNodeWithTag("shelf_batch_remove_cancel").performClick()
        compose.runOnIdle { assertFalse(removed) }
    }

    private fun openBatchSelection() {
        compose.onNodeWithTag("shelf_batch_manage").performClick()
    }

    private fun showShelf(books: List<Book>, cloudBusy: Boolean = false, onRestore: (() -> Unit)? = null,
        cloudConfigured: Boolean = false, meteredNetwork: Boolean = false, onRemove: ((List<Book>, Boolean) -> Unit)? = null,
        onUpload: (List<Book>) -> Unit) {
        compose.setContent {
            host.set(LocalView.current)
            MaterialTheme { Bookshelf(books, "", {}, {}, {}, {}, onCloudBatch = onUpload, cloudBusy = cloudBusy,
                onRestoreCloudBookInfo = onRestore, onRemoveBatch = onRemove, cloudConfigured = cloudConfigured, meteredNetwork = meteredNetwork) }
        }
    }

    private fun storedBook(label: String, bytes: Int): Book {
        val book = book("$label-${UUID.randomUUID()}")
        val directory = File(RuntimeEnvironment.getApplication().filesDir, "books/${book.id}").apply { mkdirs() }
        ownedFolders += directory
        File(directory, "content.bin").writeBytes(ByteArray(bytes) { 1 })
        return book
    }

    private fun size(book: Book) = android.text.format.Formatter.formatShortFileSize(RuntimeEnvironment.getApplication(),
        runBlocking { com.eyue.reader.data.localBookStorageBytes(RuntimeEnvironment.getApplication(), book) })

    private fun waitForSizes(vararg books: Book) {
        val expected = books.associate { it.id to size(it) }
        compose.waitUntil(5_000) {
            books.all { book -> compose.onAllNodes(hasTestTag("shelf_batch_remove_${book.id}_space") and
                hasText("本地占用：${expected.getValue(book.id)}")).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    private fun capture(name: String, dialog: Boolean = false) {
        val directory = System.getenv("EREAD_TEST_ARTIFACTS")?.let(::File) ?: return
        directory.mkdirs()
        val bitmap = compose.runOnIdle {
            val view = if (dialog) org.robolectric.shadows.ShadowDialog.getLatestDialog()?.window?.decorView ?: host.get() else host.get()
            android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888).also {
                view.draw(android.graphics.Canvas(it))
            }
        }
        try { File(directory, "$name.png").outputStream().use { output ->
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
        } } finally { bitmap.recycle() }
    }

    private fun book(id: String) = Book(id = id, title = "备份书籍 $id", format = BookFormat.TXT,
        mode = LibraryMode.NOVEL, chapters = listOf(Chapter(title = "第一章")))
}
