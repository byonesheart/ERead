package com.eyue.reader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.eyue.reader.data.LibraryMode
import org.junit.Assert.*
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
class ModeTabsRobolectricTest {
    @get:Rule val compose = createComposeRule()

    @Test fun novelComicNightNotesAndImportFitInThatOrderOnOneRowAt320Dp() {
        showTabs()
        val controls = listOf(compose.onNodeWithText("小说"), compose.onNodeWithText("漫画"),
            compose.onNodeWithTag("top_night_mode"), compose.onNodeWithTag("notebook_entry"),
            compose.onNodeWithTag("top_import"))
        val row = compose.onNodeWithTag("mode_tabs").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val bounds = controls.map { it.assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
        bounds.zipWithNext().forEach { (left, right) ->
            assertTrue("Top controls must retain their requested left-to-right order", left.right <= right.left)
        }
        bounds.forEach { control ->
            assertTrue(control.left >= row.left)
            assertTrue(control.right <= row.right)
            assertTrue(control.top >= row.top && control.bottom <= row.bottom)
        }
        assertTrue("The complete top row must fit the 320dp viewport", row.right <= 320f)
        assertTrue("Top controls should remain on one line", row.height <= 56f)
    }

    @Test fun modeSwitchAndTopActionsDispatchTheirOwnCallbacksAndNightIconChangesItsDescription() {
        var selected = LibraryMode.NOVEL
        var notes = 0
        var imports = 0
        var nightChanges = 0
        showTabs(onMode = { selected = it }, onNotes = { notes++ }, onImport = { imports++ }, onNight = { nightChanges++ })
        val before = description("top_night_mode")
        compose.onNodeWithText("漫画").performClick()
        compose.runOnIdle { assertEquals(LibraryMode.COMIC, selected) }
        compose.onNodeWithText("小说").performClick()
        compose.runOnIdle { assertEquals(LibraryMode.NOVEL, selected) }
        compose.onNodeWithTag("notebook_entry").performClick()
        compose.onNodeWithTag("top_import").performClick()
        compose.onNodeWithTag("top_night_mode").performClick()
        val after = description("top_night_mode")
        assertNotEquals("The sun/moon action must describe the new target appearance", before, after)
        assertTrue(before.contains("夜间"))
        assertTrue(after.contains("日间"))
        compose.runOnIdle { assertEquals(1, notes); assertEquals(1, imports); assertEquals(1, nightChanges) }
    }

    @Test fun aDisabledImportRemainsVisibleWhileNightAndNotesStayUsable() {
        var imports = 0
        var notes = 0
        var nightChanges = 0
        showTabs(importEnabled = false, onImport = { imports++ }, onNotes = { notes++ }, onNight = { nightChanges++ })
        compose.onNodeWithTag("top_import").assertIsDisplayed().assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithTag("notebook_entry").assertIsEnabled().performClick()
        compose.onNodeWithTag("top_night_mode").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(0, imports); assertEquals(1, notes); assertEquals(1, nightChanges) }
    }

    private fun showTabs(importEnabled: Boolean = true, onMode: (LibraryMode) -> Unit = {}, onNotes: () -> Unit = {},
        onImport: () -> Unit = {}, onNight: () -> Unit = {}) {
        compose.setContent {
            var mode by remember { mutableStateOf(LibraryMode.NOVEL) }
            var night by remember { mutableStateOf(false) }
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                MaterialTheme(colorScheme = if (night) DarkAppColors else LightAppColors) {
                    Box(Modifier.width(320.dp)) {
                        ModeTabs(mode, { mode = it; onMode(it) }, onNotes, onImport, importEnabled,
                            nightMode = night, onToggleNight = { night = !night; onNight() })
                    }
                }
            }
        }
    }

    private fun description(tag: String): String = compose.onNodeWithTag(tag).fetchSemanticsNode()
        .config[SemanticsProperties.ContentDescription].joinToString()
}
