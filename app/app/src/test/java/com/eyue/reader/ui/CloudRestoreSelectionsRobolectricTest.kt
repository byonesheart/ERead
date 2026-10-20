package com.eyue.reader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.eyue.reader.data.*
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
class CloudRestoreSelectionsRobolectricTest {
    @get:Rule val compose = createComposeRule()
    private val first = Book(id = "first", title = "同名书", format = BookFormat.TXT, mode = LibraryMode.NOVEL, chapters = emptyList())
    private val second = first.copy(id = "second", format = BookFormat.EPUB)
    private val remote = first.copy(id = "remote", format = BookFormat.MOBI)
    private val conflict = CloudRestoreConflict(first, remote, listOf(first, second))

    @Test fun uploadRetainingBothStillRequiresMeteredConsentAndShowsOriginalFileIdentity() {
        val local = first.copy(sourceFileName = "本地文件.txt", sourceFileBytes = 1024)
        val cloud = remote.copy(sourceFileName = "云端文件.mobi", sourceFileBytes = 2048)
        var saved: Map<String, CloudRestoreSelection>? = null
        var allowed = false
        compose.setContent { MaterialTheme { CloudRestoreDialog(listOf(CloudRestoreConflict(local, cloud)), true, false,
            {}, { choices, consent -> saved = choices; allowed = consent }, uploading = true) } }
        compose.onNodeWithText("确认上传").assertIsDisplayed()
        compose.onNodeWithText("确认还原").assertDoesNotExist()
        compose.onNodeWithText("本地文件.txt").assertIsDisplayed()
        compose.onNodeWithText("云端文件.mobi").assertIsDisplayed()
        compose.onNodeWithTag("cloud_restore_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("cloud_restore_metered").performClick()
        compose.onNodeWithTag("cloud_restore_confirm").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertTrue(allowed)
            assertEquals(CloudRestoreSelection(setOf(local.id), true), saved?.get(cloud.id))
        }
    }

    @Test fun uploadDecisionCannotBeSubmittedTwiceWhileTheCloudOperationRuns() {
        var calls = 0
        compose.setContent { MaterialTheme { CloudRestoreDialog(listOf(conflict), false, true, {}, { _, _ -> calls++ }, uploading = true) } }
        compose.onNodeWithTag("cloud_restore_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("cloud_restore_local_remote_first").assertIsNotEnabled()
        compose.onNodeWithText("取消").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, calls) }
    }

    @Test fun multipleCandidatesCanBeCheckedAsASubsetWithoutRequiringMeteredCloudUpdates() {
        var saved: Map<String, CloudRestoreSelection>? = null
        compose.setContent { MaterialTheme { CloudRestoreDialog(listOf(conflict), true, false, {}, { choices, _ -> saved = choices }) } }
        compose.onNodeWithTag("cloud_restore_local_remote_first").assertIsOn().performClick().assertIsOff()
        compose.onNodeWithTag("cloud_restore_local_remote_second").assertIsOn()
        compose.onNodeWithTag("cloud_restore_remote_remote").assertIsOn()
        compose.onNodeWithTag("cloud_restore_confirm").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(CloudRestoreSelection(setOf(second.id), true), saved?.get(remote.id)) }
    }

    @Test fun everyGroupRequiresAtLeastOneBookAndSingleRetentionRequiresMeteredConsent() {
        compose.setContent { MaterialTheme { CloudRestoreDialog(listOf(conflict), true, false, {}, { _, _ -> }) } }
        compose.onNodeWithTag("cloud_restore_local_remote_first").performClick()
        compose.onNodeWithTag("cloud_restore_local_remote_second").performClick()
        compose.onNodeWithTag("cloud_restore_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("cloud_restore_remote_remote").performClick()
        compose.onNodeWithTag("cloud_restore_selection_error").assertIsDisplayed()
        compose.onNodeWithTag("cloud_restore_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("cloud_restore_local_remote_second").performClick()
        compose.onNodeWithText("允许使用当前计费网络更新备份").assertIsDisplayed()
        compose.onNodeWithTag("cloud_restore_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("cloud_restore_metered").performClick()
        compose.onNodeWithTag("cloud_restore_confirm").assertIsEnabled()
    }

    @Test fun sharedLocalCandidateCheckboxesStayInSyncAcrossSameTitleGroups() {
        val other = remote.copy(id = "other")
        compose.setContent { MaterialTheme { CloudRestoreDialog(listOf(conflict, CloudRestoreConflict(first, other, listOf(first, second))), false, false, {}, { _, _ -> }) } }
        compose.onNodeWithTag("cloud_restore_local_remote_first").performScrollTo().performClick()
        compose.onNodeWithTag("cloud_restore_local_other_first").performScrollTo().assertIsOff()
        compose.onNodeWithTag("cloud_restore_local_other_first").performClick()
        compose.onNodeWithTag("cloud_restore_local_remote_first").performScrollTo().assertIsOn()
    }
}
