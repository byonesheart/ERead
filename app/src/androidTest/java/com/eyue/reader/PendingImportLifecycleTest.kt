package com.eyue.reader

import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.LibraryRepository
import com.eyue.reader.data.LinkedDocumentsProvider
import com.eyue.reader.data.ReaderSettings
import com.eyue.reader.data.ReadingMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.rules.RuleChain
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PendingImportLifecycleTest {
    val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(ConsentGrantRule()).around(compose)
    private lateinit var vm: ReaderViewModel
    private var originalNovel: ReaderSettings? = null
    private var originalComic: ReaderSettings? = null
    private var originalMode: LibraryMode? = null
    private var originalBookIds = emptySet<String>()
    private var failureDirectory: File? = null
    private val heldUri = DocumentsContract.buildDocumentUri(LinkedDocumentsProvider.AUTHORITY, "held")

    @Before fun rememberEnvironment() {
        compose.runOnIdle { vm = ViewModelProvider(compose.activity)[ReaderViewModel::class.java] }
        compose.waitUntil(30_000) { !vm.loading && !vm.importing }
        compose.runOnIdle {
            originalNovel = vm.state.settings
            originalComic = vm.state.comicSettings
            originalMode = vm.mode
            originalBookIds = vm.state.books.map { it.id }.toSet()
            vm.close()
        }
    }

    @After fun restoreEnvironment() {
        // Unblock the source even if an assertion fails before the normal release.
        providerCall("release")
        if (!::vm.isInitialized || originalNovel == null) return
        compose.waitUntil(30_000) { !vm.importing }
        val removed = mutableListOf<String>()
        val novel = requireNotNull(originalNovel)
        val comic = requireNotNull(originalComic).copy(nightMode = novel.nightMode)
        compose.runOnIdle {
            vm.close()
            vm.state.books.filter { it.id !in originalBookIds && it.sourceUri == heldUri.toString() }.forEach {
                removed += it.id
                vm.deleteBook(it)
            }
            vm.mode = LibraryMode.COMIC
            vm.settings(comic)
            vm.mode = LibraryMode.NOVEL
            vm.settings(novel)
            vm.mode = requireNotNull(originalMode)
            vm.message = null
            vm.flush()
        }
        compose.waitUntil(10_000) {
            LibraryRepository(compose.activity).load().let { state ->
                state.books.none { it.id in removed } && state.settings == novel && state.comicSettings == comic
            } && removed.none { File(compose.activity.filesDir, "books/$it").exists() }
        }
        providerCall("revoke")
        failureDirectory?.deleteRecursively()
    }

    @Test fun heldImportKeepsNavigationUsableAndSuccessAndFailureBothClearThePendingCard() {
        providerCall("hold")
        providerCall("grant")
        compose.runOnIdle {
            vm.mode = LibraryMode.NOVEL
            vm.settings(vm.readerSettings.copy(readingMode = ReadingMode.SCROLL))
            vm.message = null
            vm.importFiles(listOf(heldUri), LibraryMode.NOVEL)
        }
        compose.waitUntil(10_000) {
            vm.importing && vm.pendingImport?.title == "held" &&
                compose.onAllNodesWithTag("pending_import_cover").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("pending_import_cover").assertIsDisplayed()
        compose.onNodeWithTag("pending_import_progress").assertIsDisplayed()
        compose.onAllNodesWithText("正在整理", substring = true).assertCountEquals(0)
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithTag("top_import").assertIsNotEnabled()

        compose.onNodeWithText("漫画").performClick()
        compose.runOnIdle {
            assertEquals(LibraryMode.COMIC, vm.mode)
            assertTrue(vm.importing)
            assertEquals("held", vm.pendingImport?.title)
        }
        compose.onNodeWithTag("pending_import_book").assertDoesNotExist()
        compose.onNodeWithTag("top_import").assertIsNotEnabled()
        compose.onNodeWithText("设置", useUnmergedTree = true).performClick()
        compose.onNodeWithText("按你的习惯阅读").assertIsDisplayed()
        compose.onNodeWithText("阅读", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("reading_home").assertIsDisplayed()
        compose.onNodeWithText("小说").performClick()
        compose.onNodeWithText("书架", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("pending_import_cover").assertIsDisplayed()

        providerCall("release")
        compose.waitUntil(20_000) {
            !vm.importing && vm.pendingImport == null && vm.state.books.count { it.id !in originalBookIds } == 1
        }
        val imported = vm.state.books.single { it.id !in originalBookIds }
        assertEquals(heldUri.toString(), imported.sourceUri)
        assertTrue(imported.chapters.any { it.textPath?.let(::File)?.isFile == true })
        compose.onNodeWithTag("pending_import_book").assertDoesNotExist()
        compose.onAllNodesWithTag("shelf_book_more_${imported.id}").assertCountEquals(1)
        compose.onNodeWithTag("shelf_book_more_${imported.id}").assertIsDisplayed()
        compose.onNodeWithTag("top_import").assertIsEnabled()
        assertTrue(LibraryRepository(compose.activity).load().books.any { it.id == imported.id })

        val preservedIds = vm.state.books.map { it.id }.toSet()
        failureDirectory = File(compose.activity.cacheDir, "pending-failure-${UUID.randomUUID()}").apply { mkdirs() }
        val corrupt = File(requireNotNull(failureDirectory), "corrupt.mobi").apply { writeBytes(byteArrayOf(0, 1, 2, 3)) }
        compose.runOnIdle {
            vm.message = null
            vm.importFiles(listOf(Uri.fromFile(corrupt)), LibraryMode.NOVEL)
        }
        compose.waitUntil(10_000) {
            !vm.importing && vm.pendingImport == null &&
                compose.onAllNodesWithText("corrupt.mobi", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("corrupt.mobi", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("pending_import_book").assertDoesNotExist()
        compose.onNodeWithTag("top_import").assertIsEnabled()
        compose.runOnIdle {
            assertFalse(vm.importing)
            assertEquals(preservedIds, vm.state.books.map { it.id }.toSet())
        }
        assertEquals(preservedIds, LibraryRepository(compose.activity).load().books.map { it.id }.toSet())
    }

    private fun providerCall(method: String) {
        InstrumentationRegistry.getInstrumentation().context.contentResolver.call(
            Uri.parse("content://${LinkedDocumentsProvider.AUTHORITY}"), method, "com.eyue.reader",
            Bundle().apply { putString("id", "held") })
    }
}
