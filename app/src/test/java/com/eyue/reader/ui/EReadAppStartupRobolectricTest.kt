package com.eyue.reader.ui

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.work.Configuration
import androidx.work.WorkManager
import com.eyue.reader.ReaderViewModel
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.LibraryRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EReadAppStartupRobolectricTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: File
    private lateinit var application: Application
    private lateinit var vm: ReaderViewModel
    private val store = ViewModelStore()

    @Before fun setUp() {
        val base = RuntimeEnvironment.getApplication()
        runCatching { WorkManager.getInstance(base) }.getOrElse {
            WorkManager.initialize(base, Configuration.Builder().build())
            WorkManager.getInstance(base)
        }
        val prefix = "app-startup-${UUID.randomUUID()}-"
        root = File(base.cacheDir, prefix).apply { mkdirs() }
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefix + name, mode)
        }
        application = object : Application() { init { attachBaseContext(isolated) } }
    }

    @After fun tearDown() {
        if (::vm.isInitialized) {
            val job = vm.viewModelScope.coroutineContext[Job]!!
            // Cancel first even if an earlier Compose assertion left the UI test scope unhealthy.
            store.clear()
            awaitState(10_000) { job.isCompleted }
        }
        root.deleteRecursively()
    }

    @Test fun shelfDemoBookOpensUpdatesRealStateAndClosesBackToShelfWithDefaultStatusSettings() {
        compose.runOnIdle {
            vm = ReaderViewModel(application)
            store.put("reader", vm)
        }
        compose.setContent { EReadApp(vm, emptyFlow()) }
        awaitState(30_000) { !vm.loading && vm.state.books.isNotEmpty() }
        val book = compose.runOnIdle {
            assertTrue(vm.readerSettings.showBattery)
            assertTrue(vm.readerSettings.showTime)
            vm.state.books.first { it.mode == LibraryMode.NOVEL }
        }
        compose.onNodeWithText("书架").performClick()
        compose.onNodeWithTag("bookshelf").assertIsDisplayed()
        awaitState(10_000) {
            compose.onAllNodesWithContentDescription("《${book.title}》封面").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("《${book.title}》封面").performClick()
        awaitState(30_000) {
            vm.activeBookId == book.id && compose.onAllNodesWithTag("reader_loading").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithContentDescription("阅读正文").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("reader_body").assertIsDisplayed()
        compose.onNodeWithText("雨在傍晚五点停了", substring = true).assertIsDisplayed()

        // The real EReadApp callback writes the observed scroll anchor into ReaderViewModel.state.
        compose.onNodeWithTag("reader_scroll").performScrollToIndex(1)
        awaitState(30_000) {
            vm.state.books.first { it.id == book.id }.let { it.chapterIndex > 0 && it.progress > 0f } &&
                compose.onAllNodesWithTag("reader_loading").fetchSemanticsNodes().isEmpty()
        }
        val saved = compose.runOnIdle { vm.state.books.first { it.id == book.id } }
        compose.onNodeWithTag("reader_body").performTouchInput { click() }
        compose.onNodeWithContentDescription("返回书架").performClick()
        awaitState(15_000) {
            vm.activeBookId == null && compose.onAllNodesWithTag("bookshelf").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("bookshelf").assertIsDisplayed()
        compose.runOnIdle {
            val actual = vm.state.books.first { it.id == book.id }
            assertEquals(saved.chapterIndex, actual.chapterIndex)
            assertEquals(saved.characterOffset, actual.characterOffset)
            assertTrue(actual.progress > 0f)
        }
        awaitState(15_000) {
            LibraryRepository(application).load().books.firstOrNull { it.id == book.id }?.progress?.let { it > 0f } == true
        }
    }

    private fun awaitState(timeoutMillis: Long, condition: () -> Boolean) {
        compose.waitUntil(timeoutMillis) {
            // IO callbacks resume on Android's paused main looper, outside Compose's frame clock.
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            condition()
        }
    }
}
