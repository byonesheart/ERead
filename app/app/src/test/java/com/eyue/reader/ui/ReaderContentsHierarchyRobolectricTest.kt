package com.eyue.reader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.eyue.reader.data.Book
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.Chapter
import com.eyue.reader.data.ComicSection
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.PageSource
import com.eyue.reader.data.ReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderContentsHierarchyRobolectricTest {
    @Test fun nativeFourLevelContentsStayCollapsedAndJumpToTheExistingReadingFragment() {
        val source = novel.copy(chapters = List(8) { Chapter(id = "f$it", title = "内部片段 $it") }, publicationContents = listOf(
            com.eyue.reader.data.PublicationContentsEntry("suite", "合集", 0),
            com.eyue.reader.data.PublicationContentsEntry("volume", "分册", 1, "suite"),
            com.eyue.reader.data.PublicationContentsEntry("chapter", "章节", 2, "volume"),
            com.eyue.reader.data.PublicationContentsEntry("section", "小节", 3, "chapter"),
            com.eyue.reader.data.PublicationContentsEntry("end", "后记", 6, "volume")))
        var jumped: Pair<Int, Int>? = null
        compose.setContent { MaterialTheme { ReaderContentsPanel(source, 4, 0, ReaderSettings(), {},
            cachedSpeechChapters = setOf(3, 4, 5)) { chapter, page -> jumped = chapter to page } } }
        compose.onNodeWithText("5 项").assertExists()
        compose.onNodeWithTag("reader_contents_entry_publication/section").assertIsDisplayed()
        compose.onNodeWithTag("reader_contents_cached_speech_3").assertExists()
        compose.onNodeWithTag("reader_contents_entry_publication/section").performClick()
        compose.runOnIdle { assertEquals(3 to 0, jumped); jumped = null }
        compose.onNodeWithTag("reader_contents_toggle_publication/volume").performClick()
        compose.onNodeWithTag("reader_contents_entry_publication/section").assertDoesNotExist()
        compose.runOnIdle { assertNull(jumped) }
    }

    @get:Rule val compose = createComposeRule()
    private val novel = Book(id = "nested", title = "分卷小说", format = BookFormat.EPUB, mode = LibraryMode.NOVEL,
        chapters = listOf(
            Chapter(id = "one", title = "第一章 启程", tocParentTitle = "第一卷 起点", characterCount = 100),
            Chapter(id = "two", title = "第二章 相遇", tocParentTitle = "第一卷 起点", characterCount = 100),
            Chapter(id = "three", title = "第三章 山海", tocParentTitle = "第二卷 远行", characterCount = 100),
            Chapter(id = "four", title = "第四章 回家", tocParentTitle = "第二卷 远行", characterCount = 100)))

    @Test fun parentStaysPinnedWhileItsChildrenScrollAndChangesWithTheVisibleGroup() {
        val large = novel.copy(chapters = List(90) { index -> Chapter(id = "c$index", title = "第${index + 1}章",
            tocParentTitle = if (index < 45) "第一卷 起点" else "第二卷 远行") })
        var chapter by mutableStateOf(0)
        var settings by mutableStateOf(ReaderSettings())
        compose.setContent { MaterialTheme { ReaderContentsPanel(large, chapter, 0, settings, {},
            onHierarchyChange = { settings = settings.copy(hierarchicalContents = it) }) { _, _ -> } } }
        compose.onNodeWithTag("reader_contents_list").performScrollToIndex(25)
        compose.onNode(hasText("第一卷 起点") and hasAnyAncestor(hasTestTag("reader_contents_pinned_parent"))).assertIsDisplayed()
        compose.onNodeWithTag("reader_contents_entry_c0/parent").assertDoesNotExist()
        compose.runOnIdle { chapter = 65 }
        compose.onNode(hasText("第二卷 远行") and hasAnyAncestor(hasTestTag("reader_contents_pinned_parent"))).assertIsDisplayed()
        compose.onNodeWithTag("reader_contents_hierarchy").performClick()
        compose.onNodeWithTag("reader_contents_pinned_parent").assertDoesNotExist()
    }

    @Test fun currentGroupStartsExpandedAndDisclosureButtonsDoNotJump() {
        var jumped: Pair<Int, Int>? = null
        compose.setContent { MaterialTheme { ReaderContentsPanel(novel, 0, 0, ReaderSettings(), {}) { chapter, page ->
            jumped = chapter to page
        } } }
        compose.onNodeWithTag("reader_contents_hierarchy").assertIsOn()
        compose.onNodeWithTag("reader_contents_entry_one").assertExists()
        compose.onNodeWithTag("reader_contents_entry_two").assertExists()
        compose.onNodeWithTag("reader_contents_entry_three").assertDoesNotExist()
        compose.onNode(hasText("02") and hasAnyAncestor(hasTestTag("reader_contents_entry_three/parent")), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("reader_contents_toggle_three/parent").performScrollTo().performClick()
        compose.onNodeWithTag("reader_contents_entry_three").assertExists()
        compose.onNodeWithTag("reader_contents_entry_four").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(3 to 0, jumped); jumped = null }
        compose.onNodeWithTag("reader_contents_toggle_three/parent").performScrollTo().performClick()
        compose.onNodeWithTag("reader_contents_entry_three").assertDoesNotExist()
        compose.runOnIdle { assertNull(jumped) }
        compose.onNodeWithTag("reader_contents_list").performScrollToIndex(0)
        compose.onNodeWithTag("reader_contents_toggle_one/parent").performClick()
        compose.onNodeWithTag("reader_contents_entry_one").assertDoesNotExist()
        compose.onNodeWithTag("reader_contents_entry_two").assertDoesNotExist()
    }

    @Test fun hierarchySwitchRestoresAllPhysicalChaptersAndCanReopenTheCurrentGroup() {
        var settings by mutableStateOf(ReaderSettings())
        compose.setContent { MaterialTheme { ReaderContentsPanel(novel, 0, 0, settings, {},
            onHierarchyChange = { settings = settings.copy(hierarchicalContents = it) }) { _, _ -> } } }
        compose.onNodeWithTag("reader_contents_hierarchy").performClick().assertIsOff()
        compose.onNodeWithTag("reader_contents_entry_one/parent").assertDoesNotExist()
        listOf("one", "two", "three", "four").forEach {
            compose.onNodeWithTag("reader_contents_entry_$it").performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithTag("reader_contents_hierarchy").performClick().assertIsOn()
        compose.onNodeWithTag("reader_contents_entry_one").assertIsDisplayed()
        compose.onNodeWithTag("reader_contents_entry_three").assertDoesNotExist()
    }

    @Test fun changingTheReadingPositionExpandsAndLocatesItsPreviouslyCollapsedGroup() {
        var chapter by mutableStateOf(0)
        compose.setContent { MaterialTheme { ReaderContentsPanel(novel, chapter, 0, ReaderSettings(), {}) { _, _ -> } } }
        compose.onNodeWithTag("reader_contents_entry_four").assertDoesNotExist()
        compose.runOnIdle { chapter = 3 }
        compose.onNodeWithTag("reader_contents_entry_four").assertIsDisplayed()
        compose.onNode(hasText("在读") and hasAnyAncestor(hasTestTag("reader_contents_entry_four")), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("reader_contents_list").performScrollToIndex(0)
        compose.onNodeWithTag("reader_contents_toggle_three/parent").performScrollTo().performClick()
        compose.onNodeWithTag("reader_contents_entry_four").assertDoesNotExist()
        compose.onNode(hasText("在读") and hasAnyAncestor(hasTestTag("reader_contents_entry_three/parent")), useUnmergedTree = true).assertExists()
        compose.runOnIdle { chapter = 2 }
        compose.onNodeWithTag("reader_contents_entry_three").assertIsDisplayed()
    }

    @Test fun speechGroupSelectionUsesPhysicalChaptersWithoutDuplicatingFirstChapterBadges() {
        var generated: Set<Int>? = null
        compose.setContent { MaterialTheme { ReaderContentsPanel(novel, 0, 0, ReaderSettings(), {},
            onGenerateSpeech = { generated = it }, cachedSpeechChapters = setOf(0, 1)) { _, _ -> } } }
        compose.onNodeWithTag("reader_contents_generate_speech").performClick()
        compose.onNodeWithTag("reader_contents_list").performScrollToIndex(0)
        compose.onNodeWithTag("reader_contents_select_group_one/parent").performClick()
        compose.onNodeWithTag("reader_contents_select_0").assertIsOn()
        compose.onNodeWithTag("reader_contents_select_1").assertIsOn()
        compose.onAllNodesWithTag("reader_contents_cached_speech_0", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithTag("reader_contents_toggle_three/parent").performScrollTo().performClick()
        compose.onNodeWithTag("reader_contents_select_group_three/parent").performClick()
        compose.onNodeWithTag("reader_contents_select_3").performScrollTo().performClick()
        compose.onNodeWithTag("reader_contents_speech_generate").performClick()
        compose.runOnIdle { assertEquals(setOf(0, 1, 2), generated) }
    }

    @Test fun comicSectionsKeepNestedPageTargetsAndCanCollapseOrShowAllSections() {
        val comic = novel.copy(mode = LibraryMode.COMIC, format = BookFormat.IMAGES, chapters = listOf(
            Chapter(id = "comic", title = "第1话", pages = List(8) { PageSource("$it.png") }, subsections = listOf(
                ComicSection("上篇", 2, 6, listOf(ComicSection("场景1", 2, 4), ComicSection("场景2", 4, 6))),
                ComicSection("下篇", 6, 8)))))
        var settings by mutableStateOf(ReaderSettings())
        var jumped: Pair<Int, Int>? = null
        compose.setContent { MaterialTheme { ReaderContentsPanel(comic, 0, 4, settings, {},
            onHierarchyChange = { settings = settings.copy(hierarchicalContents = it) }) { chapter, page -> jumped = chapter to page } } }
        compose.onNodeWithTag("reader_contents_entry_comic/0/1").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(0 to 4, jumped) }
        compose.onNodeWithTag("reader_contents_list").performScrollToIndex(0)
        compose.onNodeWithTag("reader_contents_toggle_comic/0").performScrollTo().performClick()
        compose.onNodeWithTag("reader_contents_entry_comic/0/1").assertDoesNotExist()
        compose.onNodeWithTag("reader_contents_hierarchy").performClick().assertIsOff()
        compose.onNodeWithTag("reader_contents_entry_comic/0/1").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("reader_contents_entry_comic/1").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0 to 6, jumped) }
    }
}
