package com.eyue.reader.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class LibrarySaveCoordinatorTest {
    private val original = LibraryState(books = listOf(Book(id = "book", title = "书籍", format = BookFormat.TXT,
        mode = LibraryMode.NOVEL, chapters = listOf(Chapter(id = "chapter", title = "第一章")))))

    private class Fixture(initial: LibraryState) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var state: LibraryState? = initial
        val structures = mutableListOf<LibraryState>()
        val positions = mutableListOf<LibraryState>()
        val published = mutableListOf<LibraryState>()
        val errors = mutableListOf<Exception>()
        var beforeStructure: suspend () -> Unit = {}
        var beforePositions: suspend () -> Unit = {}
        val saved = CompletableDeferred<Unit>()
        val saves = LibrarySaveCoordinator(scope, { state },
            { snapshot -> beforeStructure(); structures += snapshot },
            { snapshot -> beforePositions(); positions += snapshot },
            { snapshot -> published += snapshot; saved.complete(Unit) }, { errors += it },
            dispatcher = Dispatchers.Unconfined, debounceMillis = 50)
    }

    @Test fun positionAndTimeChangesOnlyWriteThePositionStore() = runBlocking {
        val fixture = Fixture(original)
        try {
            fixture.saves.initialize(original)
            repeat(5) { index ->
                fixture.state = original.copy(books = listOf(original.books.single().copy(pageIndex = index,
                    characterOffset = index * 20, progress = index / 5f, lastReadAt = index.toLong())), readingSeconds = index.toLong())
                fixture.saves.persistNow()
            }
            assertEquals(0, fixture.structures.size)
            assertEquals(5, fixture.positions.size)
            assertEquals(fixture.state, fixture.positions.last())
            assertEquals(fixture.positions, fixture.published)
        } finally { fixture.scope.cancel() }
    }

    @Test fun cancellingTheDebounceCannotLoseAnUnsavedStructuralChange() = runBlocking {
        val fixture = Fixture(original)
        try {
            fixture.saves.initialize(original)
            val note = Annotation(bookId = "book", chapterIndex = 0, pageIndex = 0, note = "保留笔记")
            fixture.state = original.copy(annotations = listOf(note))
            fixture.saves.schedule()
            fixture.saves.cancelPending()
            fixture.state = fixture.state!!.copy(books = listOf(original.books.single().copy(characterOffset = 42)))
            fixture.saves.schedule()
            withTimeout(2_000) { fixture.saved.await() }
            assertEquals(listOf(fixture.state), fixture.structures)
            assertTrue(fixture.positions.isEmpty())
            assertEquals(listOf(note), fixture.structures.single().annotations)
        } finally { fixture.scope.cancel() }
    }

    @Test fun debouncedRequestsWriteOnlyTheLatestSnapshot() = runBlocking {
        val fixture = Fixture(original)
        try {
            fixture.saves.initialize(original)
            repeat(4) { index ->
                fixture.state = original.copy(books = listOf(original.books.single().copy(characterOffset = index * 10)))
                fixture.saves.schedule()
            }
            withTimeout(2_000) { fixture.saved.await() }
            assertTrue(fixture.structures.isEmpty())
            assertEquals(listOf(fixture.state), fixture.positions)
        } finally { fixture.scope.cancel() }
    }

    @Test fun concurrentSavesAreSerializedAndReadTheLatestStateAfterWaiting() = runBlocking {
        val fixture = Fixture(original)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            fixture.saves.initialize(original)
            fixture.state = original.copy(settings = original.settings.copy(fontSize = 25f))
            fixture.beforeStructure = { started.complete(Unit); release.await() }
            val first = fixture.scope.launch { fixture.saves.persistNow() }
            started.await()
            fixture.state = fixture.state!!.copy(books = listOf(original.books.single().copy(characterOffset = 120)))
            val second = fixture.scope.launch { fixture.saves.persistNow() }
            assertTrue(fixture.structures.isEmpty())
            assertTrue(fixture.positions.isEmpty())
            release.complete(Unit)
            withTimeout(2_000) { joinAll(first, second) }
            assertEquals(1, fixture.structures.size)
            assertEquals(0, fixture.structures.single().books.single().characterOffset)
            assertEquals(1, fixture.positions.size)
            assertEquals(120, fixture.positions.single().books.single().characterOffset)
            assertEquals(25f, fixture.positions.single().settings.fontSize)
        } finally { release.complete(Unit); fixture.scope.cancel() }
    }

    @Test fun aCancelledInFlightWriteStillCommitsItsBaselineBeforeTheNextSave() = runBlocking {
        val fixture = Fixture(original)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            fixture.saves.initialize(original)
            fixture.state = original.copy(settings = original.settings.copy(fontSize = 27f))
            fixture.beforeStructure = { started.complete(Unit); release.await() }
            val writing = fixture.scope.launch { fixture.saves.persistNow() }
            started.await()
            writing.cancel()
            release.complete(Unit)
            withTimeout(2_000) { writing.join() }
            fixture.saves.persistNow()
            assertEquals(1, fixture.structures.size)
            assertEquals(1, fixture.positions.size)
            assertEquals(2, fixture.published.size)
        } finally { release.complete(Unit); fixture.scope.cancel() }
    }

    @Test fun aFailedStructuralWriteRemainsDirtyAndCanBeRetried() = runBlocking {
        val fixture = Fixture(original)
        try {
            fixture.saves.initialize(original)
            fixture.state = original.copy(settings = original.settings.copy(fontSize = 29f))
            fixture.beforeStructure = { throw IOException("磁盘写入失败") }
            fixture.saves.flush().join()
            assertTrue(fixture.structures.isEmpty())
            assertTrue(fixture.published.isEmpty())
            assertEquals("磁盘写入失败", fixture.errors.single().message)
            fixture.beforeStructure = {}
            fixture.saves.flush().join()
            assertEquals(listOf(fixture.state), fixture.structures)
            assertTrue(fixture.positions.isEmpty())
        } finally { fixture.scope.cancel() }
    }

    @Test fun flushCancelsTheDebounceAndSurvivesItsOwningScopeBeingCleared() = runBlocking {
        val fixture = Fixture(original)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            fixture.saves.initialize(original)
            fixture.state = original.copy(readingSeconds = 180)
            fixture.beforePositions = { started.complete(Unit); release.await() }
            fixture.saves.schedule()
            val flushing = fixture.saves.flush()
            started.await()
            fixture.scope.cancel()
            assertTrue(fixture.positions.isEmpty())
            release.complete(Unit)
            withTimeout(2_000) { flushing.join() }
            assertEquals(listOf(fixture.state), fixture.positions)
            assertEquals(listOf(fixture.state), fixture.published)
        } finally { release.complete(Unit); fixture.scope.cancel() }
    }

    @Test fun aMissingLoadedStateDoesNotWriteAnEmptyCatalog() = runBlocking {
        val fixture = Fixture(original)
        try {
            fixture.state = null
            fixture.saves.flush().join()
            assertTrue(fixture.structures.isEmpty())
            assertTrue(fixture.positions.isEmpty())
            assertTrue(fixture.errors.isEmpty())
            assertTrue(fixture.published.isEmpty())
        } finally { fixture.scope.cancel() }
    }

    @Test fun aFinalFlushStillWritesWhenTheOwningScopeWasAlreadyCancelled() = runBlocking {
        val fixture = Fixture(original)
        fixture.saves.initialize(original)
        fixture.state = original.copy(readingSeconds = 240)
        fixture.scope.cancel()
        withTimeout(2_000) { fixture.saves.flush().join() }
        assertEquals(listOf(fixture.state), fixture.positions)
        assertEquals(listOf(fixture.state), fixture.published)
        assertTrue(fixture.errors.isEmpty())
    }
}
