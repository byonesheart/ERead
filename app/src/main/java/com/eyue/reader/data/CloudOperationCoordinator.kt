package com.eyue.reader.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

data class CloudTransferProgress(val stage: String, val fraction: Float? = null, val cancellable: Boolean = false)

/** Transfers share three slots; only account changes and whole-library mutations need all slots. */
internal class CloudOperationCoordinator {
    private val slots = Semaphore(3)
    private val exclusive = Mutex()
    private val books = ConcurrentHashMap<String, Mutex>()
    private val catalogs = ConcurrentHashMap<String, Mutex>()
    private val uploadIdentities = ConcurrentHashMap<String, Mutex>()
    private class Held(val books: Set<String> = emptySet(), val all: Boolean = false) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Held>
    }

    suspend fun <T> withBook(bookId: String, action: suspend () -> T): T = withBooks(listOf(bookId), action)

    suspend fun <T> withBooks(bookIds: List<String>, action: suspend () -> T): T {
        val held = currentCoroutineContext()[Held]
        val missing = bookIds.distinct().filterNot { it in held?.books.orEmpty() }.sorted()
        if (held?.all == true || missing.isEmpty()) return action()
        suspend fun locked(index: Int): T = if (index == missing.size) {
            val context = Held(books = held?.books.orEmpty() + missing)
            if (held != null) withContext(context) { action() }
            else slots.withPermit { withContext(context) { action() } }
        } else books.getOrPut(missing[index]) { Mutex() }.withLock { locked(index + 1) }
        return locked(0)
    }

    suspend fun <T> withCatalog(account: String, action: suspend () -> T): T =
        catalogs.getOrPut(account) { Mutex() }.withLock { action() }

    /** Preflight and publication for the same source/title share a gate, without occupying a transfer slot. */
    suspend fun <T> withUploadIdentity(keys: List<String>, action: suspend () -> T): T {
        val ordered = keys.distinct().sorted()
        suspend fun locked(index: Int): T = if (index == ordered.size) action()
            else uploadIdentities.getOrPut(ordered[index]) { Mutex() }.withLock { locked(index + 1) }
        return locked(0)
    }

    val isLocked: Boolean get() = exclusive.isLocked

    suspend fun lock(owner: Any? = null) {
        exclusive.lock(owner)
        var acquired = 0
        try { repeat(3) { slots.acquire(); acquired++ } }
        catch (error: Throwable) { repeat(acquired) { slots.release() }; exclusive.unlock(owner); throw error }
    }

    fun unlock(owner: Any? = null) {
        check(exclusive.isLocked && (owner == null || exclusive.holdsLock(owner))) { "Cloud lock is not held by this owner" }
        repeat(3) { slots.release() }
        exclusive.unlock(owner)
    }

    suspend fun <T> withLock(action: suspend () -> T): T {
        if (currentCoroutineContext()[Held]?.all == true) return action()
        lock()
        try { return withContext(Held(all = true)) { action() } }
        finally { unlock() }
    }
}

internal val cloudOperationMutex = CloudOperationCoordinator()
