package com.eyue.reader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.eyue.reader.data.*
import org.junit.Assert.assertEquals
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
class PendingImportQueueRobolectricTest {
    @get:Rule val compose = createComposeRule()

    @Test fun queuedCardsKeepTheirOwnProgressAndCompletedBooksCanBeOpenedImmediately() {
        val active = PendingImport("当前批次", LibraryMode.NOVEL, ImportProgress("解析正文", fraction = .4f), id = "active")
        val queued = PendingImport("排队小说", LibraryMode.NOVEL, id = "queued", queued = true)
        var tasks by mutableStateOf(listOf(active, queued))
        var books by mutableStateOf(emptyList<Book>())
        var opened: String? = null
        compose.setContent { MaterialTheme {
            Bookshelf(books, "", {}, { opened = it.id }, {}, {}, pendingImports = tasks)
        } }
        compose.onAllNodesWithTag("pending_import_book").assertCountEquals(2)
        val queuedCard = hasTestTag("pending_import_book") and hasAnyDescendant(hasText("排队小说"))
        compose.onNode(queuedCard).assertHasNoClickAction()
        compose.onNode(hasTestTag("pending_import_progress") and hasAnyAncestor(queuedCard))
            .assertRangeInfoEquals(ProgressBarRangeInfo.Indeterminate)
        compose.onNodeWithText("等待导入").assertExists()

        val completed = Book(id = "ready", title = "已完成的书", format = BookFormat.TXT, mode = LibraryMode.NOVEL,
            chapters = listOf(Chapter(title = "第一章")))
        compose.runOnIdle {
            tasks = listOf(active.copy(completed = 1, progress = ImportProgress("下一本", fraction = .8f)), queued)
            books = listOf(completed)
        }
        compose.onNodeWithText("已导入 1 本，可直接阅读").assertExists()
        compose.onNode(hasTestTag("pending_import_progress") and hasAnyAncestor(queuedCard))
            .assertRangeInfoEquals(ProgressBarRangeInfo.Indeterminate)
        compose.runOnIdle { tasks = listOf(queued) }
        compose.onAllNodes(hasText(completed.title) and hasClickAction()).onFirst().performClick()
        compose.runOnIdle { assertEquals(completed.id, opened) }
    }
}
