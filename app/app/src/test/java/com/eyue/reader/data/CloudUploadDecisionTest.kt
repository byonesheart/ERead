package com.eyue.reader.data

import com.eyue.reader.data.cloud.CloudBookInfo
import org.junit.Assert.*
import org.junit.Test

class CloudUploadDecisionTest {
    private val local = Book(id = "local", title = "同名小说", format = BookFormat.MOBI,
        mode = LibraryMode.NOVEL, chapters = listOf(Chapter(title = "第一章")),
        sourceFileName = "原书.mobi", sourceFileBytes = 10_000)
    private fun info(book: Book) = CloudBookInfo(book, emptyList(), ReaderSettings(), null, 100,
        "revision", 12_000, "hash", emptyList())

    @Test fun exactOriginalNameAndSizeReuseTheRemoteIdWithoutChangingLocalBookIdentity() {
        val remote = local.copy(id = "remote", title = "另一个元数据标题")
        assertEquals(remote.id, cloudUploadTargetId(local, listOf(info(remote)), emptyMap()))
        assertTrue(cloudUploadConflicts(LibraryState(books = listOf(local)), setOf(local.id), listOf(info(remote)), emptyMap()).isEmpty())
    }

    @Test fun anUncertainSameTitleAsksButAProvenDifferentFileCanRemainASeparateBook() {
        val remote = local.copy(id = "remote", sourceFileName = "other.mobi", sourceFileBytes = null)
        val state = LibraryState(books = listOf(local))
        val conflicts = cloudUploadConflicts(state, setOf(local.id), listOf(info(remote)), emptyMap())
        assertEquals(listOf(CloudRestoreConflict(local, remote)), conflicts)
        assertEquals(local.id, cloudUploadTargetId(local, listOf(info(remote)), emptyMap()))
        val different = mapOf((local.id to remote.id) to CloudBookMatch.DIFFERENT)
        assertTrue(cloudUploadConflicts(state, setOf(local.id), listOf(info(remote)), different).isEmpty())
    }

    @Test fun uploadedSnapshotRemapsBookAndNotesButPreservesTheLocalShelfAndCachePaths() {
        val remote = local.copy(id = "remote", cloudOnly = true)
        val note = Annotation(id = "note", bookId = local.id, chapterIndex = 0, pageIndex = 0, note = "保留笔记")
        val unrelated = note.copy(id = "other-note", bookId = "unrelated")
        val state = LibraryState(books = listOf(local, remote), annotations = listOf(note, unrelated))
        val (snapshot, book) = cloudUploadSnapshot(state, local, remote.id)
        assertEquals(remote.id, book.id)
        assertEquals(local.sourcePath, book.sourcePath)
        assertEquals(listOf(book), snapshot.books)
        assertEquals(note.copy(bookId = remote.id), snapshot.annotations.first())
        assertEquals(unrelated, snapshot.annotations.last())
        assertEquals(local, state.books.first())
        assertEquals(local.id, state.annotations.first().bookId)
    }

    @Test fun anExistingAssociationWinsAndADeletedAssociationDoesNotRecreateAnOldCloudId() {
        val remote = local.copy(id = "linked")
        val duplicate = local.copy(id = "other")
        assertEquals(remote.id, cloudUploadTargetId(local, listOf(info(duplicate), info(remote)), emptyMap(), remote.id))
        assertEquals(local.id, cloudUploadTargetId(local, emptyList(), emptyMap(), remote.id))
    }

    @Test fun aConfirmedLocalCopyDoesNotHideASecondUncertainBookInTheSameUploadBatch() {
        val remote = local.copy(id = "remote")
        val uncertain = local.copy(id = "uncertain", sourceFileName = "other.mobi", sourceFileBytes = null)
        val state = LibraryState(books = listOf(local, uncertain))
        val questions = cloudUploadConflicts(state, setOf(local.id, uncertain.id), listOf(info(remote)), emptyMap())
        assertEquals(1, questions.size)
        assertEquals(uncertain, questions.single().local)
        assertEquals(listOf(uncertain.id), questions.single().locals.map { it.id })
        val restored = applyCloudUploadSelections(state, listOf(info(remote)), questions,
            mapOf(remote.id to CloudRestoreSelection(emptySet(), true)))
        assertEquals(listOf(local.id), restored.books.map { it.id })
    }

    @Test fun retainingOnlyCloudRemovesOnlyConfirmedLocalCandidatesAndKeepsNewImports() {
        val remote = local.copy(id = "remote", cloudOnly = true)
        val importedLater = local.copy(id = "later-import", sourceFileName = "另一个文件.mobi", sourceFileBytes = null)
        val note = Annotation(bookId = local.id, chapterIndex = 0, pageIndex = 0, note = "旧笔记")
        val laterNote = note.copy(id = "later-note", bookId = importedLater.id)
        val before = LibraryState(books = listOf(local, importedLater), annotations = listOf(note, laterNote))
        val after = applyCloudUploadSelections(before, listOf(info(remote)), listOf(CloudRestoreConflict(local, remote)),
            mapOf(remote.id to CloudRestoreSelection(emptySet(), true)))
        assertEquals(setOf(importedLater.id, remote.id), after.books.map { it.id }.toSet())
        assertEquals(listOf(laterNote), after.annotations)
    }
}
