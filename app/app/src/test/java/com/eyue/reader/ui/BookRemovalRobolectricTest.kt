package com.eyue.reader.ui

import android.text.format.Formatter
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.eyue.reader.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookRemovalRobolectricTest {
    @get:Rule val compose = createComposeRule()
    private fun book() = Book(id = UUID.randomUUID().toString(), title = "删除测试", format = BookFormat.TXT,
        mode = LibraryMode.NOVEL, chapters = emptyList(), cloudEnabled = true)

    @Test fun configuredCloudWithoutAnActualBackupHasNoCloudDeletionOption() {
        val book = book()
        compose.setContent { MaterialTheme { BatchRemoveBooksDialog(listOf(book), 0, true, false, { _, _ -> }, {},
            onCloudBytes = { emptyMap() }) } }
        compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("shelf_batch_remove_confirm") and isEnabled())
            .fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("shelf_batch_remove_cloud").assertDoesNotExist()
    }

    @Test fun actualBackupsShowTheirSpaceAndOnlyConfirmedRemoteIdsAreRemoved() {
        val a = book(); val b = book()
        var confirmed: Pair<Set<String>, Boolean>? = null
        compose.setContent { MaterialTheme { BatchRemoveBooksDialog(listOf(a, b), 0, true, false,
            { ids, original -> confirmed = ids to original }, {}, onCloudBytes = { mapOf(b.id to 12345L) }) } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("shelf_batch_remove_cloud").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("shelf_batch_remove_cloud").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithTag("shelf_batch_remove_${a.id}_cloud_space").assertDoesNotExist()
        compose.onNodeWithTag("shelf_batch_remove_${b.id}_cloud_space").assertTextEquals("云端占用：" +
            Formatter.formatShortFileSize(RuntimeEnvironment.getApplication(), 12345))
        compose.onNodeWithTag("shelf_batch_remove_confirm").performClick()
        compose.runOnIdle { assertEquals(setOf(b.id) to false, confirmed) }
    }

    @Test fun externalOriginalRequiresAnExplicitSelectionAndCancelKeepsIt() {
        val root = File("build/original-remove-${UUID.randomUUID()}").absoluteFile.apply { mkdirs() }
        val original = File(root, "original.txt").apply { writeText("保留原件") }
        val book = book().copy(sourceUri = original.toURI().toString())
        var calls = 0
        try {
            compose.setContent { MaterialTheme { BatchRemoveBooksDialog(listOf(book), 0, false, false,
                { _, _ -> calls++ }, {}, canDeleteOriginal = true) } }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("shelf_batch_remove_original").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("shelf_batch_remove_original").performScrollTo().assertIsOff().performClick()
            compose.onNodeWithText("将删除原文件：${book.sourceUri}").assertExists()
            compose.onNodeWithTag("shelf_batch_remove_cancel").performClick()
            assertEquals(0, calls); assertTrue(original.isFile)
            deleteBookOriginal(RuntimeEnvironment.getApplication(), book)
            assertFalse(original.exists())
            assertTrue(root.isDirectory)
            val directoryBook = book.copy(sourceUri = root.toURI().toString())
            assertNull(bookOriginalSource(RuntimeEnvironment.getApplication(), directoryBook))
            deleteBookOriginal(RuntimeEnvironment.getApplication(), directoryBook)
            assertTrue(root.isDirectory)
        } finally { check(root.canonicalFile.parentFile == File("build").canonicalFile); root.deleteRecursively() }
    }
}
