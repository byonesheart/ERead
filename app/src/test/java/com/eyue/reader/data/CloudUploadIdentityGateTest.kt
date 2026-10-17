package com.eyue.reader.data

import com.eyue.reader.data.cloud.CloudBookInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class CloudUploadIdentityGateTest {
    private val account = "https://example.invalid/dav/\nreader"
    private fun book(id: String, name: String = "$id.mobi", title: String = id) = Book(
        id = id, title = title, format = BookFormat.MOBI, mode = LibraryMode.NOVEL,
        chapters = listOf(Chapter(title = "第一章")), sourceFileName = name, sourceFileBytes = 10_000L)
    private fun info(book: Book) = CloudBookInfo(book, emptyList(), ReaderSettings(), null, 100,
        "revision", 12_000, "hash", emptyList())

    @Test fun identicalSourcesRecheckAfterFirstPublicationWhileAnotherSourceContinues() = runBlocking<Unit> {
        val coordinator = CloudOperationCoordinator()
        val first = book("first", "原始书.mobi", "第一种元数据书名")
        val duplicate = book("duplicate", "原始书.mobi", "另一种元数据书名")
        val unrelated = book("unrelated")
        val catalog = linkedMapOf<String, CloudBookInfo>()
        val firstUploading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var duplicatePreflightStarted = false
        suspend fun publish(book: Book): String = coordinator.withUploadIdentity(cloudUploadIdentityKeys(account, book)) {
            if (book.id == duplicate.id) duplicatePreflightStarted = true
            val target = cloudUploadTargetId(book, catalog.values.toList(), emptyMap())
            coordinator.withBooks(listOf(book.id, target)) {
                if (book.id == first.id) { firstUploading.complete(Unit); release.await() }
                catalog[target] = info(book.copy(id = target))
                target
            }
        }
        val firstJob = async { publish(first) }
        withTimeout(1_000) { firstUploading.await() }
        val duplicateJob = async { publish(duplicate) }
        val unrelatedJob = async { publish(unrelated) }
        try {
            yield()
            assertFalse("The duplicate must not read an empty catalog before the first upload publishes", duplicatePreflightStarted)
            assertEquals(unrelated.id, withTimeout(1_000) { unrelatedJob.await() })
            release.complete(Unit)
            assertEquals(first.id, withTimeout(1_000) { firstJob.await() })
            assertEquals(first.id, withTimeout(1_000) { duplicateJob.await() })
            assertEquals(setOf(first.id, unrelated.id), catalog.keys)
        } finally {
            release.complete(Unit)
            firstJob.cancelAndJoin(); duplicateJob.cancelAndJoin(); unrelatedJob.cancelAndJoin()
        }
    }

    @Test fun queuedIdentityChecksDoNotConsumeAnyOfTheThreeTransferSlots() = runBlocking<Unit> {
        val coordinator = CloudOperationCoordinator()
        val held = book("held")
        val gateStarted = CompletableDeferred<Unit>()
        val releaseGate = CompletableDeferred<Unit>()
        val gate = launch { coordinator.withUploadIdentity(cloudUploadIdentityKeys(account, held)) {
            gateStarted.complete(Unit); releaseGate.await()
        } }
        withTimeout(1_000) { gateStarted.await() }
        val waiting = (1..3).map { index -> launch {
            coordinator.withUploadIdentity(cloudUploadIdentityKeys(account, held.copy(id = "queued-$index"))) {
                coordinator.withBook("queued-$index") { }
            }
        } }
        yield()
        val started = List(3) { CompletableDeferred<Unit>() }
        val releaseTransfers = CompletableDeferred<Unit>()
        val transfers = (0..2).map { index -> launch {
            val other = book("other-$index")
            coordinator.withUploadIdentity(cloudUploadIdentityKeys(account, other)) {
                coordinator.withBook(other.id) { started[index].complete(Unit); releaseTransfers.await() }
            }
        } }
        try {
            withTimeout(1_000) { started.forEach { it.await() } }
            assertTrue(started.all { it.isCompleted })
        } finally {
            releaseGate.complete(Unit); releaseTransfers.complete(Unit)
            (listOf(gate) + waiting + transfers).forEach { it.cancelAndJoin() }
        }
    }

    @Test fun cancellingTheUploadHoldingAnIdentityAllowsItsNextCopyToContinue() = runBlocking<Unit> {
        val coordinator = CloudOperationCoordinator()
        val keys = cloudUploadIdentityKeys(account, book("local"))
        val entered = CompletableDeferred<Unit>()
        var nextEntered = false
        val first = launch { coordinator.withUploadIdentity(keys) { entered.complete(Unit); awaitCancellation() } }
        withTimeout(1_000) { entered.await() }
        val next = launch { coordinator.withUploadIdentity(keys.reversed()) { nextEntered = true } }
        try {
            yield()
            assertFalse(nextEntered)
            first.cancelAndJoin()
            withTimeout(1_000) { next.join() }
            assertTrue(nextEntered)
        } finally { first.cancelAndJoin(); next.cancelAndJoin() }
    }

    @Test fun cancellingWhileAcquiringMultipleIdentityKeysReleasesAlreadyAcquiredKeys() = runBlocking<Unit> {
        val coordinator = CloudOperationCoordinator()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch { coordinator.withUploadIdentity(listOf("z")) { entered.complete(Unit); release.await() } }
        withTimeout(1_000) { entered.await() }
        val waiting = launch { coordinator.withUploadIdentity(listOf("z", "a", "a")) { fail("Cancelled waiter must not enter") } }
        try {
            yield()
            waiting.cancelAndJoin()
            withTimeout(1_000) { coordinator.withUploadIdentity(listOf("a")) { } }
        } finally { release.complete(Unit); holder.cancelAndJoin(); waiting.cancelAndJoin() }
    }

    @Test fun knownSourceKeysCrossTitlesAndModesButNeverAccountsOrFilenameCase() {
        val source = book("first", "原书.mobi", " Original Title ")
        val keys = cloudUploadIdentityKeys(account, source).toSet()
        val changedMetadata = source.copy(id = "other", title = "另一个标题", mode = LibraryMode.COMIC)
        assertEquals(1, keys.intersect(cloudUploadIdentityKeys(account, changedMetadata).toSet()).size)
        assertTrue(keys.intersect(cloudUploadIdentityKeys("another-account", source).toSet()).isEmpty())
        assertTrue(keys.intersect(cloudUploadIdentityKeys(account,
            changedMetadata.copy(sourceFileName = "原书.MOBI")).toSet()).isEmpty())
        val noSize = source.copy(sourceFileBytes = null)
        assertEquals(cloudUploadIdentityKeys(account, noSize),
            cloudUploadIdentityKeys(account, noSize.copy(id = "reimported", title = "original title")))
        assertEquals(1, cloudUploadIdentityKeys(account, noSize).size)
    }
}
