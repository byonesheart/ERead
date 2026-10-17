package com.eyue.reader

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eyue.reader.data.LibraryRepository
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.AnnotationKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.rules.RuleChain
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderSmokeTest {
    val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(ConsentGrantRule()).around(compose)

    private fun awaitHome() {
        val vm = compose.runOnIdle { ViewModelProvider(compose.activity)[ReaderViewModel::class.java] }
        compose.waitUntil(30_000) { !vm.loading }
    }

    private fun revealTools() {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("reader_loading").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithContentDescription("阅读正文").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        if (compose.onAllNodesWithContentDescription("目录").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("reader_body").performTouchInput { click(center) }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("目录").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun libraryModesSettingsAndImportOptionsAreReachable() {
        awaitHome()
        compose.onNodeWithText("书架", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("bookshelf").assertIsDisplayed()
        compose.onNodeWithText("我的书架").assertDoesNotExist()
        compose.onNodeWithText("雨后的旧书店").assertExists()
        compose.onNodeWithText("漫画").performClick()
        compose.onNodeWithText("小云的散步").assertExists()
        compose.onNodeWithTag("top_import").performClick()
        compose.onNodeWithText("导入漫画文件夹").assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("小说").performClick()
        compose.onNodeWithText("设置", useUnmergedTree = true).performClick()
        compose.onNodeWithText("按你的习惯阅读").assertIsDisplayed()
        compose.onNodeWithText("自定义目录规则").performScrollTo().performClick()
        compose.onNodeWithText("目录预览", substring = true).assertExists()
        compose.onNodeWithText("取消").performClick()
    }

    @Test fun novelDirectoryBookmarkAndResumePersist() {
        awaitHome()
        compose.onNodeWithText("小说").performClick()
        compose.onNode(hasText("开始阅读") or hasText("继续阅读")).performClick()
        compose.waitForIdle()
        revealTools()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithContentDescription("目录").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("目录").performClick()
        compose.onNodeWithText("第二章 书页里的车票").performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("第二章 书页里的车票").fetchSemanticsNodes().isNotEmpty()
        }
        revealTools()
        if (compose.onAllNodesWithContentDescription("当前页书签").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithContentDescription("快速添加书签").performClick()
        }
        compose.onNodeWithContentDescription("当前页书签").assertExists()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("返回书架").performClick()
        compose.waitUntil(10_000) {
            LibraryRepository(compose.activity).load().annotations.isNotEmpty()
        }
        val saved = LibraryRepository(compose.activity).load()
        val novel = saved.books.first { it.mode == LibraryMode.NOVEL }
        assertEquals(1, novel.chapterIndex)
        assertTrue(novel.lastReadAt > 0)
        assertTrue(saved.annotations.any { it.bookId == novel.id && it.chapterIndex == 1 && it.kind == AnnotationKind.BOOKMARK })
        compose.onNodeWithContentDescription("书签与笔记").performClick()
        compose.onNodeWithText("书签与笔记").assertIsDisplayed()
    }

    @Test fun repositoryRoundTripsAllSettingsAndComicPages() = runBlocking {
        awaitHome()
        val context = compose.activity
        val repository = LibraryRepository(context)
        val original = repository.load()
        val changed = original.copy(settings = original.settings.copy(fontSize = 23f, volumePaging = true,
            fontName = "跟随系统", nightMode = true, brightness = .35f, letterSpacing = 1.5f))
        repository.save(changed)
        val loaded = repository.load()
        assertEquals(23f, loaded.settings.fontSize, 0f)
        assertTrue(loaded.settings.volumePaging)
        assertTrue(loaded.settings.nightMode)
        assertEquals("跟随系统", loaded.settings.fontName)
        assertEquals(.35f, loaded.settings.brightness, 0f)
        assertEquals(1.5f, loaded.settings.letterSpacing, 0f)
        assertTrue(loaded.books.first { it.mode == LibraryMode.COMIC }.chapters.sumOf { it.pages.size } >= 3)
        repository.save(original)
    }
}
