package com.eyue.reader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.eyue.reader.data.Book
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.Chapter
import com.eyue.reader.data.LibraryMode
import org.junit.Assert.*
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
class BookshelfSpeechCacheRobolectricTest {
    @get:Rule val compose = createComposeRule()

    @Test fun clearingOneBooksSpeechCacheUsesItsOwnConfirmationAndPreservesTheCloudAction() {
        val cleared = mutableListOf<String>()
        val deletedCloud = mutableListOf<String>()
        showShelf(listOf(book("first"), book("second")), cleared, deletedCloud)
        compose.onNodeWithTag("shelf_book_more_first").performClick()
        compose.onNodeWithTag("shelf_book_clear_ai_speech_first").performClick()
        compose.onNodeWithTag("shelf_clear_ai_speech_dialog").assertIsDisplayed()
        compose.onNodeWithTag("shelf_clear_ai_speech_cancel").performClick()
        compose.runOnIdle { assertTrue(cleared.isEmpty()) }
        compose.onNodeWithTag("shelf_book_more_first").performClick()
        compose.onNodeWithTag("shelf_book_clear_ai_speech_first").performClick()
        compose.onNodeWithTag("shelf_clear_ai_speech_confirm").performClick()
        compose.runOnIdle { assertEquals(listOf("first"), cleared); assertTrue(deletedCloud.isEmpty()) }
        compose.onNodeWithTag("shelf_book_more_first").performClick()
        compose.onNodeWithTag("shelf_book_delete_cloud_first").assertIsDisplayed()
    }

    @Test fun listViewAlsoClearsTheChosenBooksCacheAndCloudOnlyBooksHaveNoLocalClearAction() {
        val cleared = mutableListOf<String>()
        showShelf(listOf(book("local"), book("remote").copy(cloudOnly = true)), cleared, mutableListOf())
        compose.onNodeWithContentDescription("切换书架布局").performClick()
        compose.onNodeWithTag("shelf_book_more_local").performClick()
        compose.onNodeWithTag("shelf_book_clear_ai_speech_local").performClick()
        compose.onNodeWithTag("shelf_clear_ai_speech_confirm").performClick()
        compose.runOnIdle { assertEquals(listOf("local"), cleared) }
        compose.onNodeWithTag("shelf_book_more_remote").performClick()
        compose.onNodeWithTag("shelf_book_clear_ai_speech_remote").assertDoesNotExist()
    }

    @Test fun comicsHaveNoSpeechCacheActionInGridOrListButKeepCloudDeletion() {
        showShelf(listOf(book("comic").copy(mode = LibraryMode.COMIC, format = BookFormat.IMAGES)),
            mutableListOf(), mutableListOf())
        compose.onNodeWithTag("shelf_book_more_comic").performClick()
        compose.onNodeWithTag("shelf_book_clear_ai_speech_comic").assertDoesNotExist()
        compose.onNodeWithTag("shelf_book_delete_cloud_comic").assertIsDisplayed().performClick()
        compose.onNodeWithTag("shelf_delete_cloud_cancel").performClick()
        compose.onNodeWithContentDescription("切换书架布局").performClick()
        compose.onNodeWithTag("shelf_book_more_comic").performClick()
        compose.onNodeWithTag("shelf_book_clear_ai_speech_comic").assertDoesNotExist()
        compose.onNodeWithTag("shelf_book_delete_cloud_comic").assertIsDisplayed()
    }

    private fun showShelf(books: List<Book>, cleared: MutableList<String>, cloud: MutableList<String>) {
        compose.setContent { MaterialTheme { Bookshelf(books, "", {}, {}, {}, {},
            onDeleteCloud = { cloud += it.id }, onClearAiSpeechCache = { cleared += it.id }) } }
    }

    private fun book(id: String) = Book(id = id, title = "缓存 $id", format = BookFormat.TXT, mode = LibraryMode.NOVEL,
        chapters = listOf(Chapter(title = "第一章")), cloudEnabled = true)
}
