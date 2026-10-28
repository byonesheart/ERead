package com.eyue.reader.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.eyue.reader.data.Book
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.LibraryMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class BookLocationDialogRobolectricTest {
    @get:Rule val compose = createComposeRule()

    @Test fun locationCanStillBeCopiedWithoutAnyFileManagerAction() {
        val book = Book(title = "位置", format = BookFormat.EPUB, mode = LibraryMode.NOVEL, chapters = emptyList(),
            sourcePath = "/storage/emulated/0/Books/测试.epub")
        var closed = false
        compose.setContent { MaterialTheme { BookLocationDialog(book) { closed = true } } }
        compose.onNodeWithText("文件管理器定位").assertDoesNotExist()
        compose.onNodeWithTag("book_location_copy").performClick()
        val context = RuntimeEnvironment.getApplication()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(book.sourcePath, clipboard.primaryClip!!.getItemAt(0).text.toString())
        compose.onNodeWithText("已复制").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        assertTrue(closed)
    }
}
