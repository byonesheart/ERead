package com.eyue.reader.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eyue.reader.data.Book
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.Chapter
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.ReaderSettings
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class ReaderToolbarTintTest {
    @get:Rule val compose = createComposeRule()
    private val root = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir,
        "toolbar-tint-${UUID.randomUUID()}").apply { mkdirs() }
    private var settings by mutableStateOf(ReaderSettings(nightMode = true, showTime = false, showBattery = false))

    @After fun tearDown() { root.deleteRecursively() }

    @Test fun translucentToolbarsUseThemeContentColorsInsteadOfAmbientBlack() {
        val book = Book(title = "工具栏颜色测试", format = BookFormat.MOBI, mode = LibraryMode.NOVEL,
            chapterIndex = 1, chapters = (0..2).map { index ->
                Chapter(title = "第${index + 1}章", textPath = File(root, "$index.txt").apply {
                    writeText("阅读正文。\n".repeat(20))
                }.absolutePath)
            })
        compose.setContent {
            MaterialTheme(colorScheme = if (settings.nightMode) DarkAppColors else LightAppColors) {
                CompositionLocalProvider(LocalContentColor provides Color.Black) {
                    ReaderScreen(book, settings, emptyList(), {}, { _, _, _, _, _ -> }, { settings = it },
                        {}, {}, {}, emptyFlow())
                }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("阅读正文").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("reader_loading").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("reader_body").performTouchInput { click() }
        listOf("返回书架", "切换夜间模式", "全文搜索与替换", "上一章", "下一章").forEach {
            assertTint(it, DarkAppColors.onSurface)
        }
        assertTint("工具栏颜色测试", DarkAppColors.onSurface, title = true)
        compose.onNodeWithContentDescription("切换夜间模式").performClick()
        listOf("返回书架", "切换夜间模式", "全文搜索与替换", "上一章", "下一章").forEach {
            assertTint(it, LightAppColors.onSurface)
        }
    }

    private fun assertTint(label: String, expected: Color, title: Boolean = false) {
        val node = if (title) compose.onNodeWithText(label) else compose.onNodeWithContentDescription(label)
        val pixels = node.assertIsDisplayed().captureToImage().toPixelMap()
        assertTrue("$label should use the theme content color", (0 until pixels.width).any { x ->
            (0 until pixels.height).any { y ->
                val actual = pixels[x, y]
                abs(actual.red - expected.red) < .02f && abs(actual.green - expected.green) < .02f &&
                    abs(actual.blue - expected.blue) < .02f
            }
        })
    }
}
