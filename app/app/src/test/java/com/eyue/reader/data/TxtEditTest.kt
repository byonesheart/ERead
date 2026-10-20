package com.eyue.reader.data

import org.junit.Assert.*
import org.junit.Test

class TxtEditTest {
    @Test fun editingRepeatedTextKeepsNotesInTheirOriginalChapterAndShiftsFollowingOffsets() {
        val texts = listOf("第一章\n相同的句子\n结尾正文", "第二章\n相同的句子\n结尾正文")
        val edit = TxtEdit(texts, 0, 4, 9, "改过的较长句子", DEFAULT_CHAPTER_RULE)
        val note = Annotation(bookId = "book", chapterIndex = 1, pageIndex = 3,
            characterOffset = 4, endOffset = 9, quote = "相同的句子", note = "第二章的笔记")
        val mapped = edit.annotation(note)
        assertEquals(1, mapped.chapterIndex)
        assertEquals(4, mapped.characterOffset)
        assertEquals(9, mapped.endOffset)
        assertEquals(note.quote, mapped.quote)
        assertEquals(note.id, mapped.id)
    }

    @Test fun editsCanCreateNewChaptersWithoutLosingReadingAndNoteAnchors() {
        val text = "第一章\n甲\n乙\n丙"
        val edit = TxtEdit(listOf(text), 0, 6, 6, "第二章 新章\n", DEFAULT_CHAPTER_RULE)
        assertEquals(listOf("第一章", "第二章 新章"), edit.chapters.map { it.title })
        val position = edit.position(0, 8)
        assertEquals(1, position.first)
        assertEquals('丙', edit.chapters[position.first].text[position.second])
    }

    @Test fun overlappingNoteRetainsItsNoteAndUsesTheEditedQuote() {
        val edit = TxtEdit(listOf("第一章\n原句子。"), 0, 4, 8, "新段落。", DEFAULT_CHAPTER_RULE)
        val note = edit.annotation(Annotation(bookId = "book", chapterIndex = 0, pageIndex = 0,
            characterOffset = 4, endOffset = 8, quote = "原句子。", note = "批注"))
        assertEquals("新段落。", note.quote)
        assertEquals("批注", note.note)
        assertEquals(4, note.characterOffset)
        assertEquals(8, note.endOffset)
    }

    @Test fun legacyNoteGetsARangeAndBookmarksRemainPoints() {
        val edit = TxtEdit(listOf("第一章\n一句话。"), 0, 8, 8, "\n追加", DEFAULT_CHAPTER_RULE)
        val note = edit.annotation(Annotation(bookId = "b", chapterIndex = 0, pageIndex = 0,
            characterOffset = 4, quote = "一句话。", note = "旧笔记"))
        assertEquals(8, note.endOffset)
        val bookmark = edit.annotation(note.copy(kind = AnnotationKind.BOOKMARK, note = "", endOffset = 0))
        assertEquals(0, bookmark.endOffset)
    }

    @Test fun fullChapterReplacementUsesNormalizedNewlinesAndRegeneratesTitle() {
        val text = "第一章\n正文"
        val edit = TxtEdit(listOf(text), 0, 0, text.length, "第二章 新标题\r\n新正文", DEFAULT_CHAPTER_RULE)
        assertEquals("第二章 新标题\n新正文", edit.source)
        assertEquals("第二章 新标题", edit.chapters.single().title)
    }

    @Test fun invalidRangesAndEmptyBookAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { TxtEdit(listOf("正文"), 0, 3, 4, "字", DEFAULT_CHAPTER_RULE) }
        assertThrows(IllegalArgumentException::class.java) { TxtEdit(listOf("正文"), 0, 0, 2, "", DEFAULT_CHAPTER_RULE) }
    }

    @Test fun wholeBookReplacementKeepsRepeatedNotesInTheirChapterAndMapsAllEdits() {
        val texts = listOf("第一章\n" + "😀猫 ".repeat(130) + "本章末尾。", "第二章\n猫猫\n第二章末尾。")
        val replacements = texts.indices.associateWith { literalTextReplacements(texts[it], " 猫 ", "小猫") }
        val edit = TxtEdit(texts, replacements, DEFAULT_CHAPTER_RULE)
        assertEquals(132, replacements.values.sumOf { it.size })
        assertEquals(listOf("第一章", "第二章"), edit.chapters.map { it.title })
        assertEquals(texts.joinToString("\n\n").replace("猫", "小猫"), edit.source)
        val oldStart = texts[1].indexOf("猫")
        val note = edit.annotation(Annotation(bookId = "b", chapterIndex = 1, pageIndex = 4,
            characterOffset = oldStart, endOffset = oldStart + 2, quote = "猫猫", note = "第二章笔记"))
        assertEquals(1, note.chapterIndex)
        assertEquals("小猫小猫", note.quote)
        assertEquals("第二章笔记", note.note)
        val tail = edit.position(1, texts[1].indexOf("第二章末尾"))
        assertEquals(1, tail.first)
        assertEquals(edit.chapters[1].text.indexOf("第二章末尾"), tail.second)
    }

    @Test fun deletingAllLiteralMatchesStillRejectsAnEmptyBookAndValidatesChapterKeys() {
        assertThrows(IllegalArgumentException::class.java) {
            TxtEdit(listOf("猫猫"), mapOf(0 to literalTextReplacements("猫猫", "猫", "")), DEFAULT_CHAPTER_RULE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TxtEdit(listOf("正文"), mapOf(1 to listOf(TextReplacement(0, 1, "新"))), DEFAULT_CHAPTER_RULE)
        }
    }
}
