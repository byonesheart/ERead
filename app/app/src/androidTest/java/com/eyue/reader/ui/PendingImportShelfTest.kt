package com.eyue.reader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eyue.reader.data.Book
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.Chapter
import com.eyue.reader.data.ImportProgress
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.PendingImport
import com.eyue.reader.data.ShelfGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendingImportShelfTest {
    @get:Rule val compose = createComposeRule()
    private var books by mutableStateOf(emptyList<Book>())
    private var pending by mutableStateOf<PendingImport?>(null)
    private var query by mutableStateOf("")
    private var openCalls = 0

    @Test fun pendingCoverUpdatesRealProgressAndStageWithoutOpeningReader() {
        showShelf(PendingImport(title = "正在导入.epub", mode = LibraryMode.NOVEL,
            progress = ImportProgress("解析文件", "正在导入.epub", 1, 4, .25f)))
        compose.onNodeWithTag("pending_import_cover").assertIsDisplayed().assertHasNoClickAction()
        compose.onNodeWithTag("pending_import_book").assertHasNoClickAction()
        compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("pending_import_book"))).assertCountEquals(0)
        compose.onNodeWithTag("pending_import_progress").assertRangeInfoEquals(ProgressBarRangeInfo(.25f, 0f..1f))
        compose.onNodeWithText("解析文件").assertExists()
        compose.onNodeWithText("25%").assertExists()
        compose.onNodeWithTag("pending_import_cover").performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, openCalls) }

        compose.runOnIdle {
            pending = pending!!.copy(progress = ImportProgress("缓存内嵌图片", "图片 2", 3, 4, .75f))
        }
        compose.onNodeWithTag("pending_import_progress").assertRangeInfoEquals(ProgressBarRangeInfo(.75f, 0f..1f))
        compose.onNodeWithText("缓存内嵌图片").assertExists()
        compose.onNodeWithText("图片 2").assertExists()
        compose.onNodeWithText("75%").assertExists()
        compose.onNodeWithText("解析文件").assertDoesNotExist()

        compose.runOnIdle { pending = pending!!.copy(progress = ImportProgress("扫描文件夹", "漫画")) }
        compose.onNodeWithTag("pending_import_progress").assertRangeInfoEquals(ProgressBarRangeInfo.Indeterminate)
        compose.onNodeWithText("扫描文件夹").assertExists()
        compose.onNodeWithText("75%").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, openCalls) }
    }

    @Test fun emptyShelfKeepsPendingCardThroughSearchGroupStatusAndBothLayouts() {
        val group = ShelfGroup("收藏", LibraryMode.NOVEL, "favorites")
        showShelf(PendingImport(title = "待导入.txt", mode = LibraryMode.NOVEL), groups = listOf(group))
        compose.onNodeWithTag("pending_import_book").assertIsDisplayed()
        compose.onNodeWithText("准备导入").assertExists()
        compose.onNodeWithText("暂无书籍").assertDoesNotExist()
        compose.onNodeWithTag("shelf_group_all").assert(hasText("全部 0"))
        compose.onNodeWithTag("shelf_group_favorites").performScrollTo().performClick()
        compose.onNodeWithTag("shelf_group_favorites").assertIsSelected().assert(hasText("收藏 0"))
        compose.onNodeWithTag("shelf_search").performTextInput("不存在的书名")
        compose.onNodeWithText("读完").performClick()
        compose.onNodeWithTag("pending_import_book").assertIsDisplayed()
        compose.onNodeWithText("暂无书籍").assertDoesNotExist()
        compose.onNodeWithText("暂时没有匹配的书").assertDoesNotExist()

        compose.onNodeWithContentDescription("切换书架布局").performClick()
        compose.onNodeWithTag("pending_import_book").assertIsDisplayed()
        compose.onNodeWithTag("pending_import_progress").assertRangeInfoEquals(ProgressBarRangeInfo.Indeterminate)
        compose.onNodeWithContentDescription("切换书架布局").performClick()
        compose.onNodeWithTag("pending_import_book").assertIsDisplayed()
        compose.onNodeWithTag("pending_import_cover").performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, openCalls) }
    }

    @Test fun pendingIsFirstButExcludedFromCountsAndDisappearsWhenImportedBookArrives() {
        val group = ShelfGroup("收藏", LibraryMode.NOVEL, "favorites")
        val grouped = book("grouped", "收藏里的书", group.id)
        val loose = book("loose", "未分组的书")
        showShelf(PendingImport(title = "待导入.txt", mode = LibraryMode.NOVEL), listOf(grouped, loose), listOf(group))
        compose.onNodeWithTag("shelf_group_all").assert(hasText("全部 2"))
        compose.onNodeWithTag("shelf_group_ungrouped").assert(hasText("未分组 1"))
        compose.onNodeWithTag("shelf_group_favorites").assert(hasText("收藏 1"))
        val pendingBounds = compose.onNodeWithTag("pending_import_book").fetchSemanticsNode().boundsInRoot
        val realBookBounds = compose.onNodeWithTag("shelf_book_more_grouped").fetchSemanticsNode().boundsInRoot
        assertTrue("导入占位应排在真实书籍之前", pendingBounds.top <= realBookBounds.top && pendingBounds.left < realBookBounds.left)
        compose.onNodeWithTag("shelf_group_favorites").performScrollTo().performClick()
        compose.onNodeWithTag("pending_import_book").assertIsDisplayed()
        compose.onNodeWithTag("shelf_group_all").performScrollTo().performClick()

        val imported = book("imported", "导入完成的书").copy(addedAt = 2)
        compose.runOnIdle {
            books = books + imported
            pending = null
        }
        compose.onNodeWithTag("pending_import_book").assertDoesNotExist()
        compose.onNodeWithTag("pending_import_progress").assertDoesNotExist()
        compose.onNodeWithTag("shelf_group_all").assert(hasText("全部 3"))
        compose.onNodeWithTag("shelf_group_ungrouped").assert(hasText("未分组 2"))
        compose.onNodeWithTag("shelf_group_favorites").assert(hasText("收藏 1"))
        compose.onNodeWithTag("shelf_book_more_imported").assertIsDisplayed()
        compose.onAllNodes(hasText(imported.title) and hasClickAction()).onFirst().performClick()
        compose.runOnIdle { assertEquals(1, openCalls) }
    }

    private fun showShelf(initialPending: PendingImport, initialBooks: List<Book> = emptyList(), groups: List<ShelfGroup> = emptyList()) {
        pending = initialPending
        books = initialBooks
        compose.setContent {
            MaterialTheme {
                Bookshelf(books, query, { query = it }, { openCalls++ }, {}, {}, groups = groups, pending = pending)
            }
        }
        compose.onNodeWithTag("bookshelf").assertIsDisplayed()
    }

    private fun book(id: String, title: String, groupId: String? = null) = Book(id = id, title = title,
        format = BookFormat.TXT, mode = LibraryMode.NOVEL, chapters = listOf(Chapter(title = "第一章")),
        addedAt = 1, groupId = groupId)
}
