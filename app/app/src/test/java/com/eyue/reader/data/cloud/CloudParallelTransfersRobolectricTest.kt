package com.eyue.reader.data.cloud

import android.content.Context
import android.content.ContextWrapper
import com.eyue.reader.data.Book
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.Chapter
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.LibraryState
import com.eyue.reader.data.Annotation
import com.eyue.reader.data.cloudOperationMutex
import com.eyue.reader.data.resolveCloudRestoreInformation
import com.eyue.reader.data.CloudRestoreSelection
import com.eyue.reader.data.cloudRestoreConflicts
import com.eyue.reader.data.resolveCloudRestoreSelectionsInformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CloudParallelTransfersRobolectricTest {
    private fun fixture(action: suspend CoroutineScope.(CloudBackupService, Server, (String) -> Book) -> Unit): Unit = runBlocking {
        val root = File("build/cloud-parallel-${UUID.randomUUID()}").absoluteFile.apply { mkdirs() }
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
        val credentials = object : CloudCredentialStore {
            var value: CloudCredentials? = CloudCredentials("parallel@example.invalid", "test")
            override fun read() = value
            override fun write(credentials: CloudCredentials) { value = credentials }
            override fun clear() { value = null }
        }
        val server = Server()
        val service = CloudBackupService(context, credentials, { _, check -> server.transport(check) })
        try {
            coroutineScope {
                action(this, service, server) { id ->
                    val file = File(root, "$id.txt").apply { writeText("第一章\n$id 的正文。") }
                    val path = file.absolutePath.let { if (it.matches(Regex("^[A-Za-z]:[\\\\/].*"))) it.substring(2) else it }
                    Book(id = id, title = id, format = BookFormat.TXT, mode = LibraryMode.NOVEL,
                        sourcePath = path, chapters = listOf(Chapter(title = "第一章", textPath = path)))
                }
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun threeBooksTransferTogetherAndAllCatalogEntriesSurvive() = fixture { service, server, book ->
        val books = (1..4).map { book("parallel-$it") }
        server.held.addAll(books.map { it.id })
        val state = LibraryState(books = books)
        val first = books.take(3).map { async(Dispatchers.IO) { service.upload(state, it) } }
        assertTrue("前三本应同时进入真实上传", server.threeStarted.await(5, TimeUnit.SECONDS))
        val fourth = async(Dispatchers.IO) { service.upload(state, books.last()) }
        assertFalse("第四本应等待传输名额", server.fourStarted.await(150, TimeUnit.MILLISECONDS))
        assertEquals(3, server.maximum.get())
        server.release.countDown()
        withTimeout(10_000) { (first + fourth).awaitAll() }
        assertEquals(books.map { it.id }.toSet(), server.catalog())
        assertEquals(4, service.list().size)
    }

    @Test fun metadataRestoreReadsAndMergesLatestLocalRecordsWhileAllThreeTransfersAreBusy() = fixture { service, server, book ->
        val original = book("metadata-existing").copy(progress = .2f)
        service.upload(LibraryState(books = listOf(original)), original)
        val uploading = (1..3).map { book("metadata-busy-$it") }
        server.held.addAll(uploading.map { it.id })
        val uploads = uploading.map { async(Dispatchers.IO) { service.upload(LibraryState(books = listOf(it)), it) } }
        assertTrue(server.threeStarted.await(5, TimeUnit.SECONDS))
        try {
            val listed = withTimeout(3_000) { service.list(requireNotNull(service.identity())) }
            val note = Annotation(bookId = original.id, chapterIndex = 0, pageIndex = 0, quote = "最新", note = "读取元信息期间写下的笔记")
            val latest = LibraryState(books = listOf(original.copy(progress = .9f, characterOffset = 11)) + uploading, annotations = listOf(note))
            val restored = withTimeout(3_000) {
                cloudOperationMutex.withCatalog(requireNotNull(service.identity())) {
                    val ids = service.catalogBookIds(requireNotNull(service.identity()))
                    resolveCloudRestoreInformation(latest, listed.filter { it.book.id in ids }, emptyMap())
                }
            }
            assertEquals(.9f, restored.books.first { it.id == original.id }.progress)
            assertEquals(11, restored.books.first { it.id == original.id }.characterOffset)
            assertEquals(listOf(note), restored.annotations)
            assertEquals(4, restored.books.size)
            assertEquals(3, server.active.get())
        } finally { server.release.countDown() }
        withTimeout(10_000) { uploads.awaitAll() }
    }

    @Test fun keepingAllUncertainSameTitleCopiesDoesNotWaitForUploadsOrModifyAnyCloudBackup() = fixture { service, server, book ->
        val remote = book("keep-all-remote").copy(title = "同名书籍")
        service.upload(LibraryState(books = listOf(remote)), remote)
        val uploading = (1..3).map { book("keep-all-busy-$it") }
        server.held.addAll(uploading.map { it.id })
        val uploads = uploading.map { async(Dispatchers.IO) { service.upload(LibraryState(books = listOf(it)), it) } }
        assertTrue(server.threeStarted.await(5, TimeUnit.SECONDS))
        try {
            val incoming = service.list(requireNotNull(service.identity()))
            val catalog = requireNotNull(server.files["catalog.json"]).copyOf()
            val manifest = requireNotNull(server.files["books/${remote.id}/manifest.json"]).copyOf()
            val first = remote.copy(id = "keep-all-local", progress = .8f, characterOffset = 12)
            val newlyImported = remote.copy(id = "keep-all-new-local", progress = .4f)
            val note = Annotation(bookId = first.id, chapterIndex = 0, pageIndex = 0, note = "最新笔记")
            val latest = LibraryState(books = listOf(first, newlyImported) + uploading, annotations = listOf(note))
            val restored = withTimeout(3_000) {
                cloudOperationMutex.withCatalog(requireNotNull(service.identity())) {
                    val ids = service.catalogBookIds(requireNotNull(service.identity()))
                    val available = incoming.filter { it.book.id in ids }
                    val keepAll = cloudRestoreConflicts(latest, available).associate { conflict ->
                        conflict.remote.id to CloudRestoreSelection(conflict.locals.map { it.id }.toSet(), true)
                    }
                    resolveCloudRestoreSelectionsInformation(latest, available, keepAll)
                }
            }
            assertEquals(6, restored.books.size)
            assertEquals(.8f, restored.books.first { it.id == first.id }.progress)
            assertEquals(listOf(note), restored.annotations)
            assertTrue(restored.books.any { it.id == newlyImported.id })
            assertTrue(restored.books.first { it.id == remote.id }.cloudOnly)
            assertArrayEquals(catalog, server.files["catalog.json"])
            assertArrayEquals(manifest, server.files["books/${remote.id}/manifest.json"])
            assertEquals(3, server.active.get())
        } finally { server.release.countDown() }
        withTimeout(10_000) { uploads.awaitAll() }
    }

    @Test fun blockedBookDoesNotPreventAnotherBooksDeletionOrUploadAndCancellationIsIndependent() = fixture { service, server, book ->
        val a = book("cancel-a"); val b = book("delete-b"); val c = book("upload-c")
        val state = LibraryState(books = listOf(a, b, c))
        service.upload(state, b)
        server.held += a.id
        val blocked = launch(Dispatchers.IO) { service.upload(state, a) }
        assertTrue(server.firstHeld.await(5, TimeUnit.SECONDS))
        withTimeout(5_000) {
            val deletion = async(Dispatchers.IO) { service.deleteBook(b.id) }
            val upload = async(Dispatchers.IO) { service.upload(state, c) }
            deletion.await(); upload.await()
        }
        assertFalse(server.release.count == 0L)
        blocked.cancelAndJoin()
        assertEquals(setOf(c.id), server.catalog())
        assertEquals(c.id, service.download(c.id).book.id)
        assertFalse(server.files.containsKey("books/${a.id}/manifest.json"))
    }

    private class Server {
        val files = ConcurrentHashMap<String, ByteArray>()
        val directories = ConcurrentHashMap.newKeySet<String>()
        val held = ConcurrentHashMap.newKeySet<String>()
        val release = CountDownLatch(1)
        val firstHeld = CountDownLatch(1)
        val threeStarted = CountDownLatch(3)
        val fourStarted = CountDownLatch(4)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        @Synchronized fun catalog(): Set<String> = files["catalog.json"]?.let {
            val ids = JSONObject(it.toString(Charsets.UTF_8)).getJSONArray("bookIds")
            (0 until ids.length()).map { index -> ids.getString(index) }.toSet()
        }.orEmpty()
        private fun etag(path: String) = files[path]?.contentHashCode()?.let { "\"$it\"" }
        @Synchronized private fun conditional(path: String, bytes: ByteArray, expected: String?): Boolean {
            if (etag(path) != expected) return false
            files[path] = bytes.copyOf(); return true
        }
        fun transport(check: () -> Unit): CloudTransport = object : CloudTransport {
            val cancelled = AtomicBoolean()
            override fun cancel() { cancelled.set(true) }
            override fun ensureCollection(path: String) { directories += path.trimEnd('/') }
            override fun collections(path: String) = directories.mapNotNull {
                it.removePrefix(path).takeIf { child -> it.startsWith(path) && child.isNotEmpty() && '/' !in child }
            }
            override fun metadata(path: String, maximum: Int) = files[path]?.copyOf()
            override fun putMetadata(path: String, bytes: ByteArray) { files[path] = bytes.copyOf() }
            override fun versionedMetadata(path: String): CloudVersionedMetadata? = synchronized(this@Server) {
                files[path]?.let { CloudVersionedMetadata(it.copyOf(), requireNotNull(etag(path))) }
            }
            override fun putVersionedMetadata(path: String, bytes: ByteArray, etag: String?) = conditional(path, bytes, etag)
            override fun deleteCollection(path: String) { files.keys.removeAll { it.startsWith(path) }; directories.removeAll { it.startsWith(path) } }
            override fun upload(path: String, file: File, offset: Long, bytes: Long, progress: (Long) -> Unit) {
                val count = active.incrementAndGet()
                maximum.accumulateAndGet(count, ::maxOf)
                try {
                    if (path.removePrefix("books/").substringBefore('/') in held) {
                        firstHeld.countDown(); threeStarted.countDown(); fourStarted.countDown()
                        while (!release.await(20, TimeUnit.MILLISECONDS)) {
                            check(); if (cancelled.get()) throw IOException("cancelled")
                        }
                    }
                    check()
                    val content = ByteArray(bytes.toInt())
                    RandomAccessFile(file, "r").use { it.seek(offset); it.readFully(content) }
                    files[path] = content; progress(bytes)
                } finally { active.decrementAndGet() }
            }
            override fun download(path: String, file: File, append: Boolean, maximum: Long, progress: (Long) -> Unit): Long {
                val bytes = files[path] ?: error("missing $path")
                java.io.FileOutputStream(file, append).use { it.write(bytes) }; progress(bytes.size.toLong()); return bytes.size.toLong()
            }
        }
    }
}
