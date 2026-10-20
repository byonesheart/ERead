package com.eyue.reader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.eyue.reader.data.BookSaveProgress
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
class ReaderDraftControlsRobolectricTest {
    @get:Rule val compose = createComposeRule()

    @Test fun savingShowsItsProgressBesideTheDisabledSaveButton() {
        var saves = 0
        compose.setContent {
            MaterialTheme {
                ReaderDraftSaveAction(BookSaveProgress(stage = "写入源文件", fraction = .42f), busy = true, onSave = { saves++ })
            }
        }
        compose.onNodeWithTag("reader_save_book").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("reader_save_progress").assertIsDisplayed()
        compose.onNodeWithContentDescription("写入源文件").assertIsDisplayed()
        compose.onNodeWithText("42%").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, saves) }
    }

    @Test fun savingConfirmationDisablesLeavingDiscardingAndEditing() {
        var actions = 0
        compose.setContent {
            MaterialTheme {
                ReaderUnsavedChangesDialog(listOf("手动保存"), saving = true,
                    onSave = { actions++ }, onDiscard = { actions++ }, onCancel = { actions++ })
            }
        }
        compose.onNodeWithTag("reader_unsaved_dialog").assertIsDisplayed()
        compose.onNodeWithText("正在保存…").assertIsDisplayed()
        compose.onNodeWithTag("reader_unsaved_save").assertIsNotEnabled()
        compose.onNodeWithTag("reader_unsaved_discard").assertIsNotEnabled()
        compose.onNodeWithTag("reader_unsaved_cancel").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, actions) }
    }
}
