package com.eyue.reader.data

import org.junit.Assert.*
import org.junit.Test

class TextReplacementTest {
    @Test fun literalSearchTrimsQueryIgnoresCaseAndReplacesEveryOriginalMatchWithoutRecursion() {
        val original = "😀Ab甲".repeat(150) + "结束"
        val replacements = literalTextReplacements(original, " aB ", "AbAb\r\n")
        assertEquals(150, replacements.size)
        assertEquals(TextReplacement(2, 4, "AbAb\n"), replacements.first())
        val plan = TextReplacementPlan(original, replacements)
        assertEquals("😀AbAb\n甲".repeat(150) + "结束", plan.text)
        assertEquals(plan.text.indexOf("结束"), plan.offset(original.indexOf("结束")))
        assertEquals(plan.text.length, plan.offset(original.length))
    }

    @Test fun overlappingLiteralCandidatesAreConsumedOnceAndImageMarkersCannotBeReplaced() {
        val changes = literalTextReplacements("aaaa", "aa", "aaa")
        assertEquals(listOf(TextReplacement(0, 2, "aaa"), TextReplacement(2, 4, "aaa")), changes)
        assertEquals("aaaaaa", TextReplacementPlan("aaaa", changes).text)
        assertEquals(emptyList<TextReplacement>(), literalTextReplacements("甲\uFFFC乙", "甲\uFFFC", "图"))
        assertEquals(emptyList<TextReplacement>(), literalTextReplacements("正文", " \n ", "替换"))
        assertEquals("😀末尾", TextReplacementPlan("😀猫猫末尾", literalTextReplacements("😀猫猫末尾", "猫", "")).text)
    }

    @Test fun mappingIncludesEarlierEditsAndKeepsInsertionBoundariesLeftStickyForReadingAnchors() {
        val plan = TextReplacementPlan("abcdefghij", listOf(TextReplacement(7, 9, "Z"), TextReplacement(2, 4, "LONG"), TextReplacement(5, 5, "++")))
        assertEquals("abLONGe++fgZj", plan.text)
        assertEquals(2, plan.offset(2))
        assertEquals(3, plan.offset(3))
        assertEquals(6, plan.offset(4))
        assertEquals(7, plan.offset(5))
        assertEquals(9, plan.offset(5, afterInsertion = true))
        assertEquals(11, plan.offset(7))
        assertEquals(12, plan.offset(9))
        assertEquals(13, plan.offset(10))
    }

    @Test fun plansRejectOverlapsDuplicateInsertionPointsAndInvalidRanges() {
        listOf(listOf(TextReplacement(1, 4, "a"), TextReplacement(3, 5, "b")),
            listOf(TextReplacement(2, 2, "a"), TextReplacement(2, 2, "b")),
            listOf(TextReplacement(-1, 1, "a")), listOf(TextReplacement(2, 7, "a"))).forEach { changes ->
            assertThrows(IllegalArgumentException::class.java) { TextReplacementPlan("abcdef", changes) }
        }
    }
}
