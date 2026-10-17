package com.eyue.reader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eyue.reader.data.Book
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.Chapter
import com.eyue.reader.data.CloudTransferProgress
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.cloud.DEFAULT_WEBDAV_SERVER
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CloudBackupUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun parallelTransfersKeepTheirOwnProgressAndOtherBooksCloudActionsEnabled() {
        val books = listOf(book(), book().copy(id = "second", title = "第二本"), book().copy(id = "third", title = "第三本"))
        val cancelled = mutableListOf<String>()
        val requested = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                Bookshelf(books, "", {}, {}, {}, {}, onCloud = { requested += it.id }, onCancelCloud = { cancelled += it.id },
                    cloudTransfers = mapOf("book" to CloudTransferProgress("上传第一本", .2f, true),
                        "second" to CloudTransferProgress("上传第二本", .7f, true)))
            }
        }
        compose.onNodeWithTag("cloud_book_progress_book").assertRangeInfoEquals(ProgressBarRangeInfo(.2f, 0f..1f))
        compose.onNodeWithTag("cloud_book_progress_second").assertRangeInfoEquals(ProgressBarRangeInfo(.7f, 0f..1f))
        compose.onNodeWithTag("cloud_book_cancel_book").performClick()
        compose.onNodeWithTag("cloud_book_cancel_second").performClick()
        compose.runOnIdle { assertEquals(listOf("book", "second"), cancelled) }
        compose.onNodeWithContentDescription("切换书架布局").performClick()
        compose.onNodeWithTag("cloud_book_third").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("third"), requested) }
    }

    @Test fun cloudCoverActionUploadsWithoutOpeningBookInBothShelfLayouts() {
        val book = book()
        var cloudCalls = 0
        var openCalls = 0
        compose.setContent {
            MaterialTheme {
                Bookshelf(listOf(book), "", {}, { openCalls++ }, {}, {}, onCloud = { cloudCalls++ })
            }
        }
        compose.onNodeWithTag("cloud_book_book").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, cloudCalls); assertEquals(0, openCalls) }
        compose.onNodeWithContentDescription("切换书架布局").performClick()
        compose.onNodeWithTag("cloud_book_book").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(2, cloudCalls); assertEquals(0, openCalls) }
    }

    @Test fun restoredCloudOnlyBookCannotOpenUntilUserRequestsDownload() {
        val book = book().copy(cloudOnly = true, cloudEnabled = true)
        var cloudCalls = 0
        var openCalls = 0
        compose.setContent {
            MaterialTheme {
                Bookshelf(listOf(book), "", {}, { openCalls++ }, {}, {}, onCloud = { cloudCalls++ })
            }
        }
        compose.onAllNodes(hasText(book.title) and hasClickAction()).onLast().assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, cloudCalls); assertEquals(0, openCalls) }
        compose.onNodeWithContentDescription("下载《${book.title}》").performClick()
        compose.runOnIdle { assertEquals(1, cloudCalls); assertEquals(0, openCalls) }
        compose.onNodeWithTag("shelf_book_more_book").performClick()
        compose.onNodeWithText("书籍信息").performClick()
        compose.onNodeWithText("存储：云端，尚未下载").assertIsDisplayed()
        compose.onNodeWithText("下载书籍").performClick()
        compose.runOnIdle { assertEquals(2, cloudCalls); assertEquals(0, openCalls) }
    }

    @Test fun transferProgressUpdatesCoverAndDisablesDuplicateCloudOperations() {
        var fraction by mutableStateOf<Float?>(.2f)
        var busy by mutableStateOf(true)
        var cloudCalls = 0
        compose.setContent {
            MaterialTheme {
                Bookshelf(listOf(book()), "", {}, {}, {}, {}, onCloud = { cloudCalls++ }, cloudBusy = busy,
                    cloudBookId = if (busy) "book" else null, cloudTaskLabel = "上传书籍", cloudFraction = fraction)
            }
        }
        compose.onNodeWithTag("cloud_book_book").assertIsNotEnabled()
        compose.onNodeWithTag("cloud_book_progress_book").assertRangeInfoEquals(ProgressBarRangeInfo(.2f, 0f..1f))
        compose.runOnIdle { fraction = .7f }
        compose.onNodeWithTag("cloud_book_progress_book").assertRangeInfoEquals(ProgressBarRangeInfo(.7f, 0f..1f))
        compose.runOnIdle { fraction = null }
        compose.onNodeWithTag("cloud_book_progress_book").assertRangeInfoEquals(ProgressBarRangeInfo.Indeterminate)
        compose.onNodeWithContentDescription("切换书架布局").performClick()
        compose.onNodeWithTag("cloud_book_progress_book").assertExists()
        compose.runOnIdle { busy = false }
        compose.onNodeWithTag("cloud_book_progress_book").assertDoesNotExist()
        compose.onNodeWithTag("cloud_book_book").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, cloudCalls) }
    }

    @Test fun uploadProgressCancelsOnlyCurrentUploadWithoutOpeningEitherShelfLayout() {
        val book = book()
        var cancellable by mutableStateOf(true)
        var cancelCalls = 0
        var cloudCalls = 0
        var openCalls = 0
        compose.setContent {
            MaterialTheme {
                Bookshelf(listOf(book, book.copy(id = "other", title = "另一册")), "", {}, { openCalls++ }, {}, {},
                    onCloud = { cloudCalls++ }, cloudBusy = true, cloudBookId = book.id,
                    cloudTaskLabel = "上传书籍", cloudFraction = .6f, onCancelCloud = {
                        assertEquals(book.id, it.id); cancelCalls++
                    }, cloudCanCancel = cancellable)
            }
        }
        compose.onNodeWithTag("cloud_book_cancel_other").assertDoesNotExist()
        compose.onNodeWithTag("cloud_book_cancel_book").assertIsDisplayed().performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, cancelCalls); assertEquals(0, openCalls); assertEquals(0, cloudCalls) }
        compose.onNodeWithTag("cloud_book_progress_book").assertRangeInfoEquals(ProgressBarRangeInfo(.6f, 0f..1f))
        compose.onNodeWithContentDescription("切换书架布局").performClick()
        compose.onNodeWithTag("cloud_book_cancel_book").assertIsDisplayed().performTouchInput { click() }
        compose.runOnIdle { assertEquals(2, cancelCalls); assertEquals(0, openCalls); assertEquals(0, cloudCalls) }
        compose.runOnIdle { cancellable = false }
        compose.onNodeWithTag("cloud_book_cancel_book").assertDoesNotExist()
        compose.onNodeWithTag("cloud_book_progress_book").assertRangeInfoEquals(ProgressBarRangeInfo(.6f, 0f..1f))
    }

    @Test fun readingHomeAndHistoryShareUploadCancellation() {
        val book = book().copy(lastReadAt = System.currentTimeMillis())
        var cancelCalls = 0
        var openCalls = 0
        var cloudCalls = 0
        compose.setContent {
            MaterialTheme {
                ReadingHome(listOf(book), 0, { openCalls++ }, {}, {}, onCloud = { cloudCalls++ }, cloudBusy = true,
                    cloudBookId = book.id, cloudTaskLabel = "等待重试", onCancelCloud = { cancelCalls++ }, cloudCanCancel = true)
            }
        }
        compose.onAllNodesWithTag("cloud_book_cancel_book").assertCountEquals(2)
        compose.onAllNodesWithTag("cloud_book_cancel_book")[0].performTouchInput { click() }
        compose.onAllNodesWithTag("cloud_book_cancel_book")[1].performTouchInput { click() }
        compose.runOnIdle { assertEquals(2, cancelCalls); assertEquals(0, openCalls); assertEquals(0, cloudCalls) }
    }

    @Test fun cloudDeleteRequiresEnabledBookAndExplicitConfirmationInBothShelfLayouts() {
        var enabled by mutableStateOf(false)
        var cloudDeletes = 0
        var localDeletes = 0
        var openCalls = 0
        compose.setContent {
            MaterialTheme {
                Bookshelf(listOf(book().copy(cloudEnabled = enabled)), "", {}, { openCalls++ }, { localDeletes++ }, {},
                    onDeleteCloud = { assertEquals("book", it.id); cloudDeletes++ })
            }
        }
        compose.onNodeWithTag("shelf_book_more_book").performClick()
        compose.onNodeWithTag("shelf_book_delete_cloud_book").assertDoesNotExist()
        compose.runOnIdle { enabled = true }
        compose.onNodeWithTag("shelf_book_delete_cloud_book").assertIsEnabled().performClick()
        compose.onNodeWithTag("shelf_delete_cloud_dialog").assertIsDisplayed()
        compose.onNodeWithText("云端的书籍文件及阅读记录将删除，并关闭该书的自动备份。本地书籍、阅读进度和标注保留。").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, cloudDeletes) }
        compose.onNodeWithTag("shelf_delete_cloud_cancel").performClick()
        compose.onNodeWithTag("shelf_delete_cloud_dialog").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, cloudDeletes) }
        compose.onNodeWithContentDescription("切换书架布局").performClick()
        compose.onNodeWithTag("shelf_book_more_book").performClick()
        compose.onNodeWithTag("shelf_book_delete_cloud_book").performClick()
        compose.onNodeWithTag("shelf_delete_cloud_confirm").performClick()
        compose.runOnIdle { assertEquals(1, cloudDeletes); assertEquals(0, localDeletes); assertEquals(0, openCalls) }
    }

    @Test fun cloudDeletionCannotStartOrConfirmDuringAnotherTransfer() {
        var busy by mutableStateOf(true)
        var deletes = 0
        compose.setContent {
            MaterialTheme {
                Bookshelf(listOf(book().copy(cloudEnabled = true, cloudOnly = true)), "", {}, {}, {}, {},
                    cloudBusy = busy, onDeleteCloud = { deletes++ })
            }
        }
        compose.onNodeWithTag("shelf_book_more_book").performClick()
        compose.onNodeWithTag("shelf_book_delete_cloud_book").assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithTag("shelf_delete_cloud_dialog").assertDoesNotExist()
        compose.runOnIdle { busy = false }
        compose.onNodeWithTag("shelf_book_delete_cloud_book").performClick()
        compose.onNodeWithText("云端的书籍文件及阅读记录将删除，并关闭该书的自动备份。尚未下载的书架占位也将移除。").assertIsDisplayed()
        compose.runOnIdle { busy = true }
        compose.onNodeWithTag("shelf_delete_cloud_confirm").assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, deletes); busy = false }
        compose.onNodeWithTag("shelf_delete_cloud_confirm").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, deletes) }
    }

    @Test fun accountRequiresApplicationPasswordAndClearsPasswordAfterSave() {
        var account by mutableStateOf("")
        var configured by mutableStateOf(false)
        val saved = mutableListOf<Pair<String, String>>()
        compose.setContent {
            MaterialTheme {
                CloudBackupSettings(account, configured, false, false, "", null, onSave = { email, password, server ->
                    assertEquals(DEFAULT_WEBDAV_SERVER, server)
                    saved += email to password
                    account = email
                    configured = true
                }, onTest = {}, onAutoBackup = {}, onRestore = {}, onDisconnect = {})
            }
        }
        compose.onNodeWithTag("cloud_account").assertDoesNotExist()
        compose.onNodeWithTag("cloud_open_account").performClick()
        compose.onNodeWithTag("cloud_save_account").assertIsNotEnabled()
        compose.onNodeWithTag("cloud_account").performTextInput("reader@example.com")
        compose.onNodeWithTag("cloud_save_account").assertIsNotEnabled()
        compose.onNodeWithTag("cloud_password").performTextInput("application-password")
        compose.onNodeWithTag("cloud_save_account").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("reader@example.com" to "application-password"), saved) }
        compose.onNodeWithTag("cloud_password").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("cloud_save_account").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("reader@example.com" to "", saved.last()) }
        compose.onNodeWithTag("cloud_account").performTextReplacement("other@example.com")
        compose.onNodeWithTag("cloud_save_account").assertIsNotEnabled()
    }

    @Test fun restoreAndAutomaticBackupRemainExplicitUserActions() {
        var busy by mutableStateOf(false)
        var autoBackup by mutableStateOf(false)
        var restores = 0
        var tests = 0
        compose.setContent {
            MaterialTheme {
                CloudBackupSettings("reader@example.com", true, autoBackup, busy, "正在还原书架", null,
                    onSave = { _, _, _ -> }, onTest = { tests++ }, onAutoBackup = { autoBackup = it },
                    onRestore = { restores++ }, onDisconnect = {})
            }
        }
        compose.runOnIdle { assertEquals(0, restores); assertEquals(0, tests); assertEquals(false, autoBackup) }
        compose.onNodeWithTag("cloud_auto_backup").assertIsOff().performClick()
        compose.runOnIdle { assertEquals(true, autoBackup); assertEquals(0, restores) }
        compose.onNodeWithTag("cloud_restore_info").performClick()
        compose.runOnIdle { assertEquals(1, restores); busy = true }
        compose.onNodeWithTag("cloud_restore_info").assertIsNotEnabled()
        compose.onNodeWithTag("cloud_test_connection").assertDoesNotExist()
        compose.onNodeWithTag("cloud_auto_backup").assertIsNotEnabled()
    }

    @Test fun serverChangesRequirePasswordAndMeteredBackupRemainsAnExplicitOptIn() {
        var metered by mutableStateOf(false)
        val saved = mutableListOf<Triple<String, String, String>>()
        compose.setContent {
            MaterialTheme {
                CloudBackupSettings("reader@example.com", true, true, false, "", null,
                    onSave = { account, password, server -> saved += Triple(account, password, server) },
                    onTest = {}, onAutoBackup = {}, onRestore = {}, onDisconnect = {},
                    autoBackupMetered = metered, onAutoBackupMetered = { metered = it })
            }
        }
        compose.onNodeWithTag("cloud_auto_backup_metered").assertIsOff().performClick()
        compose.runOnIdle { assertEquals(true, metered) }
        compose.onNodeWithTag("cloud_open_account").performClick()
        compose.onNodeWithTag("cloud_save_account").assertIsEnabled()
        compose.onNodeWithTag("cloud_server").performTextReplacement("https://files.example.com/dav")
        compose.onNodeWithTag("cloud_save_account").assertIsNotEnabled()
        compose.onNodeWithTag("cloud_password").performTextInput("new-server-password")
        compose.onNodeWithTag("cloud_save_account").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(Triple("reader@example.com", "new-server-password", "https://files.example.com/dav/")), saved) }
        compose.onNodeWithTag("cloud_server").performTextReplacement("https://user:secret@files.example.com/dav")
        compose.onNodeWithTag("cloud_save_account").assertIsNotEnabled()
    }

    @Test fun batchBackupSelectsLocalBooksAndSkipsCloudOnlyPlaceholders() {
        val first = book()
        val second = first.copy(id = "second", title = "第二本")
        var selected = emptyList<String>()
        compose.setContent {
            MaterialTheme { Bookshelf(listOf(first, second), "", {}, {}, {}, {}, onCloud = {},
                onCloudBatch = { selected = it.map { book -> book.id } }) }
        }
        compose.onNodeWithTag("shelf_cloud_menu").performClick()
        compose.onNodeWithText("批量备份").performClick()
        compose.onNodeWithTag("backup_select_book").performClick()
        compose.onNodeWithTag("backup_select_second").performClick()
        compose.onNodeWithText("已选 2 本").assertIsDisplayed()
        compose.onNodeWithText("上传备份").performClick()
        compose.runOnIdle { assertEquals(listOf("book", "second"), selected) }
    }

    @Test fun bookshelfCloudIconOpensRestoreAndBackupMenu() {
        var restores = 0
        compose.setContent {
            MaterialTheme { Bookshelf(listOf(book()), "", {}, {}, {}, {}, onCloudBatch = {},
                onRestoreCloudBookInfo = { restores++ }) }
        }
        compose.onNodeWithText("批量备份").assertDoesNotExist()
        compose.onNodeWithTag("shelf_cloud_menu").assert(hasContentDescription("云端备份")).performClick()
        compose.onNodeWithText("批量备份").assertIsDisplayed()
        compose.onNodeWithText("还原书籍信息").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, restores) }
        compose.onNodeWithTag("shelf_backup_actions").assertDoesNotExist()
    }

    private fun book() = Book(id = "book", title = "云端阅读测试", format = BookFormat.TXT,
        mode = LibraryMode.NOVEL, chapters = listOf(Chapter(title = "第一章")))
}
