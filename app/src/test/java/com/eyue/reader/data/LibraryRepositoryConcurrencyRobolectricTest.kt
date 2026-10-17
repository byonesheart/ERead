package com.eyue.reader.data

import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LibraryRepositoryConcurrencyRobolectricTest {
    @Test fun independentRepositoriesShareCompleteSnapshotsDuringConcurrentReadsAndWrites() {
        val base = RuntimeEnvironment.getApplication()
        val root = File(base.cacheDir, "repository-concurrency-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        }
        val repositories = List(6) { LibraryRepository(context) }
        val snapshots = listOf(snapshot(0, 5, 96), snapshot(1, 15, 256), snapshot(2, 8, 1024))
        val ready = CountDownLatch(6)
        val start = CountDownLatch(1)
        val reads = AtomicInteger()
        val writes = AtomicInteger()
        val executor = Executors.newFixedThreadPool(6)
        try {
            repositories.first().save(snapshots.first())
            val tasks = repositories.mapIndexed { index, repository -> executor.submit {
                ready.countDown()
                check(start.await(5, TimeUnit.SECONDS)) { "Concurrent repository start timed out" }
                if (index < 2) repeat(40) { iteration ->
                    repository.save(snapshots[1 + (index + iteration) % 2])
                    writes.incrementAndGet()
                    Thread.yield()
                } else repeat(80) {
                    val loaded = repository.load()
                    assertTrue("A reader must see an entire initial or committed snapshot, never an empty or mixed book list",
                        snapshots.contains(loaded))
                    reads.incrementAndGet()
                    Thread.yield()
                }
            } }
            assertTrue("All independent repository instances should reach the concurrency barrier", ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            tasks.forEach { it.get(30, TimeUnit.SECONDS) }
            assertEquals(80, writes.get())
            assertEquals(320, reads.get())

            val final = snapshot(99, 11, 512)
            LibraryRepository(context).save(final)
            repositories.forEach { assertEquals("A final committed snapshot must be visible through every repository instance", final, it.load()) }
            assertFalse("No AtomicFile backup should remain after successful saves", File(context.filesDir, "library.json.bak").exists())
        } finally {
            start.countDown()
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS)) { "Concurrent repository tasks did not terminate" }
            check(root.canonicalFile.parentFile == base.cacheDir.canonicalFile)
            root.deleteRecursively()
        }
    }

    private fun snapshot(marker: Int, count: Int, textLength: Int): LibraryState {
        val books = List(count) { index -> Book(id = "snapshot-$marker-book-$index", title = "$marker:$index:" + "书".repeat(textLength),
            author = "作者 $marker", format = BookFormat.TXT, mode = LibraryMode.NOVEL, addedAt = marker.toLong(),
            contentVersion = marker + 1, chapterIndex = 1, characterOffset = marker, progress = .5f,
            chapters = listOf(Chapter(id = "$marker:$index:0", title = "第一章 $marker", textPath = "/snapshot/$marker/$index/0.txt"),
                Chapter(id = "$marker:$index:1", title = "第二章 $marker", textPath = "/snapshot/$marker/$index/1.txt")),
            groupId = "group-$marker") }
        val annotations = books.mapIndexed { index, book -> Annotation(id = "note-$marker-$index", bookId = book.id,
            chapterIndex = 1, pageIndex = 0, characterOffset = marker, endOffset = marker + textLength,
            quote = "原".repeat(textLength), note = "$marker:$index:" + "记录".repeat(textLength), createdAt = marker.toLong()) }
        return LibraryState(books = books, annotations = annotations, readingSeconds = marker.toLong(),
            groups = listOf(ShelfGroup(name = "分组 $marker", mode = LibraryMode.NOVEL, id = "group-$marker")))
    }
}
