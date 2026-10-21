package com.eyue.reader.ui

import org.junit.Assert.*
import org.junit.Test

class ReaderLayoutSessionTest {
    @Test fun initialStateCannotPublishAnUnpreparedViewport() {
        val session = ReaderLayoutSession()
        assertEquals(ReaderLayoutPhase.UNPREPARED, session.phase)
        assertEquals(0, session.generation)
        assertEquals(0, session.restoreRequest)
        assertNull(session.key)
        assertFalse(session.preparing)
        assertTrue(session.restoring)
        assertEquals(-1, session.preparedGeneration)
        assertEquals(-1, session.restoredGeneration)
        session.prepared(0)
        session.restored(0, 0)
        assertEquals(ReaderLayoutPhase.UNPREPARED, session.phase)
    }

    @Test fun currentPreparationAndRestorationAreBothRequiredBeforeReady() {
        val session = ReaderLayoutSession()
        session.begin(listOf("book", "scroll"))
        val generation = session.generation
        val request = session.restoreRequest
        assertTrue(session.preparing)
        assertTrue(session.restoring)
        session.restored(generation, request)
        assertTrue(session.preparing)
        session.reportProgress(generation, .5f)
        assertEquals(.5f, session.progress)
        session.prepared(generation)
        assertEquals(ReaderLayoutPhase.RESTORING, session.phase)
        assertEquals(generation, session.preparedGeneration)
        assertEquals(-1, session.restoredGeneration)
        session.restored(generation, request)
        assertReady(session)
    }

    @Test fun newLayoutsRejectAllPriorLayoutCallbacksAndOwnTheirKey() {
        val session = ReaderLayoutSession()
        val key = mutableListOf<Any?>("first", "scroll")
        session.begin(key)
        key[0] = "mutated"
        assertEquals("first", session.key?.first())
        val oldGeneration = session.generation
        val oldRequest = session.restoreRequest
        session.begin(listOf("second", "paged"), prepared = true)
        assertEquals(oldGeneration + 1, session.generation)
        assertEquals(oldRequest + 1, session.restoreRequest)
        assertEquals(ReaderLayoutPhase.RESTORING, session.phase)
        session.prepared(oldGeneration)
        session.restored(oldGeneration, oldRequest)
        session.reportProgress(oldGeneration, .9f)
        session.failed(oldGeneration, "old failure")
        assertEquals(0f, session.progress)
        assertNull(session.error)
        assertEquals(-1, session.restoredGeneration)
        session.restored(session.generation, session.restoreRequest)
        assertReady(session)
    }

    @Test fun aNewRestoreRequestRejectsThePreviousViewportWithoutDiscardingPreparedPages() {
        val session = readySession()
        val generation = session.generation
        val oldRequest = session.restoreRequest
        session.requestRestore()
        assertEquals(generation, session.preparedGeneration)
        assertEquals(-1, session.restoredGeneration)
        assertEquals(ReaderLayoutPhase.RESTORING, session.phase)
        session.restored(generation, oldRequest)
        assertTrue(session.restoring)
        session.restored(generation, session.restoreRequest)
        assertReady(session)
    }

    @Test fun anUnloadedChapterClearsPreparedAndRestoredStateUntilItsOwnPreparationCompletes() {
        val session = readySession()
        session.requestRestore(needsPreparation = true)
        assertTrue(session.preparing)
        assertEquals(-1, session.preparedGeneration)
        assertEquals(-1, session.restoredGeneration)
        session.restored(session.generation, session.restoreRequest)
        assertTrue(session.preparing)
        session.prepared(session.generation)
        session.restored(session.generation, session.restoreRequest)
        assertReady(session)
    }

    @Test fun cachedPreparationKeepsReadyButUncachedPreparationCannotReuseTheOldRestore() {
        val session = readySession()
        session.prepare(ready = true)
        assertReady(session)
        session.prepare(ready = false)
        assertTrue(session.preparing)
        assertEquals(-1, session.preparedGeneration)
        assertEquals(-1, session.restoredGeneration)
        session.prepare(ready = true)
        assertEquals(ReaderLayoutPhase.RESTORING, session.phase)
        assertEquals(session.generation, session.preparedGeneration)
        session.restored(session.generation, session.restoreRequest)
        assertReady(session)
    }

    @Test fun failureCannotBeReleasedByLateCallbacksAndAnExplicitRetryCanRecover() {
        val session = readySession()
        session.requestRestore(needsPreparation = true)
        val generation = session.generation
        val request = session.restoreRequest
        session.prepared(generation)
        session.failed(generation, "broken source")
        assertEquals(ReaderLayoutPhase.FAILED, session.phase)
        assertTrue(session.restoring)
        assertFalse(session.preparing)
        assertEquals("broken source", session.error)
        assertEquals(-1, session.preparedGeneration)
        session.prepared(generation)
        session.restored(generation, request)
        assertEquals(ReaderLayoutPhase.FAILED, session.phase)
        session.requestRestore(needsPreparation = true)
        assertNull(session.error)
        assertEquals(generation + 1, session.generation)
        assertEquals(request + 1, session.restoreRequest)
        assertEquals(listOf("book", "scroll"), session.key)
        session.prepared(generation)
        session.restored(generation, request)
        assertTrue(session.preparing)
        session.prepared(session.generation)
        session.restored(session.generation, request)
        assertTrue(session.restoring)
        session.restored(session.generation, session.restoreRequest)
        assertReady(session)
    }

    @Test fun restoringAnAlreadyPreparedSourceAfterFailureDoesNotCreateAnotherLayoutGeneration() {
        val session = readySession()
        val generation = session.generation
        session.failed(generation, "restore failed")
        session.requestRestore(needsPreparation = false)
        assertEquals(generation, session.generation)
        assertNull(session.error)
        assertEquals(ReaderLayoutPhase.RESTORING, session.phase)
        session.prepare(ready = true)
        session.restored(generation, session.restoreRequest)
        assertReady(session)
    }

    @Test fun cancellationHasNoReadyTransitionAndProgressIsAlwaysFiniteAndBounded() {
        val session = ReaderLayoutSession()
        session.begin(listOf("new layout"))
        session.prepare(ready = false)
        for ((fraction, expected) in listOf(-.5f to 0f, 1.5f to 1f, Float.NaN to 0f, Float.POSITIVE_INFINITY to 0f)) {
            session.reportProgress(session.generation, fraction)
            assertEquals(expected, session.progress)
        }
        assertTrue(session.preparing)
        assertTrue(session.restoring)
        assertEquals(-1, session.restoredGeneration)
    }

    private fun readySession() = ReaderLayoutSession().apply {
        begin(listOf("book", "scroll"), prepared = true)
        restored(generation, restoreRequest)
    }

    private fun assertReady(session: ReaderLayoutSession) {
        assertEquals(ReaderLayoutPhase.READY, session.phase)
        assertFalse(session.preparing)
        assertFalse(session.restoring)
        assertNull(session.error)
        assertEquals(session.generation, session.preparedGeneration)
        assertEquals(session.generation, session.restoredGeneration)
    }
}
