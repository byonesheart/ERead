package com.eyue.reader.data

import org.junit.Assert.*
import org.junit.Test

class EbookTextEditTest {
    private val original = "标题\n首段正文。\n引用内容。\n末段正文。"

    @Test fun editingBodyKeepsHeadingsOtherParagraphStylesAndAllImages() {
        val heading = SourceTextStyle(0, 2, bold = true, fontScale = 2f, paragraph = true)
        val editedParagraph = SourceTextStyle(3, 8, italic = true, paragraph = true)
        val quote = SourceTextStyle(9, 14, italic = true, paragraph = true)
        val images = listOf(InlineImage(2, "cover.png"), InlineImage(5, "inline.png"), InlineImage(15, "last.png"))
        val chapter = Chapter(title = "章名", textPath = "old.txt", lazySourcePath = "source.json",
            sourceStyles = listOf(heading, editedParagraph, quote), inlineImages = images)
        val edit = EbookTextEdit(0, original, 3, 8, "新的第一段正文。")
        val updated = edit.chapter(chapter, "edited.txt")
        val delta = edit.text.length - original.length
        assertEquals(chapter.id, updated.id)
        assertEquals(chapter.title, updated.title)
        assertEquals("edited.txt", updated.textPath)
        assertNull(updated.lazySourcePath)
        assertEquals(edit.text.length, updated.characterCount)
        assertEquals(listOf(heading, quote.copy(start = quote.start + delta, end = quote.end + delta)), updated.sourceStyles)
        assertEquals(images.map { it.path }, updated.inlineImages.map { it.path })
        assertEquals(15 + delta, updated.inlineImages.last().offset)
    }

    @Test fun editingMiddleChapterDoesNotResplitChaptersOrMoveOtherChapterAnnotations() {
        val chapters = listOf(Chapter(title = "一", textPath = "one.txt"), Chapter(title = "二", textPath = "two.txt"),
            Chapter(title = "三", textPath = "three.txt"))
        val source = "content://documents/original.mobi"
        val book = Book(title = "书名", author = "作者", format = BookFormat.MOBI, mode = LibraryMode.NOVEL,
            chapters = chapters, coverPath = "cover.png", sourceUri = source, sourceLinked = true,
            chapterIndex = 2, characterOffset = 4, pageIndex = 6, progress = .8f, cloudEnabled = true)
        val edit = EbookTextEdit(1, original, 3, 8, "第一章 看起来像标题\n修改正文")
        val updated = edit.book(book, edit.chapter(chapters[1], "edited.txt"), listOf(6, original.length, 10))
        assertSame(chapters[0], updated.chapters[0])
        assertSame(chapters[2], updated.chapters[2])
        assertEquals(chapters.map { it.title }, updated.chapters.map { it.title })
        assertEquals(BookFormat.MOBI, updated.format)
        assertEquals(book.author, updated.author)
        assertEquals(book.coverPath, updated.coverPath)
        assertEquals(source, updated.sourceUri)
        assertTrue(updated.sourceLinked)
        assertTrue(updated.cloudEnabled)
        assertTrue(updated.hasTextEdits)
        assertEquals(book.contentVersion + 1, updated.contentVersion)
        assertEquals(2, updated.chapterIndex)
        assertEquals(4, updated.characterOffset)
        assertEquals(0, updated.pageIndex)
        assertEquals((6 + edit.text.length + 4).toFloat() / (6 + edit.text.length + 10), updated.progress, .00001f)
        val note = Annotation(bookId = book.id, chapterIndex = 2, pageIndex = 3, characterOffset = 1,
            endOffset = 5, quote = "相同正文", note = "留在第三章")
        assertSame(note, edit.annotation(note))
    }

    @Test fun notesProgressAndImageMarksShiftWithinTheEditedChapter() {
        val edit = EbookTextEdit(1, original, 3, 8, "新正文。")
        val note = Annotation(bookId = "b", chapterIndex = 1, pageIndex = 4, characterOffset = 9,
            endOffset = 14, quote = "引用内容。", note = "保留笔记", kind = AnnotationKind.NOTE)
        val moved = edit.annotation(note)
        assertEquals(8, moved.characterOffset)
        assertEquals(13, moved.endOffset)
        assertEquals(note.quote, moved.quote)
        assertEquals(note.note, moved.note)
        assertEquals(note.id, moved.id)
        assertEquals(1 to 8, edit.position(1, 9))
        assertEquals(2 to 9, edit.position(2, 9))
        val image = edit.annotation(note.copy(characterOffset = 15, endOffset = 15, imagePath = "inline.png", kind = AnnotationKind.BOOKMARK))
        assertEquals(14, image.characterOffset)
        assertEquals("inline.png", image.imagePath)
    }

    @Test fun overlappingNoteTracksReplacementAndRetainsItsContents() {
        val edit = EbookTextEdit(0, original, 3, 8, "新正文。")
        val note = edit.annotation(Annotation(bookId = "b", chapterIndex = 0, pageIndex = 0,
            characterOffset = 3, endOffset = 8, quote = "首段正文。", note = "批注"))
        assertEquals("新正文。", note.quote)
        assertEquals("批注", note.note)
        assertEquals(3, note.characterOffset)
        assertEquals(7, note.endOffset)
    }

    @Test fun insertionShiftsLaterInlineStylesWithoutExtendingFormattingToNewText() {
        val old = "abcdefghij"
        val chapter = Chapter(title = "章", sourceStyles = listOf(
            SourceTextStyle(0, 10, bold = true), SourceTextStyle(7, 10, italic = true)))
        val edit = EbookTextEdit(0, old, 5, 5, "XYZ")
        val styles = edit.chapter(chapter, "new.txt").sourceStyles
        assertEquals(listOf(SourceTextStyle(0, 5, bold = true), SourceTextStyle(8, 13, bold = true),
            SourceTextStyle(10, 13, italic = true)), styles)
    }

