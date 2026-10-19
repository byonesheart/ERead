package com.eyue.reader.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ReaderContentsMetadataRobolectricTest {
    private val repository get() = LibraryRepository(RuntimeEnvironment.getApplication())

    @Test fun oldSettingsEnableHierarchyWithoutChangingOtherPreferences() {
        val settings = repository.readSettings(JSONObject().put("fontSize", 26).put("ttsSpeed", 1.3))
        assertTrue(settings.hierarchicalContents)
        assertEquals(26f, settings.fontSize)
        assertEquals(1.3f, settings.ttsSpeed)
    }

    @Test fun independentHierarchySettingsRoundTripBothChoices() {
        val novel = ReaderSettings(hierarchicalContents = false)
        val comic = ReaderSettings(hierarchicalContents = true)
        assertFalse(repository.readSettings(repository.writeSettings(novel)).hierarchicalContents)
        assertTrue(repository.readSettings(repository.writeSettings(comic)).hierarchicalContents)
        assertTrue(ReaderSettings().hierarchicalContents)
    }

    @Test fun sourceParentTitleRoundTripsAndOldChaptersRemainUnchanged() {
        val legacy = JSONObject().put("id", "old").put("title", "原章节").put("textPath", "body.txt")
        assertNull(repository.readChapter(legacy).tocParentTitle)
        val chapter = Chapter(id = "native", title = "源小节", textPath = "section.txt", characterCount = 100,
            tocParentTitle = "第一章 原生父章")
        assertEquals(chapter, repository.readChapter(repository.writeChapter(chapter)))
        assertEquals(chapter.id, repository.readChapter(repository.writeChapter(chapter)).id)
    }

    @Test fun sourceFootnotesRoundTripSeparatelyFromUserAnnotationsAndOldChaptersDefaultEmpty() {
        assertTrue(repository.readChapter(JSONObject().put("id", "old-note").put("title", "旧章节")).sourceFootnotes.isEmpty())
        val notes = listOf(SourceFootnote(5, 8, "[1]", "原文件注释正文"),
            SourceFootnote(12, 15, "[2]", "图片注释", "source-note.png"))
        val chapter = Chapter(id = "footnotes", title = "章节", sourceFootnotes = notes)
        assertEquals(chapter, repository.readChapter(repository.writeChapter(chapter)))
        val book = Book(title = "原生注释", format = BookFormat.EPUB, mode = LibraryMode.NOVEL, chapters = listOf(chapter))
        val userNote = Annotation(bookId = book.id, chapterIndex = 0, pageIndex = 0, note = "用户笔记")
        val state = LibraryState(books = listOf(book), annotations = listOf(userNote))
        val cleaned = cleanLibraryRecords(state, LibraryCleanupKind.ANNOTATIONS)
        assertTrue(cleaned.annotations.isEmpty())
        assertEquals(notes, cleaned.books.single().chapters.single().sourceFootnotes)
        val restored = repository.readBook(repository.writeBook(cleaned.books.single()))
        assertEquals(notes, restored.chapters.single().sourceFootnotes)
    }
}
