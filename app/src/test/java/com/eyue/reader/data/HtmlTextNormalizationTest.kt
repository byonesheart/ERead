package com.eyue.reader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException
import kotlin.random.Random

class HtmlTextNormalizationTest {
    @Test fun whitespaceAndUtf16BoundariesMatchThePreviousNormalizer() {
        val random = Random(37)
        val chars = "甲乙🙂\n\t \u00a0\r"
        val cases = listOf("", " \n\t", "\n标题\n\n甲🙂\n  \n\n乙\u00a0\n ", "甲🙂\n\n引用\n\n尾声") +
            List(100) { buildString { repeat(200) { append(chars[random.nextInt(chars.length)]) } } }
        cases.forEach { raw ->
            val (text, offsets) = previous(raw)
            val normalized = normalizeHtmlText(raw, (0..raw.length).reversed().toList().toIntArray())
            assertEquals(text, normalized.text)
            offsets.forEachIndexed { index, value -> assertEquals("UTF-16 offset $index", value, normalized.offset(index)) }
        }
    }

    @Test fun adjacentImagesAndStyleEndpointsShareExactOffsets() {
        val raw = "  标题\n\n甲🙂\n\n\n尾声  "
        val positions = intArrayOf(0, 2, 4, 9, 9, 12, raw.length)
        val normalized = normalizeHtmlText(raw, positions)
        val (text, offsets) = previous(raw)
        assertEquals(text, normalized.text)
        positions.forEach { assertEquals(offsets[it], normalized.offset(it)) }
        assertEquals(positions.distinct().size, normalized.mappedBoundaryCount)
    }

    @Test fun tenMillionCharactersStoreOnlyRequestedBoundaries() {
        val raw = "正文".repeat(5_000_000)
        val normalized = normalizeHtmlText(raw, intArrayOf(100, raw.length))
        assertEquals(raw, normalized.text)
        assertEquals(2, normalized.mappedBoundaryCount)
        assertEquals(100, normalized.offset(100))
        assertEquals(raw.length, normalized.offset(raw.length))
    }

    @Test fun plainChaptersDoNotAllocateAnOffsetMap() {
        val normalized = normalizeHtmlText("甲\n\n\n乙", intArrayOf())
        assertEquals("甲\n\n乙", normalized.text)
        assertEquals(0, normalized.mappedBoundaryCount)
    }

    @Test fun cancellationIsCheckedDuringLongTextConversion() {
        val cancelled = CancellationException("cancel")
        var checks = 0
        val error = runCatching {
            normalizeHtmlText("甲".repeat(50_000), intArrayOf()) { if (++checks == 3) throw cancelled }
        }.exceptionOrNull()
        assertTrue(error === cancelled)
    }

    private fun previous(raw: String): Pair<String, IntArray> {
        val body = StringBuilder()
        val offsets = IntArray(raw.length + 1)
        var newlines = 0
        raw.forEachIndexed { index, original ->
            val char = if (original == '\u00A0') ' ' else original
            if (!(char in " \t" && body.lastOrNull() == '\n') && !(char == '\n' && newlines >= 2)) body.append(char)
            if (char == '\n') newlines++ else if (char !in " \t" || body.lastOrNull() != '\n') newlines = 0
            offsets[index + 1] = body.length
        }
        val untrimmed = body.toString()
        val leading = untrimmed.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: untrimmed.length
        val text = untrimmed.trim()
        offsets.indices.forEach { offsets[it] = (offsets[it] - leading).coerceIn(0, text.length) }
        return text to offsets
    }
}
