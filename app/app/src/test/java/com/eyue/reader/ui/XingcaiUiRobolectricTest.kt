package com.eyue.reader.ui

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.work.Configuration
import androidx.work.WorkManager
import com.eyue.reader.ReaderViewModel
import com.eyue.reader.data.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w392dp-h852dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class XingcaiUiRobolectricTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: File
    private lateinit var application: Application
    private lateinit var vm: ReaderViewModel
    private val store = ViewModelStore()
    private val host = AtomicReference<View>()

    @Before fun setup() {
        val base = RuntimeEnvironment.getApplication() as Application
        runCatching { WorkManager.getInstance(base) }.getOrElse {
            WorkManager.initialize(base, Configuration.Builder().build())
        }
        root = File(base.cacheDir, "xingcai-${UUID.randomUUID()}").apply { mkdirs() }
        application = object : Application() { init { attachBaseContext(object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(root.name + name, mode)
        }) } }
    }

    @After fun cleanup() {
        val job = if (::vm.isInitialized) vm.viewModelScope.coroutineContext[Job] else null
        store.clear()
        if (job != null) compose.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idle(); job.isCompleted }
        root.deleteRecursively()
    }

    @Test fun themedNavigationStillOpensTheActualReaderAndExistingSettingsAndPersistsItsSelection() {
        compose.runOnIdle { vm = ReaderViewModel(application); store.put("reader", vm) }
        val updates = AppUpdateSession(checker = { AppUpdateResult(true, "next", 999,
            "更新", null, "https://github.com/byonesheart/ERead/releases") })
        compose.setContent { host.set(LocalView.current); EReadApp(vm, emptyFlow(), updates) }
        await { !vm.loading && vm.state.books.isNotEmpty() }
        val before = compose.runOnIdle {
            val preferences = vm.state.settings to vm.state.comicSettings
            vm.setAppTheme(AppThemeStyle.XINGCAI)
            preferences
        }
        compose.onNodeWithTag("xingcai_featured_book").assertIsDisplayed()
        compose.onNodeWithTag("app_nav_0").assertIsSelected()
        capture("xingcai-home-light")
        compose.onNodeWithTag("top_night_mode").performClick()
        await { vm.state.themeMode == ThemeMode.DARK }
        capture("xingcai-home-dark")
        compose.onNodeWithTag("app_nav_1").performClick().assertIsSelected()
        compose.onNodeWithTag("bookshelf").assertIsDisplayed()
        capture("xingcai-shelf-dark")
        val book = compose.runOnIdle { vm.state.books.first { it.mode == LibraryMode.NOVEL } }
        compose.onNodeWithTag("shelf_book_title_${book.id}").performClick()
        await { vm.activeBookId == book.id }
        compose.onNodeWithTag("reader_body").assertIsDisplayed()
        compose.runOnIdle { vm.close() }
        await { vm.activeBookId == null }
        compose.onNodeWithTag("app_nav_2").performClick()
        compose.onNodeWithTag("settings_app_theme").assertIsDisplayed()
        compose.onNodeWithTag("settings_update_badge").assertDoesNotExist()
        capture("xingcai-settings-dark")
        compose.onNodeWithTag("settings_cloud").performScrollTo().performClick()
        capture("xingcai-cloud-dark")
        compose.onNodeWithTag("cloud_open_account").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings_back").performClick()
        compose.onNodeWithTag("settings_app_theme").performScrollTo().performClick()
        compose.onNodeWithTag("app_theme_xingcai").assertIsSelected()
        compose.onNodeWithTag("app_theme_classic").performClick().assertIsSelected()
        compose.onNodeWithTag("app_theme_xingcai").performClick().assertIsSelected()
        compose.runOnIdle {
            assertEquals(before.first.copy(nightMode = true), vm.state.settings)
            assertEquals(before.second.copy(nightMode = true), vm.state.comicSettings)
        }
        await { LibraryRepository(application).load().appTheme == AppThemeStyle.XINGCAI }
        compose.onNodeWithTag("settings_back").performClick()
        compose.onNodeWithTag("app_nav_0").performClick()
        compose.onNodeWithTag("top_import").performClick()
        compose.onNodeWithText("导入文件").assertIsDisplayed()
        compose.onNodeWithText("导入漫画文件夹").assertIsDisplayed()
    }

    @Test fun cloudBookTapShowsTheNotDownloadedMessageAndRecentPreferenceIsSavedThroughTheActualApp() {
        val remote = Book(id = "cloud-placeholder", title = "云端示例", format = BookFormat.EPUB,
            mode = LibraryMode.NOVEL, chapters = emptyList(), cloudOnly = true)
        LibraryRepository(application).save(LibraryState(books = listOf(remote), appTheme = AppThemeStyle.XINGCAI))
        compose.runOnIdle { vm = ReaderViewModel(application); store.put("reader", vm) }
        val updates = AppUpdateSession(checker = { AppUpdateResult(false, "current", 1, "", null,
            "https://github.com/byonesheart/ERead/releases") })
        compose.setContent { EReadApp(vm, emptyFlow(), updates) }
        await { !vm.loading && vm.state.books.isNotEmpty() }
        compose.onNodeWithTag("app_nav_1").performClick()
        compose.onNodeWithTag("shelf_book_cover_${remote.id}", useUnmergedTree = true).performTouchInput { click() }
        compose.onNodeWithText("《云端示例》还未下载，请点击云端图标下载").assertIsDisplayed()
        compose.runOnIdle { assertNull(vm.activeBookId) }
        compose.onNodeWithTag("app_nav_2").performClick()
        compose.onNodeWithTag("settings_recent_reading").performScrollTo().assertIsOn().performClick().assertIsOff()
        await { !LibraryRepository(application).load().showRecentReading }
        compose.onNodeWithTag("app_nav_0").performClick()
        compose.onNodeWithTag("recent_reading").assertDoesNotExist()
    }

    @Test fun einkUsesMonochromeGlyphsInsteadOfTheColorfulNavigationAndTopBar() {
        compose.setContent {
            host.set(LocalView.current)
            EReadTheme(false, true, AppThemeStyle.XINGCAI) {
                Column(Modifier.fillMaxSize().background(Paper)) {
                    ModeTabs(LibraryMode.NOVEL, {}, {})
                    XingcaiNavigationBar(1, false) {}
                }
            }
        }
        compose.onNodeWithTag("app_nav_1").assertIsSelected()
        compose.waitForIdle()
        val bitmap = compose.runOnIdle {
            val view = host.get()
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue(pixels.all { val r = it shr 16 and 255; val g = it shr 8 and 255; val b = it and 255; r == g && g == b })
        bitmap.recycle()
        capture("xingcai-eink")
    }

    private fun await(condition: () -> Boolean) = compose.waitUntil(30_000) {
        shadowOf(Looper.getMainLooper()).idle(); condition()
    }

    private fun capture(name: String) {
        val output = System.getenv("EREAD_TEST_ARTIFACTS") ?: return
        compose.waitForIdle()
        val bitmap = compose.runOnIdle {
            val view = host.get()
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        File(output).apply { mkdirs() }.resolve("$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
