package com.eyue.reader.ui

import android.text.format.Formatter
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.eyue.reader.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CleanupDialogRobolectricTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cloudCleanupDefaultsOffAndMeteredPermissionIsRequiredOnlyAfterSelectingCloud() {
        var confirmed: Boolean? = null
        compose.setContent { MaterialTheme { CleanupDialog("清空笔记？", "保留书签", 4096, true, true,
            "同步清空云端笔记", { confirmed = it }, {}, "cleanup") } }
        compose.onNodeWithTag("cleanup_cloud").assertIsOff()
        compose.onNodeWithTag("cleanup_confirm").assertIsEnabled()
        compose.onNodeWithTag("cleanup_metered").assertDoesNotExist()
        compose.onNodeWithTag("cleanup_space").assertTextEquals("本地占用：" + Formatter.formatShortFileSize(RuntimeEnvironment.getApplication(), 4096))
        compose.onNodeWithTag("cleanup_cloud").performClick()
        compose.onNodeWithTag("cleanup_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("cleanup_metered").performScrollTo().performClick()
        compose.onNodeWithTag("cleanup_confirm").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(true, confirmed) }
        compose.onNodeWithTag("cleanup_cloud").performScrollTo().performClick()
        compose.onNodeWithTag("cleanup_confirm").performClick()
        compose.runOnIdle { assertEquals(false, confirmed) }
        compose.onNodeWithTag("cleanup_cloud").performScrollTo().performClick()
        compose.onNodeWithTag("cleanup_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("cleanup_metered").assertIsOff()
    }

    @Test fun missingCloudConfigurationDisablesCloudAndEachNewCleanupStartsUnchecked() {
        var tag by mutableStateOf("first")
        var configured by mutableStateOf(false)
        compose.setContent { MaterialTheme { CleanupDialog("清理？", "本地数据", 0, configured, false,
            "云端对应数据", {}, {}, tag) } }
        compose.onNodeWithTag("first_cloud").assertIsNotEnabled().assertIsOff()
        compose.runOnIdle { configured = true }
        compose.onNodeWithTag("first_cloud").performClick().assertIsOn()
        compose.runOnIdle { tag = "second" }
        compose.onNodeWithTag("second_cloud").assertIsOff()
        compose.onNodeWithText("仅清本地时，本次不更新云端；后续备份仍按本地记录更新。").assertExists()
    }

    @Test fun settingsShowAllFourMeasuredSizesAndDispatchTheirOwnCleanupWithoutCloudByDefault() {
        val context = RuntimeEnvironment.getApplication()
        val state = LibraryState()
        val usage = runBlocking { measureLibraryCleanup(context, state) }
        val calls = mutableListOf<Pair<LibraryCleanupKind, Boolean>>()
        compose.setContent { MaterialTheme { SettingsScreen(state, {}, {}, {}, {}, cloudConfigured = true,
            onCleanup = { kind, cloud -> calls += kind to cloud }) } }
        compose.onNodeWithTag("settings_cache").performClick()
        val tags = listOf(LibraryCleanupKind.HISTORY to "history", LibraryCleanupKind.AI_AUDIO to "ai_speech",
            LibraryCleanupKind.ANNOTATIONS to "annotations", LibraryCleanupKind.ROLES to "roles")
        val expected = Formatter.formatShortFileSize(context, usage.historyBytes)
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag("settings_clear_history") and hasText(expected, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        tags.forEach { (kind, suffix) ->
            compose.onNodeWithTag("settings_clear_$suffix").performScrollTo()
                .assertTextContains(Formatter.formatShortFileSize(context, usage.bytes(kind)), substring = true).performClick()
            compose.onNodeWithTag("settings_clear_${suffix}_cloud").assertIsOff()
            if (kind == LibraryCleanupKind.AI_AUDIO) compose.onNodeWithText("云端 AI 音频占用：0 B。AI 音频仅保存在本机，勾选也不会删除云端书籍或阅读记录。").assertExists()
            compose.onNodeWithTag("settings_clear_${suffix}_confirm").performClick()
        }
        compose.runOnIdle { assertEquals(tags.map { it.first to false }, calls) }
    }

    @Test fun bookshelfRemovalAndSpeechCleanupUseTheirOwnCloudAndMeteredConfirmations() {
        val book = Book(id = "cleanup-book", title = "清理测试书", format = BookFormat.TXT, mode = LibraryMode.NOVEL,
            chapters = listOf(Chapter(title = "正文")))
        val removed = mutableListOf<Pair<String, Boolean>>()
        val audio = mutableListOf<Pair<String, Boolean>>()
        var legacyRemovals = 0
        compose.setContent { MaterialTheme { Bookshelf(listOf(book), "", {}, {}, { legacyRemovals++ }, {},
            onRemoveWithCloud = { value, cloud -> removed += value.id to cloud },
            onClearSpeechWithCloud = { value, cloud -> audio += value.id to cloud }, cloudConfigured = true, meteredNetwork = true) } }
        compose.onNodeWithTag("shelf_book_more_cleanup-book").performClick()
        compose.onNodeWithText("移出书架").performClick()
        compose.onNodeWithTag("shelf_remove_book_cloud").assertIsOff().performClick()
        compose.onNodeWithTag("shelf_remove_book_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("shelf_remove_book_metered").performScrollTo().performClick()
        compose.onNodeWithTag("shelf_remove_book_confirm").performClick()
        compose.runOnIdle { assertEquals(listOf(book.id to true), removed); assertEquals(0, legacyRemovals) }
        compose.onNodeWithTag("shelf_book_more_cleanup-book").performClick()
        compose.onNodeWithTag("shelf_book_clear_ai_speech_cleanup-book").performClick()
        compose.onNodeWithTag("shelf_clear_ai_speech_cloud").assertIsOff()
        compose.onNodeWithTag("shelf_clear_ai_speech_confirm").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(book.id to false), audio); assertEquals(1, removed.size) }
    }
}
