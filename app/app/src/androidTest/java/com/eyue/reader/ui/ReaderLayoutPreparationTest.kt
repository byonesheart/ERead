package com.eyue.reader.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.junit4.createComposeRule
import com.eyue.reader.data.Book
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.Chapter
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.ReaderSettings
import com.eyue.reader.data.SourceTextAlignment
import com.eyue.reader.data.SourceTextStyle
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReaderLayoutPreparationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: File
    private lateinit var context: Context

    @Before fun setUp() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        root = File(base.cacheDir, "layout-preparation-test-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        }
    }

    @After fun tearDown() { root.deleteRecursively() }

    @Test fun fractionalDensityUsesTheActualComposePerEdgePadding() {
        val density = Density(2.8125f, 1.23f)
        var safeArea = IntSize.Zero
        var body = IntSize.Zero
        var content = IntSize.Zero
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides density) {
                // This is the reader's modifier order, with a measured safe area supplied by its host.
                Box(Modifier.requiredSize(200.dp, 300.dp).onSizeChanged { safeArea = it }
                    .padding(top = ReaderBodyTopPadding, bottom = ReaderBodyBottomPadding).onSizeChanged { body = it }
                    .padding(horizontal = ReaderTextHorizontalPadding, vertical = ReaderTextVerticalPadding)
                    .onSizeChanged { content = it })
            }
        }
        compose.runOnIdle {
            assertTrue(safeArea.width > 0 && safeArea.height > 0)
            assertEquals(body, readerBodyViewport(density, safeArea))
            val spec = readerLayoutSpec(ReaderSettings(), density, LayoutDirection.Rtl, body, context)
            assertEquals(content.width, spec.textWidth)
            assertEquals(content.height, spec.textHeight)
            assertEquals(LayoutDirection.Rtl, spec.layoutDirection)
            assertEquals(density.density, spec.density.density, 0f)
            assertEquals(density.fontScale, spec.density.fontScale, 0f)
            // At this density the old aggregate 48dp rounding differs by one pixel from two 24dp edges.
            assertTrue(spec.textWidth != body.width - with(density) { 48.dp.roundToPx() })
        }
    }

    @Test fun preparationWritesOnlyTheResumeChapterAndASecondCallOnlyCompletesProgress() = runBlocking {
        val texts = listOf(
            "第一章 标题\n" + "第一章正文甲乙丙丁。包含足够文字以验证多个阅读页面。\n".repeat(140),
            "第二章 标题\n" + "第二章正文与上一章不同。分页需要保留当前的源格式。\n".repeat(110))
        val chapters = texts.mapIndexed { index, text ->
            Chapter(title = "第${index + 1}章", textPath = File(root, "chapter-$index.txt").apply { writeText(text) }.absolutePath,
                sourceStyles = listOf(SourceTextStyle(0, text.indexOf('\n') + 1, fontScale = 1.4f,
                    bold = true, alignment = SourceTextAlignment.CENTER, paragraph = true)))
        }
        val book = Book(id = UUID.randomUUID().toString(), title = "预排版缓存回归", format = BookFormat.TXT,
            mode = LibraryMode.NOVEL, chapters = chapters, chapterIndex = 1, contentVersion = 3)
        // Preparing the resume chapter must not wait on, or read, an unrelated source file.
        File(chapters.first().textPath!!).delete()
        val settings = ReaderSettings(fontSize = 18f, lineHeight = 1.7f, letterSpacing = .2f)
        val density = Density(2.8125f, 1.23f)
        val spec = readerLayoutSpec(settings, density, LayoutDirection.Rtl,
            readerBodyViewport(density, IntSize(700, 1100)), context)
        val firstProgress = mutableListOf<Float>()
        prepaginateBook(context, book, settings, spec) { firstProgress += it }
        assertEquals(0f, firstProgress.first())
        assertEquals(1f, firstProgress.last())
        val cacheFiles = File(context.cacheDir, "reader-chapter-page-cache").listFiles().orEmpty()
        assertEquals(1, cacheFiles.size)
        assertTrue(cacheFiles.single().length() > 0)
        val pages = readerCachedChapterPages(context, book, 1, settings, spec.density, spec.layoutDirection,
            spec.textWidth, spec.textHeight, texts[1]) { error("The reader must reuse the prepared chapter layout") }
        assertTrue(pages.size > 1)
        assertEquals(texts[1].length, pages.last().end)
        val secondProgress = mutableListOf<Float>()
        prepaginateBook(context, book, settings, spec) { secondProgress += it }
        // A pagination miss also invokes readerPaginate's final 1f before the helper completes.
        assertEquals(listOf(0f, 1f), secondProgress)
        assertEquals(pages, readerCachedChapterPages(context, book, 1, settings, spec.density, spec.layoutDirection,
            spec.textWidth, spec.textHeight, texts[1]) { error("Repeated preparation must preserve the same chapter cache") })
    }
}
