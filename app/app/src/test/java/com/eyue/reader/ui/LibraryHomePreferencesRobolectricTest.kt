package com.eyue.reader.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.eyue.reader.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w392dp-h852dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryHomePreferencesRobolectricTest {
    @get:Rule val compose = createComposeRule()
    private val host = AtomicReference<View>()

    @Test fun everyThemeUsesSharedSettingsAndCanHideRecentReadingWithoutDeletingHistory() {
        val books = listOf(book("older", 100), book("latest", 200))
        var state by mutableStateOf(LibraryState(books = books))
        var settingsPage by mutableStateOf(false)
        compose.setContent {
            host.set(LocalView.current)
            EReadTheme(false, false, state.appTheme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (settingsPage) SettingsScreen(state, {}, {}, {}, {},
                        onShowRecentReading = { state = state.copy(showRecentReading = it) })
                    else ReadingHome(state.books, 3600, {}, {}, {}, showRecentReading = state.showRecentReading)
                }
            }
        }
        for (theme in AppThemeStyle.entries) {
            compose.runOnIdle { state = LibraryState(books = books, appTheme = theme); settingsPage = false }
            compose.onNodeWithTag("recent_reading").performScrollTo().assertIsDisplayed()
            val latest = compose.onNodeWithTag("history_row_latest").performScrollTo().fetchSemanticsNode().boundsInRoot
            val older = compose.onNodeWithTag("history_row_older").performScrollTo().fetchSemanticsNode().boundsInRoot
            assertTrue(latest.top < older.top)
            capture("recent-${theme.name.lowercase()}")
            compose.runOnIdle { settingsPage = true }
            val cards = listOf("display", "reading", "services").map {
                compose.onNodeWithTag("settings_${it}_card").fetchSemanticsNode().boundsInRoot
            }
            assertTrue(cards[0].bottom <= cards[1].top && cards[1].bottom <= cards[2].top)
            compose.onNodeWithTag("settings_theme_light").assertIsSelected()
            capture("settings-${theme.name.lowercase()}")
            compose.onNodeWithTag("settings_recent_reading").assertIsOn().performClick().assertIsOff()
            compose.runOnIdle { assertEquals(books, state.books); settingsPage = false }
            compose.onNodeWithTag("recent_reading").assertDoesNotExist()
            compose.onNodeWithTag("history_row_latest").assertDoesNotExist()
            compose.runOnIdle { settingsPage = true }
            compose.onNodeWithTag("settings_recent_reading").performClick().assertIsOn()
            compose.runOnIdle { settingsPage = false }
            compose.onNodeWithTag("recent_reading").performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun aboutHasAContributorPageWithAvatarCreditAndBrowserLink() {
        compose.setContent {
            host.set(LocalView.current)
            EReadTheme(false, false, AppThemeStyle.XINGCAI) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SettingsScreen(LibraryState(appTheme = AppThemeStyle.XINGCAI), {}, {}, {}, {})
                }
            }
        }
        compose.onNodeWithTag("settings_about").performScrollTo().performClick()
        compose.onNodeWithTag("settings_about_contributors").performScrollTo().performClick()
        compose.onNodeWithTag("contributors_content").assertIsDisplayed()
        compose.onNodeWithText("7Mrlan").assertIsDisplayed()
        compose.onNodeWithContentDescription("7Mrlan 的头像").assertIsDisplayed()
        compose.onNodeWithText("提供星彩主题的界面设计与参考源码，参与软件测试，发现并反馈多项 Bug。").assertIsDisplayed()
        capture("contributors")
        compose.onNodeWithTag("contributor_7mrlan_github").performClick()
        compose.runOnIdle {
            assertEquals("https://github.com/7Mrlan", shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity.dataString)
        }
    }

    private fun book(id: String, readAt: Long) = Book(id = id, title = "阅读记录 $id", author = "测试作者",
        format = BookFormat.TXT, mode = LibraryMode.NOVEL, chapters = listOf(Chapter(title = "第一章")),
        lastReadAt = readAt, progress = .3f)

    private fun capture(name: String) {
        val output = System.getenv("EREAD_TEST_ARTIFACTS")?.let(::File) ?: return
        compose.waitForIdle()
        val bitmap = compose.runOnIdle {
            val view = host.get()
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        output.mkdirs()
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
