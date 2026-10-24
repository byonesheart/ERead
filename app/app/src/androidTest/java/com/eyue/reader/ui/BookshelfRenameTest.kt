package com.eyue.reader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eyue.reader.data.Book
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.Chapter
import com.eyue.reader.data.LibraryMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookshelfRenameTest {
    @get:Rule val compose = createComposeRule()
    private val book = Book(id = "rename", title = "原书名", format = BookFormat.MOBI,
        mode = LibraryMode.NOVEL, chapters = listOf(Chapter(title = "正文")))

    @Test fun longPressingAGridTitleRenamesOnlyOnSaveAndDoesNotOpenTheReader() {
        var books by mutableStateOf(listOf(book))
        var opens = 0
        var renames = 0
        compose.setContent { MaterialTheme { Bookshelf(books, "", {}, { opens++ }, {}, {},
            onRenameBook = { id, title ->
                renames++; books = books.map { if (it.id == id) it.copy(title = title) else it }; true
            }) } }
        compose.onNodeWithTag("shelf_book_title_rename").performTouchInput { longClick() }
        compose.onNodeWithTag("shelf_rename_dialog").assertIsDisplayed()
        compose.onNodeWithTag("shelf_rename_title").performTextReplacement("   ")
        compose.onNodeWithTag("shelf_rename_save").assertIsNotEnabled()
        compose.onNodeWithTag("shelf_rename_title").performTextReplacement(" 新书名 ")
        compose.onNodeWithTag("shelf_rename_save").performClick()
        compose.onNodeWithTag("shelf_rename_dialog").assertDoesNotExist()
        compose.onNodeWithTag("shelf_book_title_rename").assertTextEquals("新书名")
        compose.runOnIdle { assertEquals(0, opens); assertEquals(1, renames) }
        compose.onNodeWithTag("shelf_book_title_rename").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, opens) }
    }

    @Test fun listAndUndownloadedBookTitlesCanBeRenamedAndCancelDoesNotSave() {
        var selected: Pair<String, String>? = null
        var opens = 0
        compose.setContent { MaterialTheme { Bookshelf(listOf(book.copy(cloudOnly = true)), "", {}, { opens++ }, {}, {},
            onRenameBook = { id, title -> selected = id to title; true }) } }
        compose.onNodeWithContentDescription("切换书架布局").performClick()
        compose.onNodeWithTag("shelf_book_title_rename").performTouchInput { longClick() }
        compose.onNodeWithTag("shelf_rename_title").performTextReplacement("不保存")
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertNull(selected); assertEquals(0, opens) }
        compose.onNodeWithTag("shelf_book_title_rename").performTouchInput { longClick() }
        compose.onNodeWithTag("shelf_rename_title").performTextReplacement("云端新书名")
        compose.onNodeWithTag("shelf_rename_save").performClick()
        compose.runOnIdle { assertEquals(book.id to "云端新书名", selected); assertEquals(0, opens) }
    }
}
