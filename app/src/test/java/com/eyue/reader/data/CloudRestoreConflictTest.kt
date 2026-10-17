package com.eyue.reader.data

import com.eyue.reader.data.cloud.CloudBookInfo
import org.junit.Assert.*
import org.junit.Test

class CloudRestoreConflictTest {
    private val local = Book(id = "local", title = "同一本书", format = BookFormat.TXT, mode = LibraryMode.NOVEL,
        chapters = listOf(Chapter(title = "第一章")), progress = .5f)
    private val remote = local.copy(id = "remote", progress = .2f)
    private val localNote = Annotation(bookId = local.id, chapterIndex = 0, pageIndex = 0, note = "本地笔记")
    private val remoteNote = localNote.copy(id = "remote-note", bookId = remote.id, note = "云端笔记")
    private fun info(book: Book = remote) = CloudBookInfo(book, listOf(remoteNote), ReaderSettings(), null, 100,
        "revision", 10, "hash", emptyList())
    private fun state() = LibraryState(books = listOf(local), annotations = listOf(localNote))

    @Test fun sameIdAndOtherModesDoNotCountAsDuplicates() {
        assertTrue(cloudRestoreConflicts(state(), listOf(info(local))).isEmpty())
        assertTrue(cloudRestoreConflicts(state(), listOf(info(remote.copy(mode = LibraryMode.COMIC)))).isEmpty())
        assertEquals(listOf(CloudRestoreConflict(local, remote)), cloudRestoreConflicts(state(), listOf(info())))
    }
    @Test fun provenSameKeepsLocalWhileProvenDifferentRestoresBothWithoutPrompt() {
        val same = mapOf((local.id to remote.id) to CloudBookMatch.SAME)
        assertTrue(cloudRestoreConflicts(state(), listOf(info()), same).isEmpty())
        assertEquals(state(), resolveCloudRestoreInformation(state(), listOf(info()), emptyMap(), same))
        val different = mapOf((local.id to remote.id) to CloudBookMatch.DIFFERENT)
        assertTrue(cloudRestoreConflicts(state(), listOf(info()), different).isEmpty())
        val both = resolveCloudRestoreInformation(state(), listOf(info()), emptyMap(), different)
        assertEquals(setOf(local.id, remote.id), both.books.map { it.id }.toSet())
        assertEquals(local, both.books.first { it.id == local.id })
    }

    @Test fun provenSameRemovesAnExistingCloudPlaceholderAndKeepsLocalRecords() {
        val placeholder = remote.copy(cloudOnly = true, cloudEnabled = true)
        val existing = state().copy(books = listOf(local, placeholder), annotations = listOf(localNote, remoteNote))
        val same = mapOf((local.id to remote.id) to CloudBookMatch.SAME)
        val restored = resolveCloudRestoreInformation(existing, listOf(info()), emptyMap(), same)
        assertEquals(state(), restored)
        assertEquals(local.progress, restored.books.single().progress)
    }

    @Test fun matchingLaterCandidateOrExistingIdDoesNotPromptAboutAnotherNamesake() {
        val other = local.copy(id = "other")
        val expanded = state().copy(books = listOf(other, local))
        assertTrue(cloudRestoreConflicts(expanded, listOf(info()), mapOf((local.id to remote.id) to CloudBookMatch.SAME)).isEmpty())
        assertTrue(cloudRestoreConflicts(expanded.copy(books = expanded.books + remote), listOf(info())).isEmpty())
        assertEquals(other.id, cloudRestoreConflicts(expanded, listOf(info())).single().local.id)
    }

    @Test fun keepingBothPreservesLocalNotesAndRestoresARemotePlaceholder() {
        val result = resolveCloudRestoreInformation(state(), listOf(info()), mapOf(remote.id to CloudRestoreChoice.BOTH))
        assertEquals(local, result.books.first { it.id == local.id })
        assertTrue(result.books.first { it.id == remote.id }.cloudOnly)
        assertEquals(setOf(localNote.id, remoteNote.id), result.annotations.map { it.id }.toSet())
    }
    @Test fun keepingLocalNeverReplacesItsProgressOrNotesWithRemoteRecords() {
        val result = resolveCloudRestoreInformation(state(), listOf(info()), mapOf(remote.id to CloudRestoreChoice.LOCAL))
        assertEquals(state(), result)
    }
    @Test fun keepingCloudRemovesOnlyTheDiscardedLocalBookAndItsAnnotations() {
        val other = local.copy(id = "other", title = "另一本书")
        val result = resolveCloudRestoreInformation(state().copy(books = listOf(local, other)), listOf(info()), mapOf(remote.id to CloudRestoreChoice.CLOUD))
        assertEquals(setOf(remote.id, other.id), result.books.map { it.id }.toSet())
        assertEquals(listOf(remoteNote), result.annotations)
        assertTrue(result.books.first { it.id == remote.id }.cloudOnly)
    }

