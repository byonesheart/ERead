package com.eyue.reader.ui

import android.content.Intent
import android.os.Environment
import android.provider.DocumentsContract
import com.eyue.reader.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class BookLocationRobolectricTest {
    @Test fun publicFileLocationsUseSystemDocumentsUiAndNeverExposePrivateFileUris() {
        val context = RuntimeEnvironment.getApplication()
        val book = Book(title = "位置", format = BookFormat.EPUB, mode = LibraryMode.NOVEL, chapters = emptyList(),
            sourcePath = File(Environment.getExternalStorageDirectory(), "Books/位置.epub").absolutePath)
        val intent = requireNotNull(bookFileManagerIntent(context, book))
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        val initial = intent.getParcelableExtra<android.net.Uri>(DocumentsContract.EXTRA_INITIAL_URI)!!
        assertEquals("content", initial.scheme)
        assertEquals("primary:Books/位置.epub", DocumentsContract.getDocumentId(initial))
        assertNull(bookFileManagerIntent(context, book.copy(sourcePath = File(context.filesDir, "books/private.epub").absolutePath)))
        assertNull(bookFileManagerIntent(context, book.copy(cloudOnly = true)))
    }
}
