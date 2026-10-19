package com.eyue.reader.ui

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.work.Configuration
import androidx.work.WorkManager
import com.eyue.reader.ReaderViewModel
import com.eyue.reader.data.Book
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.Chapter
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.LibraryRepository
import com.eyue.reader.data.LibraryState
import com.eyue.reader.data.cloudOperationMutex
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EReadAppDraftRobolectricTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var root: File
    private lateinit var application: Application
    private lateinit var source: File
    private lateinit var original: Book
    private lateinit var vm: ReaderViewModel
    private val host = java.util.concurrent.atomic.AtomicReference<android.view.View>()
    private val store = ViewModelStore()
    private val originalText = "第一章\n这是旧正文，用于验证手动保存。\n\n第二段正文应保持不变。"
    private val draftText = originalText.replace("旧正文", "草稿正文")

    @Before fun setUp() {
        val base = RuntimeEnvironment.getApplication()
        runCatching { WorkManager.getInstance(base) }.getOrElse {
            WorkManager.initialize(base, Configuration.Builder().build())
        }
        val prefix = "app-draft-${UUID.randomUUID()}-"
        root = File(base.cacheDir, prefix).apply { mkdirs() }
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefix + name, mode)
        }
        application = object : Application() { init { attachBaseContext(isolated) } }
        application.getSharedPreferences("onboarding", 0).edit().putBoolean("demo_seeded", true).commit()
        source = File(root, "手动保存.txt").apply { writeText(originalText) }
        original = Book(title = "手动保存", format = BookFormat.TXT, mode = LibraryMode.NOVEL,
            sourcePath = source.absolutePath,
            chapters = listOf(Chapter(title = "第一章", textPath = source.absolutePath, characterCount = originalText.length)))
        LibraryRepository(application).save(LibraryState(books = listOf(original)))
    }

    @After fun tearDown() {
        if (::vm.isInitialized) {
            val job = requireNotNull(vm.viewModelScope.coroutineContext[Job])
            store.clear()
            awaitState(10_000) { job.isCompleted }
        }
        root.deleteRecursively()
    }

    @Test fun draftIsVisibleButNotWrittenUntilSaveAndLeaveIsConfirmed() {
        launchAndOpen()
        makeDraft()
        assertOriginalStillSaved()
        compose.onNodeWithTag("reader_save_book").assertIsDisplayed().assertIsEnabled()
        capture("reader-draft")

        compose.onNodeWithContentDescription("返回书架").performClick()
        compose.onNodeWithTag("reader_unsaved_dialog").assertIsDisplayed()
        compose.onNodeWithText("《手动保存》").assertIsDisplayed()
        compose.onNodeWithTag("reader_unsaved_cancel").performClick()
        compose.onNodeWithTag("reader_unsaved_dialog").assertDoesNotExist()
        compose.onNodeWithText("这是草稿正文", substring = true).assertIsDisplayed()
        compose.runOnIdle { assertEquals(original.id, vm.activeBookId) }
        assertOriginalStillSaved()

        compose.onNodeWithContentDescription("返回书架").performClick()
        compose.onNodeWithTag("reader_unsaved_save").performClick()
        awaitState(30_000) {
            vm.activeBookId == null && !vm.hasBookDraft(original.id) &&
                compose.onAllNodesWithTag("bookshelf").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("bookshelf").assertIsDisplayed()
        assertEquals(draftText, source.readText())
        assertEquals(draftText, persistedText())
    }

    @Test fun discardingDraftLeavesSourceUnchangedAndReopensOriginalText() {
        launchAndOpen()
        makeDraft()
        compose.onNodeWithContentDescription("返回书架").performClick()
        compose.onNodeWithTag("reader_unsaved_discard").performClick()
        awaitState(15_000) { vm.activeBookId == null && !vm.hasBookDraft(original.id) }
        compose.onNodeWithTag("bookshelf").assertIsDisplayed()
        assertOriginalStillSaved()

        openFromShelf()
        compose.onNodeWithText("这是旧正文", substring = true).assertIsDisplayed()
        compose.onNodeWithText("这是草稿正文", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("reader_save_book").assertDoesNotExist()
    }

    @Test fun backingOutWhileFirstPreviewIsStillPreparingWaitsForTheUnsavedDecision() {
        launchAndOpen()
        val owner = Any()
        runBlocking { cloudOperationMutex.lock(owner) }
        var held = true
        try {
            compose.runOnIdle {
                val offset = originalText.indexOf("旧正文")
                vm.editTxt(0, offset, offset + 3, "草稿正文")
            }
            awaitState(10_000) { original.id in vm.editingBookIds }
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("reader_unsaved_dialog").assertIsDisplayed()
            compose.onNodeWithText("正在应用修改…").assertIsDisplayed()
            compose.onNodeWithTag("reader_unsaved_save").assertIsNotEnabled()
            compose.runOnIdle {
                assertEquals(original.id, vm.activeBookId)
                assertFalse(compose.activity.isFinishing)
            }
            cloudOperationMutex.unlock(owner)
            held = false
            awaitState(30_000) { vm.hasBookDraft(original.id) && !vm.textEditBusy }
            compose.onNodeWithTag("reader_unsaved_cancel").performClick()
            compose.onNodeWithText("这是草稿正文", substring = true).assertIsDisplayed()
            assertOriginalStillSaved()
        } finally { if (held) cloudOperationMutex.unlock(owner) }
    }

    private fun launchAndOpen() {
        compose.runOnIdle {
            vm = ReaderViewModel(application)
            store.put("reader", vm)
        }
        compose.setContent { host.set(LocalView.current); EReadApp(vm, emptyFlow()) }
        awaitState(30_000) { !vm.loading }
        compose.onNodeWithText("书架").performClick()
        openFromShelf()
    }

    private fun openFromShelf() {
        compose.onNodeWithTag("shelf_book_title_${original.id}").performScrollTo().assertIsDisplayed().performClick()
        try {
            awaitState(30_000) {
                vm.activeBookId == original.id && compose.onAllNodesWithTag("reader_loading").fetchSemanticsNodes().isEmpty() &&
                    compose.onAllNodesWithContentDescription("阅读正文").fetchSemanticsNodes().isNotEmpty()
            }
        } catch (failure: Throwable) {
            capture("reader-open-failed")
            throw AssertionError("active=${vm.activeBookId}; expected=${original.id}; message=${vm.message}; " +
                compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 8), failure)
        }
    }

    private fun makeDraft() {
        compose.runOnIdle {
            val offset = originalText.indexOf("旧正文")
            vm.editTxt(0, offset, offset + "旧正文".length, "草稿正文")
        }
        awaitState(30_000) {
            vm.hasBookDraft(original.id) && !vm.textEditBusy &&
                compose.onAllNodesWithText("这是草稿正文", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("这是草稿正文", substring = true).assertIsDisplayed()
    }

    private fun assertOriginalStillSaved() {
        assertEquals(originalText, source.readText())
        assertEquals(originalText, persistedText())
    }

    private fun persistedText(): String = LibraryRepository(application).load().books.single { it.id == original.id }
        .chapters.joinToString("\n\n") { File(requireNotNull(it.textPath)).readText() }

    private fun capture(name: String) {
        val directory = System.getenv("EREAD_TEST_ARTIFACTS")?.let(::File) ?: return
        directory.mkdirs()
        val bitmap = compose.runOnIdle {
            val view = host.get()
            android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                .also { view.draw(android.graphics.Canvas(it)) }
        }
        try { File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }

    private fun awaitState(timeoutMillis: Long, condition: () -> Boolean) {
        compose.waitUntil(timeoutMillis) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            condition()
        }
    }
}
