package com.eyue.reader.ui

import com.eyue.reader.data.InlineImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderScrollIndexTest {
    @Test fun blocksCoverAllTextAndImagesWithBoundedSurrogateSafeRanges() {
        val text = "正文😀甲乙\n".repeat(1_000) + "尾声"
        val images = listOf(InlineImage(0, "cover"), InlineImage(1_204, "middle"), InlineImage(1_204, "repeat"), InlineImage(text.length, "end"))
        val blocks = readerScrollBlocks(text, images)
        var cursor = 0
        blocks.forEach { block ->
            assertEquals(cursor, block.start)
            assertTrue(block.end - block.start <= 1_024)
            if (block.imageIndex < 0) {
                assertTrue(block.end > cursor)
                if (block.end < text.length) assertFalse(Character.isHighSurrogate(text[block.end - 1]) && Character.isLowSurrogate(text[block.end]))
                cursor = block.end
            } else assertEquals(images[block.imageIndex].offset, cursor)
        }
        assertEquals(text.length, cursor)
        assertEquals(images.indices.toList(), blocks.filter { it.imageIndex >= 0 }.map { it.imageIndex })
    }

    @Test fun oneLongParagraphHasNoMissingCharactersAtChunkBoundaries() {
        val text = "a".repeat(1_023) + "😀" + "正文".repeat(2_000)
        val blocks = readerScrollBlocks(text, emptyList())
        assertEquals(1_023, blocks.first().end)
        assertEquals(text, blocks.joinToString("") { text.substring(it.start, it.end) })
    }

    @Test(timeout = 10_000) fun tenMegabyteParagraphIndexesInBoundedWindows() {
        val text = "正文😀".repeat(10 * 1024 * 1024 / "正文😀".toByteArray().size + 1)
        val blocks = readerScrollBlocks(text, emptyList())
        assertTrue(blocks.size > 1_000)
        assertEquals(0, blocks.first().start)
        assertEquals(text.length, blocks.last().end)
        blocks.zipWithNext().forEach { (left, right) -> assertEquals(left.end, right.start) }
        assertTrue(blocks.all { it.end - it.start <= 1_024 })
    }

    @Test fun progressAndSeekIncludeDuplicateImageSlotsAndCrossChapters() {
        val index = ReaderScrollIndex(listOf("abcd", "xy"), listOf(listOf(InlineImage(0, "cover"),
            InlineImage(2, "one"), InlineImage(2, "two")), emptyList()))
        assertEquals(ReaderScrollLocation(0, 0, 0), index.location(0f))
        assertEquals(ReaderScrollLocation(0, 0), index.location(1f / 8))
        assertEquals(ReaderScrollLocation(0, 2, 1), index.location(3f / 8))
        assertEquals(ReaderScrollLocation(0, 2, 2), index.location(4f / 8))
        assertEquals(ReaderScrollLocation(1, 0), index.location(7f / 8))
        assertEquals(ReaderScrollLocation(1, 1), index.location(1f))
        assertTrue(index.progress(0, 2, 1) < index.progress(0, 2, 2))
        assertEquals(1f, index.progress(1, 2, null), 0f)
    }

    @Test fun seekDoesNotSplitEmojiAndImageOnlyBooksKeepDistinctPositions() {
        val index = ReaderScrollIndex(listOf("a😀b"), listOf(emptyList()))
        assertEquals(ReaderScrollLocation(0, 1), index.location(2f / 3))
        val illustrated = ReaderScrollIndex(listOf(""), listOf(listOf(InlineImage(0, "one"), InlineImage(0, "two"))))
        assertEquals(ReaderScrollLocation(0, 0, 1), illustrated.location(1f))
        assertEquals(.5f, illustrated.progress(0, 0, 0), 0f)
        assertEquals(1f, illustrated.progress(0, 0, 1), 0f)
        assertEquals(listOf(ReaderTextPage(0, 0)), readerScrollBlocks("", emptyList()))
    }
}
