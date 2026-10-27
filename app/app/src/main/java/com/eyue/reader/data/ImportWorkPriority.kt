package com.eyue.reader.data

import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Only import checkpoints wait here; reader loads never acquire an import lock. */
internal object ImportWorkPriority {
    private val readers = AtomicInteger()
    private val busyUntil = AtomicLong()
    val reading: Boolean get() = readers.get() > 0

    fun readerSession(): Closeable {
        readers.incrementAndGet()
        return Closeable { if (readers.decrementAndGet() == 0) busyUntil.set(0) }
    }

    fun interaction() { if (reading) busyUntil.set(System.nanoTime() + 180_000_000L) }

    fun checkpoint(check: () -> Unit) {
        check()
        while (reading && System.nanoTime() < busyUntil.get()) {
            Thread.sleep(10)
            check()
        }
    }
}
