package com.eyue.reader.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eyue.reader.data.Book
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.Chapter
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.ShelfGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookshelfGroupsTest {
    @get:Rule val compose = createComposeRule()
    private var books by mutableStateOf(emptyList<Book>())
    private var groups by mutableStateOf(emptyList<ShelfGroup>())
    private var query by mutableStateOf("")
    private var mode by mutableStateOf(LibraryMode.NOVEL)
    private val created = mutableListOf<Pair<LibraryMode, String>>()
    private val renamed = mutableListOf<Pair<String, String>>()
    private val deleted = mutableListOf<String>()
    private val moved = mutableListOf<Pair<String, String?>>()
    private var opened: String? = null

    @Test fun createRenameAndDeleteGroupCallTheirCallbacksWithoutRemovingBooks() {
        showShelf(listOf(book("kept", "保留的书")))
        openGroupManager()
        compose.onNodeWithTag("shelf_create_group").performClick()
        compose.onNodeWithTag("shelf_group_name_input").performTextInput("旅行")
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle { assertEquals(listOf(LibraryMode.NOVEL to "旅行"), created) }
        compose.onNodeWithText("关闭").performClick()
        openMoveDialog("kept")
        compose.onNodeWithTag("shelf_move_created-0").performClick()
        selectGroup("created-0")
        assertBookPresent("kept")

        openGroupManager()
        compose.onNodeWithTag("shelf_group_rename_created-0").performClick()
        compose.onNodeWithTag("shelf_group_name_input").performTextReplacement("远方")
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle { assertEquals(listOf("created-0" to "远方"), renamed) }

        openGroupManager()
        compose.onNodeWithTag("shelf_group_delete_created-0").performClick()
        compose.onNodeWithText("删除分组《远方》？").assertIsDisplayed()
        compose.onNodeWithText("删除分组").performClick()
        compose.runOnIdle {
            assertEquals(listOf("created-0"), deleted)
            assertEquals(listOf("kept"), books.map { it.id })
            assertEquals(null, books.single().groupId)
            assertEquals(emptyList<ShelfGroup>(), groups)
        }
        compose.onNodeWithTag("shelf_group_delete_created-0").assertDoesNotExist()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithTag("shelf_group_all").assertIsSelected()
        assertBookPresent("kept")
    }

    @Test fun groupFilterAndSearchCombineAndCanReturnToUngroupedAndAllBooks() {
        val novels = ShelfGroup("小说收藏", LibraryMode.NOVEL, "novels")
        val essays = ShelfGroup("散文", LibraryMode.NOVEL, "essays")
        showShelf(listOf(
            book("star-novel", "星河漫游", "甲作者", novels.id),
            book("mountain", "山间来信", "乙作者", novels.id),
            book("star-essay", "星河随笔", "甲作者", essays.id),
            book("loose", "未分类日记")
        ), listOf(novels, essays))
        selectGroup("novels")
        compose.onNodeWithTag("shelf_group_novels").assertIsSelected()
        assertBookPresent("star-novel")
        assertBookPresent("mountain")
        assertBookAbsent("star-essay")
        assertBookAbsent("loose")

        compose.onNodeWithTag("shelf_search").performTextInput("星河")
        assertBookPresent("star-novel")
        assertBookAbsent("mountain")
        assertBookAbsent("star-essay")
        compose.onNodeWithTag("shelf_search").performTextReplacement("乙作者")
        assertBookAbsent("star-novel")
        assertBookPresent("mountain")
        compose.onAllNodes(hasText("山间来信") and hasClickAction()).onFirst().performClick()
        compose.runOnIdle { assertEquals("mountain", opened) }

        compose.onNodeWithTag("shelf_search").performTextClearance()
        compose.onNodeWithTag("shelf_group_ungrouped").performScrollTo().performClick()
        compose.onNodeWithTag("shelf_group_ungrouped").assertIsSelected()
        assertBookPresent("loose")
        assertBookAbsent("star-novel")
        compose.onNodeWithTag("shelf_group_all").performScrollTo().performClick()
        compose.onNodeWithTag("shelf_group_all").assertIsSelected()
        assertBookPresent("star-novel")
        assertBookPresent("mountain")
    }

    @Test fun movingBookIntoGroupAndBackToUngroupedImmediatelyUpdatesSelectedGroup() {
        val group = ShelfGroup("稍后阅读", LibraryMode.NOVEL, "later")
        showShelf(listOf(book("move", "待归类的书")), listOf(group))
        openMoveDialog("move")
        compose.onNodeWithTag("shelf_move_later").performClick()
        compose.onNodeWithTag("shelf_move_dialog").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("move" to "later"), moved) }

        selectGroup("later")
        assertBookPresent("move")
        openMoveDialog("move")
        compose.onNodeWithTag("shelf_move_ungrouped").performClick()
        compose.onNodeWithTag("shelf_move_dialog").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("move" to "later", "move" to null), moved) }
        assertBookAbsent("move")
        compose.onNodeWithTag("shelf_group_ungrouped").performScrollTo().performClick()
        assertBookPresent("move")
    }

    @Test fun switchingModesKeepsSameNamedGroupsAndMoveChoicesIsolated() {
        val novelGroup = ShelfGroup("收藏", LibraryMode.NOVEL, "novel-favorites")
        val comicGroup = ShelfGroup("收藏", LibraryMode.COMIC, "comic-favorites")
        showShelf(listOf(
            book("novel", "小说收藏", groupId = novelGroup.id),
            book("comic", "漫画收藏", groupId = comicGroup.id, mode = LibraryMode.COMIC)
        ), listOf(novelGroup, comicGroup), switchable = true)
        selectGroup(novelGroup.id)
        assertBookPresent("novel")
        assertBookAbsent("comic")
        compose.onNodeWithTag("shelf_group_comic-favorites").assertDoesNotExist()

        compose.onNodeWithTag("test_mode_comic").performClick()
        compose.onNodeWithTag("shelf_group_novel-favorites").assertDoesNotExist()
        selectGroup(comicGroup.id)
        assertBookPresent("comic")
        assertBookAbsent("novel")
        openMoveDialog("comic")
        compose.onNodeWithTag("shelf_move_comic-favorites").assertIsDisplayed()
        compose.onNodeWithTag("shelf_move_novel-favorites").assertDoesNotExist()
        compose.onNodeWithTag("shelf_move_ungrouped").performClick()
        compose.runOnIdle {
            assertEquals(listOf("comic" to null), moved)
            assertEquals(novelGroup.id, books.single { it.id == "novel" }.groupId)
        }
        compose.onNodeWithTag("shelf_group_ungrouped").performScrollTo().performClick()
        assertBookPresent("comic")

        compose.onNodeWithTag("test_mode_novel").performClick()
        selectGroup(novelGroup.id)
        assertBookPresent("novel")
        assertBookAbsent("comic")
        compose.onNodeWithTag("shelf_group_comic-favorites").assertDoesNotExist()
    }

    @Test fun readingHomeKeepsClickableHistoryWithoutDecorativeSlogans() {
        val older = book("older", "较早读过的书").copy(lastReadAt = 1_000)
        val latest = book("latest", "最近读过的书").copy(lastReadAt = 2_000)
        val unread = book("unread", "尚未读过的书")
        compose.setContent {
            MaterialTheme {
                ReadingHome(listOf(older, unread, latest), 3_600, onOpen = { opened = it.id }, onShelf = {}, onImport = {})
            }
        }
        compose.onNodeWithTag("reading_home").assertIsDisplayed()
        compose.onNodeWithText("今天，也读一点。").assertDoesNotExist()
        compose.onNodeWithText("  继续上次的故事").assertDoesNotExist()
        val latestRow = compose.onNode(hasText(latest.title) and hasClickAction())
        val olderRow = compose.onNode(hasText(older.title) and hasClickAction())
        compose.onNodeWithTag("reading_home").performScrollToNode(hasText(latest.title) and hasClickAction())
        latestRow.assertIsDisplayed()
        compose.onNodeWithTag("reading_home").performScrollToNode(hasText(older.title) and hasClickAction())
        olderRow.assertIsDisplayed()
        assertTrue(latestRow.fetchSemanticsNode().boundsInRoot.top < olderRow.fetchSemanticsNode().boundsInRoot.top)
        compose.onNodeWithText(unread.title).assertDoesNotExist()
        compose.onNodeWithText("  不必匆忙，故事一直都在。").assertDoesNotExist()
        compose.onNodeWithTag("reading_home").performScrollToNode(hasText(latest.title) and hasClickAction())
        latestRow.performClick()
        compose.runOnIdle { assertEquals(latest.id, opened) }
    }

    @Test fun topImportAndNotebookEntriesDispatchIndependentlyAndRespectImportEnabledState() {
        var selectedMode by mutableStateOf(LibraryMode.NOVEL)
        var importEnabled by mutableStateOf(true)
        val modeChanges = mutableListOf<LibraryMode>()
        var notesOpened = 0
        var importsOpened = 0
        compose.setContent {
            MaterialTheme {
                ModeTabs(selectedMode, onMode = { selectedMode = it; modeChanges += it }, onNotes = { notesOpened++ },
                    onImport = { importsOpened++ }, importEnabled = importEnabled)
            }
        }
        compose.onNodeWithTag("mode_tabs").assertIsDisplayed()
        val comicLabel = compose.onNodeWithText("漫画")
        val importButton = compose.onNodeWithTag("top_import")
        val notebook = compose.onNodeWithTag("notebook_entry")
        comicLabel.assertIsDisplayed()
        importButton.assertIsDisplayed().assertIsEnabled()
        notebook.assertIsDisplayed()
        assertTrue("导入入口应在漫画标签右侧",
            importButton.fetchSemanticsNode().boundsInRoot.left > comicLabel.fetchSemanticsNode().boundsInRoot.right)
        assertTrue("笔记入口应在导入入口右侧",
            notebook.fetchSemanticsNode().boundsInRoot.left >= importButton.fetchSemanticsNode().boundsInRoot.right)
        notebook.performClick()
        compose.runOnIdle {
            assertEquals(1, notesOpened)
            assertEquals(emptyList<LibraryMode>(), modeChanges)
        }
        importButton.performClick()
        compose.runOnIdle {
            assertEquals(1, importsOpened)
            assertEquals(1, notesOpened)
            assertEquals(emptyList<LibraryMode>(), modeChanges)
            importEnabled = false
        }
        importButton.assertIsNotEnabled().performTouchInput { click() }
        comicLabel.performClick()
        compose.runOnIdle {
            assertEquals(listOf(LibraryMode.COMIC), modeChanges)
            assertEquals(LibraryMode.COMIC, selectedMode)
            assertEquals(1, notesOpened)
            assertEquals(1, importsOpened)
        }
    }

    private fun showShelf(initialBooks: List<Book>, initialGroups: List<ShelfGroup> = emptyList(), switchable: Boolean = false) {
        books = initialBooks
        groups = initialGroups
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize()) {
                    if (switchable) Row {
                        TextButton(onClick = { mode = LibraryMode.NOVEL; query = "" }, modifier = Modifier.testTag("test_mode_novel")) { Text("测试小说") }
                        TextButton(onClick = { mode = LibraryMode.COMIC; query = "" }, modifier = Modifier.testTag("test_mode_comic")) { Text("测试漫画") }
                    }
                    key(mode) {
                        Bookshelf(books.filter { it.mode == mode }, query, { query = it }, { opened = it.id }, {}, {},
                            groups = groups.filter { it.mode == mode },
                            onCreateGroup = { name ->
                                val id = "created-${created.size}"
                                created += mode to name
                                groups = groups + ShelfGroup(name, mode, id)
                            },
                            onRenameGroup = { id, name ->
                                renamed += id to name
                                groups = groups.map { if (it.id == id) it.copy(name = name) else it }
                            },
                            onDeleteGroup = { id ->
                                deleted += id
                                groups = groups.filterNot { it.id == id }
                                books = books.map { if (it.groupId == id) it.copy(groupId = null) else it }
                            },
                            onMoveBook = { id, groupId ->
                                moved += id to groupId
                                books = books.map { if (it.id == id) it.copy(groupId = groupId) else it }
                            })
                    }
                }
            }
        }
        compose.onNodeWithTag("bookshelf").assertIsDisplayed()
    }

    private fun openGroupManager() {
        if (compose.onAllNodesWithTag("shelf_groups_dialog").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("shelf_manage_groups").performClick()
        }
        compose.onNodeWithTag("shelf_groups_dialog").assertIsDisplayed()
    }

    private fun selectGroup(id: String) {
        compose.onNodeWithTag("shelf_group_$id").performScrollTo().performClick()
    }

    private fun openMoveDialog(id: String) {
        compose.onNodeWithTag("shelf_book_more_$id").performClick()
        compose.onNodeWithText("移动分组").performClick()
        compose.onNodeWithTag("shelf_move_dialog").assertIsDisplayed()
    }

    private fun assertBookPresent(id: String) = compose.onNodeWithTag("shelf_book_more_$id").assertIsDisplayed()
    private fun assertBookAbsent(id: String) = compose.onNodeWithTag("shelf_book_more_$id").assertDoesNotExist()

    private fun book(id: String, title: String, author: String = "", groupId: String? = null,
                     mode: LibraryMode = LibraryMode.NOVEL) = Book(id = id, title = title, author = author,
        format = if (mode == LibraryMode.NOVEL) BookFormat.TXT else BookFormat.IMAGES, mode = mode,
        chapters = listOf(Chapter(id = "$id-chapter", title = "第一章")), addedAt = 1, groupId = groupId)
}
