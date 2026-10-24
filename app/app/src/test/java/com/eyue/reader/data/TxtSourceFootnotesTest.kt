package com.eyue.reader.data

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class TxtSourceFootnotesTest {
    private fun annotatedChapters(source: String) = TextParser.split(source, DEFAULT_CHAPTER_RULE).map {
        it.copy(sourceFootnotes = txtSourceFootnotes(it.text))
    }

    @Test fun chapterNumberingRestartsAndTitleReferencesRemainPartOfTheUnchangedText() {
        val source = "第一回 开篇[1]\n原文[2]。\n\n[1] 开篇释义。\n\n[2] 原文释义。\n\n第二回 续篇[1]\n后文[1]。\n\n[1] 续篇释义。"
        val chapters = annotatedChapters(source)
        assertEquals(2, chapters.size)
        assertEquals(source.substringBefore("第二回").trim(), chapters[0].text)
        assertEquals(source.substring(source.indexOf("第二回")).trim(), chapters[1].text)
        assertEquals(listOf("开篇释义。", "原文释义。"), chapters[0].sourceFootnotes.map { it.content })
        assertEquals(listOf("续篇释义。", "续篇释义。"), chapters[1].sourceFootnotes.map { it.content })
        chapters.forEach { chapter -> chapter.sourceFootnotes.forEach { note ->
            assertEquals(note.label, chapter.text.substring(note.start, note.end))
        } }
    }

    @Test fun singleBodyKeepsUtf16OffsetsAndAllRepeatedReferences() {
        val source = "🙂原文[1]与引用[1]，不明[9]。\n\n[1] 一段释文。"
        val chapter = annotatedChapters(source).single()
        assertEquals(source, chapter.text)
        assertEquals(2, chapter.sourceFootnotes.size)
        assertEquals(source.indexOf("[1]"), chapter.sourceFootnotes[0].start)
        assertEquals(source.indexOf("[1]", chapter.sourceFootnotes[0].end), chapter.sourceFootnotes[1].start)
        assertTrue(chapter.sourceFootnotes.all { it.label == "[1]" && it.content == "一段释文。" })
    }

    @Test fun definitionContentAndLaterUnrelatedParagraphsAreNotReferences() {
        val source = "正文[1]。\n\n[1] 释文参见[2]。\n续行内容。\n\n[2] 第二条释文。\n\n后续段落[1]。"
        val note = txtSourceFootnotes(source).single()
        assertEquals(source.indexOf("[1]"), note.start)
        assertEquals("释文参见[2]。\n续行内容。", note.content)
    }

    @Test fun duplicateDefinitionsAndUnreferencedNumberedListsAreLeftAlone() {
        assertTrue(txtSourceFootnotes("正文[1]。\n\n[1] 第一种解释。\n\n[1] 另一个解释。").isEmpty())
        assertTrue(txtSourceFootnotes("[1] 列表第一项。\n[2] 列表第二项。").isEmpty())
        assertTrue(txtSourceFootnotes("正文[01]。\n\n[1] 编号不相同。").isEmpty())
    }

    @Test fun appendixRestartsNumberingInsideTheSameChapterWithoutBorrowingEarlierDefinitions() {
        val text = "第八回 正文\n本回原文[1]及[2]。\n\n[1] 本回第一条释文。\n\n[2] 本回第二条释文。\n\n" +
            "附录\n附录原文[1]和未说明[2]。\n\n[1] 附录释文。"
        val chapter = annotatedChapters(text).single()
        assertEquals(text, chapter.text)
        assertEquals(listOf("本回第一条释文。", "本回第二条释文。", "附录释文。"), chapter.sourceFootnotes.map { it.content })
        assertEquals(text.indexOf("[1]", text.indexOf("附录原文")), chapter.sourceFootnotes.last().start)
    }

    @Test fun compactLegacyChaptersStillRecognizeEachDefinitionWithoutMovingTheMarker() {
        val source = "第一回 开篇\n\n原文[1]与[2]。\n\n[1] 第一条释文。\n\n[2] 第二条释文。"
        val parsed = annotatedChapters(source).single()
        val compacted = TextParser.compactParagraphs(parsed).first
        val rediscovered = txtSourceFootnotes(compacted.text)
        assertEquals(compacted.sourceFootnotes, rediscovered)
        assertEquals(listOf("第一条释文。", "第二条释文。"), rediscovered.map { it.content })
    }

    @Test fun ebookDefinitionsMayTouchTheirNumberWhilePlainTxtKeepsItsStricterRule() {
        val text = "原文[1]和[2]。\n\n[1]第一条释文。\n\n[2]第二条释文。"
        assertTrue(txtSourceFootnotes(text).isEmpty())
        val notes = txtSourceFootnotes(text, allowAttachedDefinition = true)
        assertEquals(listOf("第一条释文。", "第二条释文。"), notes.map { it.content })
        assertEquals(listOf(text.indexOf("[1]"), text.indexOf("[2]")), notes.map { it.start })
        assertTrue(txtSourceFootnotes("[1]列表项。\n[2]列表项。", allowAttachedDefinition = true).isEmpty())
        assertTrue(txtSourceFootnotes("原文[1]。\n\n[1]解释一。\n\n[1]解释二。", allowAttachedDefinition = true).isEmpty())
    }

    @Test(expected = CancellationException::class)
    fun cancellationPropagatesInsteadOfReturningAnEmptyResult() {
        txtSourceFootnotes("正文[1]。\n\n[1] 释文。") { throw CancellationException("cancelled") }
    }

    @Test fun optionalRealTxtDefinitionsAreChapterLocalAndNeverAlterTheSource() {
        val configured = System.getenv("EREAD_TXT_NOTES_SOURCE")
        assumeTrue("Set EREAD_TXT_NOTES_SOURCE for the supplied TXT footnote smoke test", !configured.isNullOrBlank())
        val file = File(requireNotNull(configured))
        val bytes = file.readBytes()
        val text = TextParser.decode(bytes)
        val chapters = annotatedChapters(text)
        assertTrue(chapters.count { it.sourceFootnotes.isNotEmpty() } >= 100)
        val first = chapters.first { it.sourceFootnotes.isNotEmpty() }
        val second = chapters.drop(chapters.indexOf(first) + 1).first { it.sourceFootnotes.isNotEmpty() }
        assertEquals("[1]", first.sourceFootnotes.first().label)
        assertEquals("[1]", second.sourceFootnotes.first().label)
        assertNotEquals(first.sourceFootnotes.first().content, second.sourceFootnotes.first().content)
        assertTrue(chapters.sumOf { it.sourceFootnotes.size } > 1000)
        chapters.forEach { chapter -> chapter.sourceFootnotes.forEach { note ->
            assertEquals(note.label, chapter.text.substring(note.start, note.end))
            assertTrue(note.content.isNotBlank())
        } }
        assertArrayEquals(bytes, file.readBytes())
        println("TXT footnote smoke: chapters=${chapters.size}, chaptersWithNotes=${chapters.count { it.sourceFootnotes.isNotEmpty() }}, references=${chapters.sumOf { it.sourceFootnotes.size }}")
    }
}
