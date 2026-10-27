package com.eyue.reader.data

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ImportWorkPriorityTest {
    @Test fun anInteractionYieldsImportButDoesNotHoldAReaderLockAndClosingReleasesIt() {
        val session = ImportWorkPriority.readerSession()
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        ImportWorkPriority.interaction()
        val thread = Thread {
            ImportWorkPriority.checkpoint { entered.countDown() }
            finished.countDown()
        }
        try {
            thread.start()
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFalse(finished.await(25, TimeUnit.MILLISECONDS))
            assertTrue(ImportWorkPriority.reading)
        } finally { session.close() }
        assertTrue(finished.await(1, TimeUnit.SECONDS))
        thread.join()
        assertFalse(ImportWorkPriority.reading)
    }

    @Test fun waitingImportStillHonorsCancellation() {
        val session = ImportWorkPriority.readerSession()
        val canceled = AtomicBoolean()
        try {
            ImportWorkPriority.interaction()
            assertThrows(kotlinx.coroutines.CancellationException::class.java) {
                ImportWorkPriority.checkpoint {
                    if (canceled.getAndSet(true)) throw kotlinx.coroutines.CancellationException()
                }
            }
        } finally { session.close() }
    }
}
