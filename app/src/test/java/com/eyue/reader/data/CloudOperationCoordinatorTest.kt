package com.eyue.reader.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class CloudOperationCoordinatorTest {
    @Test fun exclusiveOwnerValidationDoesNotReleaseAnotherOwnersTransferSlots() = runBlocking<Unit> {
        val coordinator = CloudOperationCoordinator()
        val owner = Any()
        coordinator.lock(owner)
        assertTrue(coordinator.isLocked)
        try { coordinator.unlock(Any()); fail("Wrong owner must be rejected") }
        catch (_: IllegalStateException) { }
        assertTrue(coordinator.isLocked)
        coordinator.unlock(owner)
        assertFalse(coordinator.isLocked)
        withTimeout(1_000) { coordinator.withLock { } }
    }

    @Test fun sameBookSerializesButAnotherBookContinuesAndNestedServiceCallsDoNotDeadlock() = runBlocking<Unit> {
        val coordinator = CloudOperationCoordinator()
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        var secondStarted = false
        val first = launch { coordinator.withBook("a") { started.complete(Unit); release.await() } }
        started.await()
        val second = async { coordinator.withBook("a") { secondStarted = true } }
        yield()
        assertFalse(secondStarted)
        withTimeout(1_000) { coordinator.withBook("b") { coordinator.withBook("b") { assertFalse(secondStarted) } } }
        release.complete(Unit)
        withTimeout(1_000) { first.join(); second.await() }
        assertTrue(secondStarted)
    }

    @Test fun cancelledExclusiveWaitReturnsItsPartialPermitsAndGlobalMutationWaitsForActiveBook() = runBlocking<Unit> {
        val coordinator = CloudOperationCoordinator()
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val book = launch { coordinator.withBook("a") { started.complete(Unit); release.await() } }
        started.await()
        var exclusiveStarted = false
        val cancelled = launch { coordinator.withLock { exclusiveStarted = true } }
        yield()
        assertFalse(exclusiveStarted)
        cancelled.cancelAndJoin()
        withTimeout(1_000) { coordinator.withBook("b") { coordinator.withBook("b") { } } }
        val mutation = launch { coordinator.withLock { exclusiveStarted = true; coordinator.withBook("a") { } } }
        yield()
        assertFalse(exclusiveStarted)
        release.complete(Unit)
        withTimeout(1_000) { book.join(); mutation.join() }
        assertTrue(exclusiveStarted)
        withTimeout(1_000) { coordinator.withBook("a") { } }
    }
}
