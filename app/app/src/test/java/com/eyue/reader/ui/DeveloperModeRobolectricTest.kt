package com.eyue.reader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.eyue.reader.data.DeveloperModePreferences
import com.eyue.reader.data.LibraryState
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DeveloperModeRobolectricTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() { context.getSharedPreferences("app_updates", 0).edit().clear().commit() }
    @After fun cleanup() { reset() }

    @Test fun tenAvatarClicksEnableTheLocalPreferenceThroughTheSettingsPageAndPersistIt() {
        val preferences = DeveloperModePreferences(context)
        var enabled by mutableStateOf(preferences.enabled)
        var enables = 0
        compose.setContent { MaterialTheme { SettingsScreen(LibraryState(), {}, {}, {}, {},
            developerMode = enabled, onEnableDeveloperMode = {
                preferences.enable(); enabled = preferences.enabled; enables++
            }) } }
        compose.onNodeWithTag("settings_about").performScrollTo().performClick()
        compose.onNodeWithTag("settings_about_author").performScrollTo().performClick()
        repeat(9) { compose.onNodeWithTag("author_avatar").performClick() }
        compose.runOnIdle { assertFalse(preferences.enabled); assertEquals(0, enables) }
        compose.onNodeWithTag("developer_mode_enabled").assertDoesNotExist()
        compose.onNodeWithTag("author_avatar").performClick()
        compose.onNodeWithTag("developer_mode_enabled").assertIsDisplayed()
        compose.runOnIdle { assertTrue(DeveloperModePreferences(context).enabled); assertEquals(1, enables) }
        repeat(10) { compose.onNodeWithTag("author_avatar").performClick() }
        compose.runOnIdle { assertEquals(1, enables) }
    }

    @Test fun aLongPauseOrLeavingTheAuthorPageBreaksTheClickSequence() {
        var shown by mutableStateOf(true)
        var enables = 0
        compose.setContent { MaterialTheme { if (shown) AuthorContent(onEnableDeveloperMode = { enables++ }) } }
        repeat(5) { compose.onNodeWithTag("author_avatar").performClick() }
        ShadowSystemClock.advanceBy(Duration.ofMillis(1600))
        repeat(5) { compose.onNodeWithTag("author_avatar").performClick() }
        compose.runOnIdle { assertEquals(0, enables); shown = false }
        compose.waitForIdle()
        compose.runOnIdle { shown = true }
        repeat(5) { compose.onNodeWithTag("author_avatar").performClick() }
        compose.runOnIdle { assertEquals(0, enables) }
        repeat(5) { compose.onNodeWithTag("author_avatar").performClick() }
        compose.runOnIdle { assertEquals(1, enables) }
    }
}
