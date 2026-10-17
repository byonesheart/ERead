package com.eyue.reader.ui

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eyue.reader.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookVoiceRolesRobolectricTest {
    @get:Rule val compose = createComposeRule()
    private val role = BookVoiceRole("张三", "张三", "青年男声", "沉稳", "战斗", id = "zhang")
    private val book = Book(title = "本书", format = BookFormat.TXT, mode = LibraryMode.NOVEL,
        chapters = emptyList(), voiceRoles = listOf(role))

    @Test fun anExistingRoleReceivesOnlyTheEditedAliasInItsRegex() {
        var saved: List<BookVoiceRole>? = null
        compose.setContent { MaterialTheme { BookVoiceRolesDialog(book, { saved = it }, {}, "小张") } }
        compose.onNodeWithTag("role_selected_text").performTextReplacement("小三(青年)")
        compose.onNodeWithTag("role_target_picker").performClick()
        compose.onNodeWithTag("role_target_zhang").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("role_assign_confirm").assertIsDisplayed().performClick()
        compose.onNodeWithTag("role_name").assertTextContains("张三")
        compose.onNodeWithTag("role_save").assertIsDisplayed().performClick()
        compose.runOnIdle {
            val result = requireNotNull(saved).single()
            assertEquals(role.name, result.name)
            assertEquals(role.voice, result.voice)
            assertEquals(role.description, result.description)
            assertEquals(role.scenes, result.scenes)
            assertTrue(Regex(result.nameRegex).containsMatchIn("小三(青年)"))
        }
    }

    @Test fun duplicateNamesAreReportedInsteadOfBeingAddedAgain() {
        compose.setContent { MaterialTheme { BookVoiceRolesDialog(book, {}, {}, "张三") } }
        compose.onNodeWithTag("role_target_picker").performClick()
        compose.onNodeWithTag("role_target_zhang").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("role_assign_confirm").assertIsDisplayed().performClick()
        compose.onNodeWithTag("role_error").assertTextEquals("该名字已存在")
        compose.onNodeWithTag("role_name").assertDoesNotExist()
    }

    @Test fun selectedTextCanBeEditedBeforeAppendingToDescription() {
        var saved: List<BookVoiceRole>? = null
        compose.setContent { MaterialTheme { BookVoiceRolesDialog(book, { saved = it }, {}, "原选中文字") } }
        compose.onNodeWithTag("role_selected_text").performTextReplacement("新的描述")
        compose.onNodeWithTag("role_target_picker").performClick()
        compose.onNodeWithTag("role_target_zhang").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("role_assign_DESCRIPTION").performClick()
        compose.onNodeWithTag("role_assign_confirm").assertIsDisplayed().performClick()
        compose.onNodeWithTag("role_description").performScrollTo().assertTextContains("沉稳\n新的描述")
        compose.onNodeWithTag("role_description").performTextReplacement("手动调整后的描述")
        compose.onNodeWithTag("role_save").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("手动调整后的描述", saved?.single()?.description); assertEquals(role.scenes, saved?.single()?.scenes) }
    }

    @Test fun sceneAssignmentAndTheThreeThousandCharacterLimitAreEnforced() {
        compose.setContent { MaterialTheme { BookVoiceRolesDialog(book, {}, {}, "低声说话") } }
        compose.onNodeWithTag("role_target_picker").performClick()
        compose.onNodeWithTag("role_target_zhang").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("role_assign_SCENE").performClick()
        compose.onNodeWithTag("role_assign_confirm").assertIsDisplayed().performClick()
        compose.onNodeWithTag("role_scenes").performScrollTo().assertTextContains("战斗\n低声说话")
        compose.onNodeWithTag("role_scenes").performTextReplacement("字".repeat(3001))
        compose.onNodeWithTag("role_save").assertIsDisplayed().assertIsNotEnabled()
    }

    @Test fun speechSettingsListEachBooksRolesAndUseItsOwnBookId() {
        var opened: String? = null
        compose.setContent { MaterialTheme { SettingsScreen(LibraryState(books = listOf(book)), {}, {}, {}, {},
            onBookVoiceRoles = { opened = it }) } }
        compose.onNodeWithTag("settings_speech").performClick()
        compose.onNodeWithTag("settings_book_roles_${book.id}").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(book.id, opened) }
        compose.onNodeWithText("AI 音色提取").assertDoesNotExist()
    }

    @Test fun selectingTheRoleActionHandsOffTheTextAndHidesTheSelectionMenu() {
        var toolbar: TextToolbar? = null
        var passage: ReaderSelectedPassage? = null
        compose.setContent { MaterialTheme {
            ReaderSelectableText(AnnotatedString("A selected passage with more words."), TextStyle(fontSize = 18.sp),
                0, 0, 0, 0, { _, _ -> }, modifier = Modifier.width(280.dp).composed {
                    val provided = LocalTextToolbar.current; SideEffect { toolbar = provided }; this
                }, onRole = { passage = it })
        } }
        compose.onNodeWithContentDescription("阅读正文").performSemanticsAction(SemanticsActions.SetSelection) { it(2, 10, false) }
        compose.runOnIdle { toolbar!!.showMenu(Rect(120f, 250f, 200f, 280f), null, null, null, null) }
        compose.onNodeWithTag("reader_action_role").performClick()
        compose.runOnIdle { assertEquals("selected", passage?.quote); toolbar!!.showMenu(Rect(120f, 250f, 200f, 280f), null, null, null, null) }
        compose.onNodeWithTag("reader_selection_menu").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w320dp-h360dp")
    fun assigningAndEditingRemainScrollableOnAShortWindow() {
        var saved: List<BookVoiceRole>? = null
        compose.setContent { MaterialTheme { BookVoiceRolesDialog(book, { saved = it }, {}, "小张") } }
        compose.onNodeWithTag("book_voice_roles_dialog").assertIsDisplayed()
        compose.onNodeWithTag("role_selected_text").performScrollTo().assertIsDisplayed().performTextReplacement("小三")
        compose.onNodeWithTag("role_target_picker").performScrollTo().performClick()
        compose.onNodeWithTag("role_target_new").performClick()
        compose.onNodeWithTag("role_assign_confirm").assertIsDisplayed().performClick()
        compose.onNodeWithTag("role_name").performScrollTo().assertTextContains("小三")
        compose.onNodeWithTag("role_save").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("小三", requireNotNull(saved).last().name) }
    }

    @Test fun savingClosesTheRoleMenuAndBlocksFurtherEditsUntilCompletion() {
        var saving by mutableStateOf(false)
        compose.setContent { MaterialTheme { BookVoiceRolesDialog(book, {}, {}, "小张", saving = saving) } }
        compose.onNodeWithTag("role_target_picker").performClick()
        compose.onNodeWithTag("role_target_zhang").assertExists()
        compose.runOnIdle { saving = true }
        compose.onNodeWithTag("role_saving_status").assertTextEquals("正在保存角色音色…")
        compose.onNodeWithTag("role_target_zhang").assertDoesNotExist()
        compose.onNodeWithTag("role_selected_text").assertDoesNotExist()
        compose.onNodeWithTag("role_save").assertDoesNotExist()
        compose.onNodeWithText("关闭").assertIsNotEnabled()
        compose.runOnIdle { saving = false }
        compose.onNodeWithTag("role_selected_text").assertTextContains("小张")
        compose.onNodeWithTag("role_target_zhang").assertDoesNotExist()
        compose.onNodeWithText("关闭").assertIsEnabled()
    }

    @Test fun newRolesCannotDuplicateAnExistingExactName() {
        compose.setContent { MaterialTheme { BookVoiceRolesDialog(book, {}, {}) } }
        compose.onNodeWithTag("role_new").performClick()
        compose.onNodeWithTag("role_name").performTextReplacement("张三")
        compose.onNodeWithText("该名字已存在，请选择已有角色").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("role_save").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("role_name").performScrollTo().performTextReplacement("李四")
        compose.onNodeWithTag("role_save").assertIsDisplayed().assertIsEnabled()
    }

    @Test fun renamingARoleCannotUseAnotherRolesNameButCanKeepItsOwnName() {
        val other = BookVoiceRole("李四", "李四", id = "li")
        compose.setContent { MaterialTheme { BookVoiceRolesDialog(book.copy(voiceRoles = listOf(role, other)), {}, {}) } }
        compose.onNodeWithTag("role_edit_li").performClick()
        compose.onNodeWithTag("role_name").performTextReplacement("张三")
        compose.onNodeWithTag("role_save").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("该名字已存在，请选择已有角色").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("role_name").performScrollTo().performTextReplacement("李四")
        compose.onNodeWithTag("role_save").assertIsDisplayed().assertIsEnabled()
    }

    @Test fun selectedTextRequiresAnExplicitRoleChoiceAndKeepsItsActionsInTheFooter() {
        compose.setContent { MaterialTheme { BookVoiceRolesDialog(book, {}, {}, "小张") } }
        compose.onAllNodesWithText("选择角色").assertCountEquals(2)
        compose.onNodeWithTag("role_assign_confirm").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("role_target_picker").performClick()
        compose.onNodeWithTag("role_target_new").performClick()
        compose.onNodeWithTag("role_assign_confirm").assertIsEnabled().performClick()
        compose.onNodeWithTag("role_save").assertIsDisplayed()
        compose.onNodeWithTag("role_return_list").assertIsDisplayed()
        compose.onNodeWithText("关闭").assertIsDisplayed()
        val footer = compose.onNodeWithTag("role_actions").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("role_scenes").performScrollTo().assertIsDisplayed()
        assertEquals(footer, compose.onNodeWithTag("role_actions").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun listCrossRequiresConfirmationAndOnlyDeletesItsOwnRole() {
        val other = BookVoiceRole("李四", "李四", id = "li")
        var current by mutableStateOf(book.copy(voiceRoles = listOf(role, other)))
        var saves = 0
        compose.setContent { MaterialTheme { BookVoiceRolesDialog(current, { saves++; current = current.copy(voiceRoles = it) }, {}) } }
        compose.onNodeWithTag("role_list_delete_zhang").performClick()
        compose.onNodeWithTag("role_delete_dialog").assertIsDisplayed()
        compose.onNodeWithTag("role_delete_cancel").performClick()
        compose.runOnIdle { assertEquals(0, saves) }
        compose.onNodeWithTag("role_edit_zhang").assertExists()
        compose.onNodeWithTag("role_list_delete_zhang").performClick()
        compose.onNodeWithTag("role_delete_confirm").performClick()
        compose.runOnIdle { assertEquals(1, saves); assertEquals(listOf("li"), current.voiceRoles.map { it.id }) }
        compose.onNodeWithTag("role_edit_zhang").assertDoesNotExist()
        compose.onNodeWithTag("role_edit_li").assertExists()
    }

    @Test fun dropdownCrossDoesNotSelectTheRoleAndRequiresConfirmationBeforeDeletion() {
        var current by mutableStateOf(book)
        var saves = 0
        compose.setContent { MaterialTheme { BookVoiceRolesDialog(current, { saves++; current = current.copy(voiceRoles = it) }, {}, "小张") } }
        compose.onNodeWithTag("role_target_picker").performClick()
        compose.onNodeWithTag("role_target_delete_zhang").performClick()
        compose.onNodeWithTag("role_delete_cancel").performClick()
        compose.onNodeWithTag("role_assign_confirm").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, saves) }
        compose.onNodeWithTag("role_target_picker").performClick()
        compose.onNodeWithTag("role_target_delete_zhang").performClick()
        compose.onNodeWithTag("role_delete_confirm").performClick()
        compose.runOnIdle { assertEquals(1, saves); assertTrue(current.voiceRoles.isEmpty()) }
        compose.onNodeWithTag("role_assign_confirm").assertIsNotEnabled()
    }

    @Test fun roleListSwitchUpdatesOnlyItsOwnEnabledFlag() {
        val other = BookVoiceRole("李四", "李四", id = "li")
        var current by mutableStateOf(book.copy(voiceRoles = listOf(role, other)))
        compose.setContent { MaterialTheme { BookVoiceRolesDialog(current, { current = current.copy(voiceRoles = it) }, {}) } }
        compose.onNodeWithTag("role_enabled_zhang").assertIsOn().performClick()
        compose.onNodeWithTag("role_enabled_zhang").assertIsOff()
        compose.onNodeWithTag("role_enabled_li").assertIsOn()
        compose.runOnIdle { assertEquals(role.copy(enabled = false), current.voiceRoles.first()) }
    }

    @Test fun dropdownSwitchDoesNotSelectAndTheRoleLabelStillAcceptsATouch() {
        var current by mutableStateOf(book)
        var saves = 0
        compose.setContent { MaterialTheme {
            BookVoiceRolesDialog(current, { saves++; current = current.copy(voiceRoles = it) }, {}, "小张")
        } }
        compose.onNodeWithTag("role_target_picker").performClick()
        compose.onNodeWithTag("role_target_enabled_zhang").assertIsOn().performClick().assertIsOff()
        compose.runOnIdle { assertEquals(1, saves); assertEquals(role.copy(enabled = false), current.voiceRoles.single()) }
        compose.onNodeWithTag("role_target_zhang").assertIsDisplayed()
        compose.onNodeWithTag("role_assign_confirm").assertIsNotEnabled()
        // The item's center contains its independent switch; the label side selects the role.
        compose.onNodeWithTag("role_target_zhang").performTouchInput { click(Offset(8f, center.y)) }
        compose.onNodeWithTag("role_target_zhang").assertDoesNotExist()
        compose.onNodeWithTag("role_assign_confirm").assertIsEnabled().performClick()
        compose.onNodeWithTag("role_name").assertTextContains("张三")
        compose.onNodeWithTag("role_edit_enabled").assertIsOff()
        compose.onNodeWithTag("role_save").performClick()
        compose.runOnIdle {
            assertEquals(2, saves)
            assertFalse(current.voiceRoles.single().enabled)
            assertTrue(Regex(current.voiceRoles.single().nameRegex).containsMatchIn("小张"))
        }
    }

    @Test fun newRoleDefaultsToCharacterAndCanSaveAsADisabledNarrator() {
        var saved: List<BookVoiceRole>? = null
        compose.setContent { MaterialTheme { BookVoiceRolesDialog(book, { saved = it }, {}) } }
        compose.onNodeWithTag("role_new").performClick()
        compose.onNodeWithTag("role_kind_character").assertIsSelected()
        compose.onNodeWithTag("role_kind_narrator").performClick().assertIsSelected()
        compose.onNodeWithTag("role_edit_enabled").assertIsOn().performClick()
        compose.onNodeWithTag("role_name").performTextReplacement("新增旁白")
        compose.onNodeWithTag("role_save").performClick()
        compose.runOnIdle {
            assertEquals(VoiceRoleKind.NARRATOR, saved?.last()?.kind)
            assertEquals(false, saved?.last()?.enabled)
            assertEquals(role, saved?.first())
        }
    }

    @Test fun commonRolesHaveAnIndependentSettingsEntryAndReuseTheRoleEditor() {
        var opened = false
        var showDialog by mutableStateOf(false)
        val common = book.copy(id = "__common_roles__", title = "通用角色", voiceRoles = defaultCommonVoiceRoles())
        compose.setContent { MaterialTheme {
            if (showDialog) BookVoiceRolesDialog(common, {}, {})
            else SettingsScreen(LibraryState(books = listOf(book)), {}, {}, {}, {}, onCommonVoiceRoles = { opened = true; showDialog = true })
        } }
        compose.onNodeWithTag("settings_speech").performClick()
        compose.onNodeWithTag("settings_common_voice_roles").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(opened) }
        compose.onNodeWithTag("role_enabled_common-narrator-male").assertIsOn()
        compose.onNodeWithTag("role_enabled_common-narrator-female").assertIsOn()
        compose.onNodeWithTag("role_edit_common-narrator-male").performClick()
        compose.onNodeWithTag("role_kind_narrator").assertIsSelected()
    }
}
