package com.eyue.reader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.eyue.reader.data.Book
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.ImportBookConflict
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
@Config(sdk = [28], qualifiers = "w360dp-h800dp")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImportBookConflictDialogRobolectricTest {
    @get:Rule val compose = createComposeRule()
    private val first = Book(id = "first", title = "同名书", format = BookFormat.TXT,
        mode = LibraryMode.NOVEL, chapters = emptyList())
    private val second = first.copy(id = "second", format = BookFormat.EPUB)
    private val incoming = first.copy(id = "incoming", format = BookFormat.MOBI)
    private val conflict = ImportBookConflict(incoming, listOf(first, second))

    @Test fun everyExistingAndIncomingCopyIsRetainedByDefault() {
        var selected: Set<String>? = null
        compose.setContent { MaterialTheme { ImportBookConflictDialog(conflict) { selected = it } } }
        listOf(first, second, incoming).forEach { book ->
            compose.onNodeWithTag("import_keep_${book.id}").assertIsOn()
        }
        compose.onNodeWithTag("import_conflict_confirm").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(setOf(first.id, second.id, incoming.id), selected) }
    }

    @Test fun anEmptySelectionCannotBeConfirmed() {
        var calls = 0
        compose.setContent { MaterialTheme { ImportBookConflictDialog(conflict) { calls++ } } }
        listOf(first, second, incoming).forEach { book ->
            compose.onNodeWithTag("import_keep_${book.id}").performClick().assertIsOff()
        }
        compose.onNodeWithText("至少保留一本").assertIsDisplayed()
        compose.onNodeWithTag("import_conflict_confirm").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, calls) }
    }

    @Test fun retainingAnExistingCopyAndTheIncomingCopyReportsOnlyTheirIds() {
        var selected: Set<String>? = null
        compose.setContent { MaterialTheme { ImportBookConflictDialog(conflict) { selected = it } } }
        compose.onNodeWithTag("import_keep_first").performClick().assertIsOff()
        compose.onNodeWithTag("import_keep_second").assertIsOn()
        compose.onNodeWithTag("import_keep_incoming").assertIsOn()
        compose.onNodeWithTag("import_conflict_confirm").performClick()
        compose.runOnIdle { assertEquals(setOf(second.id, incoming.id), selected) }
    }

    @Test fun skippingDiscardsTheIncomingCopyAndKeepsAllExistingCopies() {
        var selected: Set<String>? = null
        compose.setContent { MaterialTheme { ImportBookConflictDialog(conflict) { selected = it } } }
        compose.onNodeWithTag("import_keep_first").performClick().assertIsOff()
        compose.onNodeWithTag("import_conflict_skip").performClick()
        compose.runOnIdle { assertEquals(setOf(first.id, second.id), selected) }
    }
}
