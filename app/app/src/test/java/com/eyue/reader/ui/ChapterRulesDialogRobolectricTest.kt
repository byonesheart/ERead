package com.eyue.reader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.eyue.reader.data.ChapterRulePreset
import com.eyue.reader.data.DEFAULT_CHAPTER_RULE
import org.junit.Assert.assertEquals
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
class ChapterRulesDialogRobolectricTest {
    @get:Rule val compose = createComposeRule()
    private var presets by mutableStateOf(listOf(
        ChapterRulePreset("default", "默认规则", DEFAULT_CHAPTER_RULE), ChapterRulePreset("custom", "分节规则", "^分节.*$")
    ))
    private var active by mutableStateOf("default")

    private fun showRules() {
        compose.setContent { MaterialTheme { ChapterRulesDialog(presets, active, {}, { saved ->
            presets = presets.filterNot { it.id == saved.id } + saved
        }, { active = it }, { id -> presets = presets.filterNot { it.id == id } }) } }
    }

    @Test fun listHidesDetailsAndChoosingAnActiveRuleDoesNotOpenItsEditor() {
        showRules()
        compose.onNodeWithTag("chapter_rule_pattern").assertDoesNotExist()
        compose.onNodeWithText("^分节.*$").assertDoesNotExist()
        compose.onNodeWithTag("chapter_rule_select_custom").performClick()
        compose.runOnIdle { assertEquals("custom", active) }
        compose.onNodeWithTag("chapter_rule_select_custom").assertIsSelected()
        compose.onNodeWithTag("chapter_rule_pattern").assertDoesNotExist()
    }

    @Test fun rowOpensEditorAndSaveReturnsToTheCompactListWithoutActivatingIt() {
        showRules()
        compose.onNodeWithTag("chapter_rule_edit_custom").performClick()
        compose.onNodeWithTag("chapter_rule_pattern").assertTextContains("^分节.*$")
        compose.onNodeWithTag("chapter_rules_list").assertDoesNotExist()
        compose.onNodeWithTag("chapter_rule_name").performTextReplacement("卷名规则")
        compose.onNodeWithTag("chapter_rule_pattern").performTextReplacement("^第.*卷$")
        compose.onNodeWithTag("chapter_rule_save").performClick()
        compose.onNodeWithTag("chapter_rules_list").assertIsDisplayed()
        compose.onNodeWithTag("chapter_rule_pattern").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("default", active)
            assertEquals(ChapterRulePreset("custom", "卷名规则", "^第.*卷$"), presets.last())
        }
    }

    @Test fun addOnlyOpensTheEditorAndReturningDiscardsTheDraft() {
        showRules()
        compose.onNodeWithTag("chapter_rule_add").performClick()
        compose.onNodeWithTag("chapter_rule_name").performTextInput("未保存的规则")
        compose.onNodeWithTag("chapter_rule_back").performClick()
        compose.onNodeWithTag("chapter_rule_pattern").assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, presets.size) }
    }
}
