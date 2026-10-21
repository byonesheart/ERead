package com.eyue.reader.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Owns save ordering; reading activity never needs to serialize unchanged catalog metadata. */
internal class LibrarySaveCoordinator(
    private val scope: CoroutineScope,
    private val currentState: () -> LibraryState?,
    private val writeStructure: suspend (LibraryState) -> Unit,
    private val writePositions: suspend (LibraryState) -> Unit,
    private val onSaved: (LibraryState) -> Unit,
    private val onError: (Exception) -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val debounceMillis: Long = 350
) {
    private val mutex = Mutex()
    private var pending: Job? = null
    private var persistedStructure: LibraryState? = null

    fun initialize(loaded: LibraryState) {
        persistedStructure = loaded.withoutReadingPositions()
    }

    fun schedule() {
        cancelPending()
        pending = scope.launch {
            delay(debounceMillis)
            saveReportingFailure()
        }
    }

    fun cancelPending() {
        pending?.cancel()
        pending = null
    }

    suspend fun persistNow() {
        mutex.withLock {
            val snapshot = currentState() ?: return
            val structure = snapshot.withoutReadingPositions()
            // Commit the comparison baseline together with the write, including during scope cancellation.
            withContext(NonCancellable) {
                withContext(dispatcher) {
                    if (structure != persistedStructure) writeStructure(snapshot) else writePositions(snapshot)
                }
                persistedStructure = structure
                onSaved(snapshot)
            }
        }
    }

    fun flush(): Job {
        cancelPending()
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { saveReportingFailure() }
        }
    }

    private suspend fun saveReportingFailure() {
        try { persistNow() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { onError(error) }
    }
}