    @Test fun unknownMatchShowsEverySameTitleCandidateRatherThanOnlyTheFirst() {
        val second = local.copy(id = "second")
        val third = local.copy(id = "third")
        val expanded = state().copy(books = listOf(local, second, third))
        val matches = mapOf((local.id to remote.id) to CloudBookMatch.DIFFERENT, (second.id to remote.id) to CloudBookMatch.UNKNOWN)
        val conflict = cloudRestoreConflicts(expanded, listOf(info()), matches).single()
        assertEquals(second.id, conflict.local.id)
        assertEquals(listOf(local, second, third), conflict.locals)
        assertEquals(listOf(local), cloudRestoreConflicts(state(), listOf(info()), mapOf((local.id to remote.id) to CloudBookMatch.UNKNOWN)).single().locals)
    }

    @Test fun selectingALocalSubsetAndTheRemoteRetainsOnlyThoseBooksAndTheirOwnNotes() {
        val second = local.copy(id = "second")
        val third = local.copy(id = "third")
        val secondNote = localNote.copy(id = "second-note", bookId = second.id)
        val expanded = state().copy(books = listOf(local, second, third), annotations = listOf(localNote, secondNote))
        val restored = resolveCloudRestoreSelectionsInformation(expanded, listOf(info()),
            mapOf(remote.id to CloudRestoreSelection(setOf(second.id), true)))
        assertEquals(setOf(second.id, remote.id), restored.books.map { it.id }.toSet())
        assertEquals(setOf(secondNote.id, remoteNote.id), restored.annotations.map { it.id }.toSet())
        assertEquals(second, restored.books.first { it.id == second.id })
    }

    @Test fun retainingOnlyALaterLocalCandidateDiscardsOtherCandidatesAndTheRemotePlaceholder() {
        val second = local.copy(id = "second")
        val expanded = state().copy(books = listOf(local, second, remote.copy(cloudOnly = true)), annotations = listOf(localNote, remoteNote))
        val restored = resolveCloudRestoreSelectionsInformation(expanded, listOf(info()),
            mapOf(remote.id to CloudRestoreSelection(setOf(second.id), false)))
        assertEquals(listOf(second), restored.books)
        assertTrue(restored.annotations.isEmpty())
    }

    @Test fun emptyOrForeignSelectionsAreRejectedBeforeRemovingAnything() {
        assertThrows(IllegalArgumentException::class.java) {
            resolveCloudRestoreSelectionsInformation(state(), listOf(info()), mapOf(remote.id to CloudRestoreSelection(emptySet(), false)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolveCloudRestoreSelectionsInformation(state(), listOf(info()), mapOf(remote.id to CloudRestoreSelection(setOf("foreign"), true)))
        }
    }

    @Test fun aLocalBookKeptInAnotherGroupCannotBeDeletedByACloudOnlySelection() {
        val anotherRemote = remote.copy(id = "another-remote")
        val incoming = listOf(info(), info(anotherRemote))
        val selections = mapOf(remote.id to CloudRestoreSelection(setOf(local.id), false),
            anotherRemote.id to CloudRestoreSelection(emptySet(), true))
        val normalized = cloudRestoreSelectionsForState(state(), incoming, selections, emptyMap())
        assertEquals(setOf(local.id), normalized.getValue(anotherRemote.id).keepLocalIds)
        val restored = resolveCloudRestoreSelectionsInformation(state(), incoming, selections)
        assertEquals(setOf(local.id, anotherRemote.id), restored.books.map { it.id }.toSet())
        assertEquals(local, restored.books.first { it.id == local.id })
    }

    @Test fun automaticSameContentMatchIsProtectedFromAnOverlappingUnknownGroup() {
        val sameRemote = remote.copy(id = "known-same")
        val incoming = listOf(info(), info(sameRemote))
        val matches = mapOf((local.id to sameRemote.id) to CloudBookMatch.SAME)
        assertEquals(listOf(remote.id), cloudRestoreConflicts(state(), incoming, matches).map { it.remote.id })
        val restored = resolveCloudRestoreSelectionsInformation(state(), incoming,
            mapOf(remote.id to CloudRestoreSelection(emptySet(), true)), matches)
        assertEquals(setOf(local.id, remote.id), restored.books.map { it.id }.toSet())
        assertEquals(local, restored.books.first { it.id == local.id })
    }
}