    @Test fun newlinesNormalizeAndInvalidOrEmptyChaptersAreRejected() {
        assertEquals("标题\n甲\n乙", EbookTextEdit(0, "旧内容", 0, 3, "标题\r\n甲\r乙").text)
        assertThrows(IllegalArgumentException::class.java) { EbookTextEdit(0, original, -1, 3, "字") }
        assertThrows(IllegalArgumentException::class.java) { EbookTextEdit(0, original, 0, original.length, " ") }
    }

    @Test fun rebuildingEditedCatalogKeepsImagesAndSpecialStylesAcrossNewChapterBoundaries() {
        val first = ParsedTextChapter("原第一章", "第一章\n修改正文\n第二章\n引用正文",
            inlineImages = listOf(InlineImage(6, "first.png"), InlineImage(15, "second.png")),
            sourceStyles = listOf(SourceTextStyle(13, 17, italic = true, paragraph = true)))
        val second = ParsedTextChapter("原第二章", "后续正文", inlineImages = listOf(InlineImage(2, "third.png")),
            sourceStyles = listOf(SourceTextStyle(0, 4, bold = true)))
        val parsed = splitEditedTextChapters(listOf(first, second), DEFAULT_CHAPTER_RULE)
        assertEquals(listOf("第一章", "第二章"), parsed.map { it.title })
        assertTrue(parsed.first().text.contains("修改正文"))
        assertEquals(listOf("first.png", "second.png", "third.png"), parsed.flatMap { it.inlineImages }.map { it.path })
        val quote = parsed[1].sourceStyles.first { it.italic == true }
        assertEquals("引用正文", parsed[1].text.substring(quote.start, quote.end))
        val bold = parsed[1].sourceStyles.first { it.bold == true }
        assertEquals("后续正文", parsed[1].text.substring(bold.start, bold.end))
        assertTrue(parsed.flatMap { it.inlineImages }.all { it.offset >= 0 })
    }

    @Test fun bulkEditsPreserveUntouchedInlineRunsParagraphStylesImagesAndNotes() {
        val old = "标题\nalpha CAT beta\n引用完整\n另段cat尾\n末段\uFFFC结束"
        fun endOfParagraph(start: Int) = old.indexOf('\n', start).let { if (it < 0) old.length else it }
        val body = old.indexOf("alpha")
        val quote = old.indexOf("引用完整")
        val second = old.indexOf("另段")
        val tail = old.indexOf("末段")
        val heading = SourceTextStyle(0, 2, bold = true, paragraph = true)
        val chapter = Chapter(title = "原章名", inlineImages = listOf(InlineImage(old.indexOf('\uFFFC'), "image.png")),
            sourceStyles = listOf(heading,
                SourceTextStyle(body, endOfParagraph(body), paragraph = true, italic = true),
                SourceTextStyle(quote, endOfParagraph(quote), paragraph = true, italic = true),
                SourceTextStyle(body, endOfParagraph(body), bold = true),
                SourceTextStyle(second, endOfParagraph(second), paragraph = true, bold = true),
                SourceTextStyle(tail, old.length, italic = true)))
        val edit = EbookTextEdit(1, old, literalTextReplacements(old, "cat", "狗狗"))
        val updated = edit.chapter(chapter, "edited.txt")
        assertEquals("标题\nalpha 狗狗 beta\n引用完整\n另段狗狗尾\n末段\uFFFC结束", edit.text)
        val keptQuote = edit.text.indexOf("引用完整")
        val keptTail = edit.text.indexOf("末段")
        assertEquals(listOf(heading,
            SourceTextStyle(keptQuote, keptQuote + 4, paragraph = true, italic = true),
            SourceTextStyle(body, body + "alpha ".length, bold = true),
            SourceTextStyle(edit.text.indexOf(" beta"), edit.text.indexOf(" beta") + " beta".length, bold = true),
            SourceTextStyle(keptTail, edit.text.length, italic = true)), updated.sourceStyles)
        assertEquals(listOf(InlineImage(edit.text.indexOf('\uFFFC'), "image.png")), updated.inlineImages)
        val note = edit.annotation(Annotation(bookId = "b", chapterIndex = 1, pageIndex = 8,
            characterOffset = body, endOffset = endOfParagraph(second), quote = "旧引用", note = "跨两处替换的笔记"))
        assertEquals(edit.text.substring(body, edit.text.indexOf('\n', edit.text.indexOf("另段"))), note.quote)
        assertEquals("跨两处替换的笔记", note.note)
        assertEquals(0, note.pageIndex)
        assertEquals(1 to edit.text.indexOf('\uFFFC'), edit.position(1, old.indexOf('\uFFFC')))
        assertEquals(2 to 123, edit.position(2, 123))
    }

    @Test fun bulkInsertionsSplitStylesWithoutApplyingOldFormattingToNewText() {
        val edit = EbookTextEdit(0, "abcdefghij", listOf(TextReplacement(2, 2, "++"), TextReplacement(7, 7, "!")))
        val chapter = Chapter(title = "章", sourceStyles = listOf(SourceTextStyle(0, 10, bold = true), SourceTextStyle(2, 7, italic = true)))
        assertEquals(listOf(SourceTextStyle(0, 2, bold = true), SourceTextStyle(4, 9, bold = true), SourceTextStyle(10, 13, bold = true),
            SourceTextStyle(4, 9, italic = true)), edit.chapter(chapter, "edited.txt").sourceStyles)
        assertThrows(IllegalArgumentException::class.java) { EbookTextEdit(0, "cat CAT", literalTextReplacements("cat CAT", "cat", "")) }
    }
}
