package com.eyue.reader.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.eyue.reader.data.Book
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.LibraryMode
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
class CloudBookCoverUiRobolectricTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cloudBookHasNoPendingLabelAndTappingItDispatchesTheOpenCheck() {
        val book = Book(id = "remote-grid", title = "云端示例", format = BookFormat.EPUB,
            mode = LibraryMode.NOVEL, author = "  ", cloudOnly = true, chapters = emptyList())
        var opened = 0
        compose.setContent { MaterialTheme { Bookshelf(listOf(book), "", {}, { opened++ }, {}, {}, onCloud = {}) } }
        compose.onNodeWithTag("shelf_book_author_remote-grid").assertTextEquals("佚名")
        compose.onNodeWithTag("cloud_book_pending_remote-grid", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("shelf_book_title_remote-grid").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
        compose.onNodeWithText("云端书籍 · 点击云图标下载").assertDoesNotExist()
    }

    @Test fun cloudCoverLeavesTheDownloadActionAvailableAndClearsAfterDownload() {
        val book = mutableStateOf(Book(id = "remote", title = "待下载", format = BookFormat.EPUB,
            mode = LibraryMode.NOVEL, cloudOnly = true, chapters = emptyList()))
        var downloads = 0
        compose.setContent {
            MaterialTheme { BookCover(book.value, Modifier.width(120.dp).height(180.dp), onCloud = { downloads++ }) }
        }
        compose.onNodeWithTag("cloud_book_cover_remote").assertIsDisplayed()
        compose.onNodeWithTag("cloud_book_pending_remote").assertDoesNotExist()
        compose.onNodeWithTag("cloud_book_remote").assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, downloads)
            book.value = book.value.copy(cloudOnly = false)
        }
        compose.onNodeWithTag("cloud_book_cover_remote").assertDoesNotExist()
        compose.onNodeWithTag("cloud_book_pending_remote").assertDoesNotExist()
        compose.onNodeWithTag("cloud_book_remote").assertIsDisplayed().assertIsEnabled()
    }
}
