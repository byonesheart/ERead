package com.eyue.reader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eyue.reader.data.ReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ReaderLineHeightTest {
    @get:Rule val compose = createComposeRule()

    @Test fun paragraphGapsAndArtificialScrollBlockSeamsHaveDifferentBaselineSpacing() {
        // Original prose exercises real paragraph boundaries, including boundaries between fields.
        val text = "树木沿着道路伸向远处，微风拂过草地，雨后的天空很明亮。\n短句。\n".repeat(80)
        val blocks = readerScrollBlocks(text, emptyList())
        assertTrue(blocks.size > 2)
        assertTrue(blocks.first().end <= 1_024)
        assertEquals(text, blocks.joinToString("") { text.substring(it.start, it.end) })
        val density = Density(1f, 1f)
        val spec = readerLayoutSpec(ReaderSettings(fontName = "系统黑体", fontSize = 20f, lineHeight = 2f),
            density, LayoutDirection.Ltr, IntSize(320, 640), ApplicationProvider.getApplicationContext<Context>())
        val source = ReaderSourceStyles(text, emptyList())
        val uninterrupted = "长路上的清风缓缓经过树林，枝叶在日光中轻轻摇动。".repeat(100)
        val artificial = readerScrollBlocks(uninterrupted, emptyList())
        assertEquals(1_024, artificial.first().end)
        val artificialSource = ReaderSourceStyles(uninterrupted, emptyList())
        val selectedBlocks = blocks.take(2) + artificial.take(2)
        val projected = blocks.take(2).map { source.apply(text, it.start, it.end, spec.textStyle) } +
            artificial.take(2).map { artificialSource.apply(uninterrupted, it.start, it.end, spec.textStyle) }
        val footerGaps = blocks.take(2).map { readerParagraphGapAfter(text, it.end) } +
            artificial.take(2).map { readerParagraphGapAfter(uninterrupted, it.end) }
        assertTrue(footerGaps[0])
        assertTrue(footerGaps.drop(2).none { it })
        assertTrue(projected.all { it.paragraphStyles.isEmpty() })
        val displayed = Array(4) { AtomicReference<TextLayoutResult?>() }
        val measured = Array(4) { AtomicReference<TextLayoutResult?>() }
        val origins = Array(4) { AtomicReference<Float?>() }
        val gap = with(density) { (spec.textStyle.fontSize.value * ReaderParagraphGapEm).sp.toDp() }

        compose.setContent {
            CompositionLocalProvider(LocalDensity provides density, LocalLayoutDirection provides LayoutDirection.Ltr) {
                val measurer = rememberTextMeasurer()
                SideEffect {
                    projected.forEachIndexed { index, paragraph ->
                        measured[index].set(measurer.measure(readerParagraphText(paragraph, spec.textStyle).text, spec.textStyle,
                            constraints = Constraints(minWidth = spec.textWidth, maxWidth = spec.textWidth)))
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    // Only genuine paragraph endings receive the reader's display-only footer gap.
                    Column(Modifier.width(spec.textWidth.dp).verticalScroll(rememberScrollState())) {
                        projected.forEachIndexed { index, paragraph ->
                            ReaderSelectableText(paragraph, spec.textStyle, chapter = 0, page = index,
                                pageStart = selectedBlocks[index].start, selectionReset = 0, onAction = { _, _ -> },
                                modifier = Modifier.fillMaxWidth().padding(bottom = if (footerGaps[index]) gap else 0.dp).onGloballyPositioned {
                                    origins[index].set(it.positionInRoot().y)
                                }, onTextLayout = { displayed[index].set(it) })
                        }
                    }
                }
            }
        }
        compose.waitUntil(10_000) {
            displayed.all { it.get() != null } && measured.all { it.get() != null } && origins.all { it.get() != null }
        }
        val layouts = displayed.map { requireNotNull(it.get()) }
        val expectedSpacing = 40f
        val expectedGap = 16f
        layouts.forEachIndexed { index, layout ->
            assertTrue(layout.lineCount > 2)
            assertEquals("Pagination measurement and displayed block height", requireNotNull(measured[index].get()).size.height,
                layout.size.height)
            val rendered = layout.layoutInput.text.text
            val spacerLines = (0 until layout.lineCount).filter { line ->
                rendered.substring(layout.getLineStart(line), layout.getLineEnd(line, visibleEnd = false)).contains('\u200B')
            }
            if (index < 2) assertTrue(spacerLines.isNotEmpty()) else assertTrue(spacerLines.isEmpty())
            spacerLines.forEach { line ->
                assertEquals("Display-only spacer height", expectedGap, layout.getLineBottom(line) - layout.getLineTop(line), 1f)
            }
            val bodyLines = (0 until layout.lineCount).filter { it !in spacerLines }
            bodyLines.zipWithNext().forEach { (previous, line) ->
                val intervening = spacerLines.count { it in (previous + 1) until line }
                assertEquals("Body baselines inside block $index, line $line", expectedSpacing + expectedGap * intervening,
                    layout.getLineBaseline(line) - layout.getLineBaseline(previous), 1f)
            }
        }
        fun seam(first: Int): Float = requireNotNull(origins[first + 1].get()) + layouts[first + 1].getLineBaseline(0) -
            (requireNotNull(origins[first].get()) + layouts[first].getLineBaseline(layouts[first].lineCount - 1))
        assertEquals("Genuine paragraph boundary between blocks", expectedSpacing + expectedGap, seam(0), 1f)
        assertEquals("Artificial 1024-character boundary inside one paragraph", expectedSpacing, seam(2), 1f)
    }
}
