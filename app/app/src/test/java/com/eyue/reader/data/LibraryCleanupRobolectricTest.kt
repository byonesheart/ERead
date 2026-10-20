package com.eyue.reader.data

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LibraryCleanupRobolectricTest {
    private fun book(id: String) = Book(id = id, title = id, format = BookFormat.TXT, mode = LibraryMode.NOVEL,
        chapters = listOf(Chapter(title = "一"), Chapter(title = "二")), chapterIndex = 1, pageIndex = 7,
        characterOffset = 29, progress = .6f, lastReadAt = 123,
        voiceRoles = listOf(BookVoiceRole("人物$id", "人物$id", id = "role-$id")))

    @Test fun subsetCleanupKeepsOtherBooksBookmarksAndReadingPositions() {
        val first = book("first")
        val other = book("other")
        val annotations = listOf(first, other).flatMap { book -> AnnotationKind.entries.map { kind ->
            Annotation(id = "${book.id}-$kind", bookId = book.id, chapterIndex = 1, pageIndex = 3,
                characterOffset = 12, kind = kind, quote = "原文", note = if (kind == AnnotationKind.NOTE) "说明" else "")
        } }
        val state = LibraryState(books = listOf(first, other), annotations = annotations, readingSeconds = 900)
        val history = cleanLibraryRecords(state, LibraryCleanupKind.HISTORY, setOf(first.id))
        assertEquals(first.copy(lastReadAt = 0), history.books.first())
        assertSame(other, history.books.last())
        assertSame(annotations, history.annotations)
        assertEquals(state.readingSeconds, history.readingSeconds)
        val notes = cleanLibraryRecords(state, LibraryCleanupKind.ANNOTATIONS, setOf(first.id))
        assertEquals(annotations.filter { it.bookId == other.id || it.kind == AnnotationKind.BOOKMARK }, notes.annotations)
        assertEquals(state.books, notes.books)
        assertEquals(state.commonVoiceRoles, notes.commonVoiceRoles)
        val roles = cleanLibraryRecords(state, LibraryCleanupKind.ROLES, setOf(first.id))
        assertEquals(first.copy(voiceRoles = emptyList()), roles.books.first())
        assertSame(other, roles.books.last())
        assertTrue(roles.commonVoiceRoles.isEmpty())
        assertSame(annotations, roles.annotations)
        assertSame(state, cleanLibraryRecords(state, LibraryCleanupKind.AI_AUDIO, setOf(first.id)))
    }

    private fun withStorage(block: (Context, File) -> Unit) {
        val base = RuntimeEnvironment.getApplication()
        val root = File(base.cacheDir, "cleanup-storage-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
        try { block(context, root) }
        finally { check(root.canonicalFile.parentFile == base.cacheDir.canonicalFile); root.deleteRecursively() }
    }

    @Test fun metadataUsageMeasuresUtf8RecordsAndExcludesBookmarks() = withStorage { context, _ -> runBlocking {
        val book = book("中文书😀")
        val note = Annotation(bookId = book.id, chapterIndex = 1, pageIndex = 0, quote = "引用", note = "中文😀", kind = AnnotationKind.NOTE)
        val bookmark = note.copy(id = "bookmark", kind = AnnotationKind.BOOKMARK, note = "")
        val common = defaultCommonVoiceRoles()
        val state = LibraryState(books = listOf(book), annotations = listOf(note, bookmark), commonVoiceRoles = common)
        val usage = measureLibraryCleanup(context, state)
        val history = JSONArray().put(JSONObject().put("bookId", book.id).put("lastReadAt", 123)).toString()
        assertEquals(history.toByteArray(Charsets.UTF_8).size.toLong(), usage.historyBytes)
        assertTrue(usage.historyBytes > history.length)
        val changedNote = note.copy(note = "ASCII")
        val plain = measureLibraryCleanup(context, state.copy(annotations = listOf(changedNote)))
        assertEquals("中文😀".toByteArray(Charsets.UTF_8).size - "ASCII".toByteArray(Charsets.UTF_8).size,
            (usage.annotationBytes - plain.annotationBytes).toInt())
        assertEquals(usage.annotationBytes, measureLibraryCleanup(context, state.copy(annotations = listOf(note))).annotationBytes)
        val changedRole = book.voiceRoles.single().copy(voice = "明亮😀")
        val changed = measureLibraryCleanup(context, state.copy(books = listOf(book.copy(voiceRoles = listOf(changedRole)))))
        assertEquals("明亮😀".toByteArray(Charsets.UTF_8).size.toLong(), changed.roleBytes - usage.roleBytes)
        assertTrue(usage.roleBytes > measureLibraryCleanup(context, state.copy(commonVoiceRoles = emptyList())).roleBytes)
    } }

    @Test fun audioAndSingleBookUsageCountOwnedFilesAndCachesButNeverLinkedOriginals() = withStorage { context, root -> runBlocking {
        val external = File(root, "external-original.txt").apply { writeBytes(ByteArray(8192) { 7 }) }
        val first = book("owned").copy(sourcePath = external.absolutePath, sourceLinked = true)
        val other = book("other")
        val cache = AiSpeechCache(context)
        fun audio(bookId: String, size: Int) {
            val temporary = File.createTempFile("audio", ".mp3", context.cacheDir).apply { writeBytes(ByteArray(size)) }
            assertNotNull(cache.commit(bookId, "test-key", "mp3", temporary, cache.generation(bookId)))
        }
        audio(first.id, 13); audio(other.id, 29); audio("not-on-shelf", 41)
        fun file(directory: File, name: String, size: Int) = File(directory, name).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(size)) }
        file(File(context.filesDir, "books/${first.id}"), "chapter.txt", 17)
        file(File(context.filesDir, "books/${first.id}"), "nested/cover.png", 23)
        file(File(context.filesDir, "books/${other.id}"), "other.txt", 100)
        val hash = MessageDigest.getInstance("SHA-256").digest(first.id.toByteArray()).joinToString("") { "%02x".format(it) }
        file(File(context.cacheDir, "reader-page-cache"), "$hash.bin", 7)
        file(File(context.cacheDir, "reader-page-cache"), "${hash}_layout.bin", 11)
        file(File(context.cacheDir, "reader-page-cache"), "${hash}-unrelated.bin", 19)
        file(File(context.cacheDir, "reader-chapter-page-cache"), "${hash}_0.bin", 5)
        file(File(context.cacheDir, "reader-chapter-lengths"), "$hash.json", 3)
        file(File(context.cacheDir, "reader-book-page-index"), "${hash}_layout.parts/0.bin", 8)
        file(File(context.cacheDir, "reader-book-page-index"), "${hash}_other.bin", 9)
        file(File(context.cacheDir, "reader-book-page-index"), "unrelated.bin", 77)
        val usage = measureLibraryCleanup(context, LibraryState(books = listOf(first, other)))
        assertEquals(42L, usage.aiAudioBytes)
        assertEquals(96L, localBookStorageBytes(context, first))
        assertEquals(8192L, external.length())
        assertTrue(external.readBytes().all { it == 7.toByte() })
    } }
}
