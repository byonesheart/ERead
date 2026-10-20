package com.eyue.reader.data

import android.content.ContextWrapper
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ReadingPositionsRobolectricTest {
    @Test fun positionsAndReadingTimeSurviveRestartWithoutRewritingChapterStylesOrNotes() = withRepository { repository, files ->
        val book = book().copy(chapters = listOf(Chapter(title = "长正文", sourceStyles = List(1000) {
            SourceTextStyle(start = it * 3, end = it * 3 + 2, bold = true)
        })))
        val original = LibraryState(books = listOf(book), annotations = listOf(
            Annotation(bookId = book.id, chapterIndex = 0, pageIndex = 0, note = "保留的笔记")))
        repository.save(original)
        val metadata = File(files, "library.json").readBytes()
        val moved = original.copy(readingSeconds = 75, books = listOf(book.copy(chapterIndex = 0, pageIndex = 8,
            characterOffset = 37, progress = .7f, lastReadAt = 1234, positionImagePath = "image/\"书页\".png")))
        repeat(30) { repository.savePositions(moved) }
        assertArrayEquals(metadata, File(files, "library.json").readBytes())
        assertTrue(File(files, "reading_positions.json").length() < metadata.size / 10)
        assertEquals(1, JSONObject(File(files, "reading_positions.json").readText()).getJSONArray("books").length())
        assertEquals(moved, LibraryRepository(context(files)).load())
        assertEquals(original.withoutReadingPositions(), moved.withoutReadingPositions())
    }

    @Test fun restoreAndDeletionInvalidateOldPositionsEvenWhenTheBookIdAndContentVersionAreReused() = withRepository { repository, files ->
        val original = LibraryState(books = listOf(book()))
        repository.save(original)
        repository.savePositions(original.copy(readingSeconds = 44, books = listOf(original.books.single().copy(characterOffset = 99))))
        val stalePositions = File(files, "reading_positions.json").readBytes()
        val staleWriter = LibraryRepository(context(files)).also { it.load() }

        val restored = original.copy(books = listOf(original.books.single().copy(characterOffset = 7, progress = .1f)), readingSeconds = 2)
        repository.save(restored)
        // Models a crash between the catalog commit and removal of its old sidecar.
        File(files, "reading_positions.json").writeBytes(stalePositions)
        assertEquals(restored, LibraryRepository(context(files)).load())

        repository.save(LibraryState())
        staleWriter.savePositions(original)
        assertTrue(LibraryRepository(context(files)).load().books.isEmpty())
        repository.save(restored)
        assertEquals(restored, LibraryRepository(context(files)).load())
        assertFalse(File(files, "reading_positions.json").exists())
    }

    @Test fun structuralCommitKeepsItsCurrentPositionAndCompactsTheSidecar() = withRepository { repository, files ->
        val original = LibraryState(books = listOf(book()))
        repository.save(original)
        repository.savePositions(original.copy(books = listOf(original.books.single().copy(characterOffset = 80))))
        val edited = original.copy(books = listOf(original.books.single().copy(contentVersion = 2,
            chapters = listOf(Chapter(title = "重新分章")), characterOffset = 12, progress = .3f)))
        repository.save(edited)
        assertEquals(edited, LibraryRepository(context(files)).load())
        assertFalse(File(files, "reading_positions.json").exists())
        repository.savePositions(edited.copy(books = listOf(edited.books.single().copy(characterOffset = 13))))
        assertEquals(13, LibraryRepository(context(files)).load().books.single().characterOffset)
    }

    private fun book() = Book(id = "position-book", title = "位置", format = BookFormat.TXT,
        mode = LibraryMode.NOVEL, chapters = listOf(Chapter(title = "第一章")))

    private fun context(files: File) = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir() = files
    }

    private fun withRepository(block: (LibraryRepository, File) -> Unit) {
        val root = File(RuntimeEnvironment.getApplication().cacheDir, "reading-positions-${UUID.randomUUID()}").apply { mkdirs() }
        try { block(LibraryRepository(context(root)), root) }
        finally { root.deleteRecursively() }
    }
}
