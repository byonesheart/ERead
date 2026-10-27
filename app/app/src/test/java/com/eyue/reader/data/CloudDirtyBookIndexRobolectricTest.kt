package com.eyue.reader.data

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
class CloudDirtyBookIndexRobolectricTest {
    @Test fun dayNightSwitchesAcceptPreviouslyUploadedLightAndDarkDigestsButStillDetectRealChanges() {
        val context = RuntimeEnvironment.getApplication()
        val repository = LibraryRepository(context)
        val history = CloudBackupHistory(context)
        for (dark in listOf(false, true)) {
            val account = "night-mode-${UUID.randomUUID()}"
            val book = book("night-$dark")
            var state = LibraryState(books = listOf(book), settings = ReaderSettings(nightMode = dark))
            history.record(account, book.id, legacyFingerprint(repository, state, book), 100)
            var prepares = 0
            val index = CloudDirtyBookIndex(repository) { source, snapshot, current ->
                prepares++; CloudBookFingerprint(source, snapshot, current)
            }
            assertTrue(index.dirtyBookIds(state, account, history).isEmpty())
            val canonical = cloudBackupFingerprint(repository, state, book)
            repeat(4) {
                state = state.copy(settings = state.settings.copy(nightMode = !state.settings.nightMode))
                assertTrue(index.dirtyBookIds(state, account, history).isEmpty())
                assertTrue(cloudDirtyBookIds(repository, state, account, history).isEmpty())
                assertTrue(CloudBookFingerprint(repository, state, book).matches(book, history.digest(account, book.id)))
                assertEquals(canonical, cloudBackupFingerprint(repository, state, book))
            }
            assertEquals("Night mode must not serialize the chapter index again", 1, prepares)
            val moved = book.copy(characterOffset = 25, progress = .1f)
            assertEquals(setOf(book.id), index.dirtyBookIds(state.copy(books = listOf(moved)), account, history))
            assertEquals(setOf(book.id), index.dirtyBookIds(state.copy(annotations = listOf(
                Annotation(bookId = book.id, chapterIndex = 0, pageIndex = 0, note = "新笔记"))), account, history))
            assertEquals(setOf(book.id), index.dirtyBookIds(state.copy(settings = state.settings.copy(fontSize = 26f)), account, history))
        }
    }

    @Test fun cachedPositionTokensProduceTheExistingRecordsV2FingerprintExactly() {
        val repository = LibraryRepository(RuntimeEnvironment.getApplication())
        for (image in listOf(null, "插图/\"书页\"\\1.png")) {
            val first = book("兼容").copy(positionImagePath = image)
            val state = LibraryState(books = listOf(first), annotations = listOf(
                Annotation(bookId = first.id, chapterIndex = 0, pageIndex = 0, note = "指纹不能改变")))
            val prepared = CloudBookFingerprint(repository, state, first)
            for ((page, progress) in listOf(0 to 0f, 3 to .5f, 25 to .33333334f, 90 to 1f)) {
                val moved = first.copy(chapterIndex = 2, pageIndex = page, characterOffset = page * 17,
                    progress = progress, lastReadAt = 9999, positionImagePath = image?.let { "$it/新图$progress" })
                assertEquals(legacyFingerprint(repository, state, moved), prepared.fingerprint(moved))
                assertEquals(legacyFingerprint(repository, state, moved), cloudBackupFingerprint(repository, state, moved))
            }
        }
    }

    @Test fun progressAndHistoryReuseSerializedBooksWhileNotesSettingsRolesAndGroupsInvalidateOnlyTheirOwners() {
        val context = RuntimeEnvironment.getApplication()
        val repository = LibraryRepository(context)
        val history = CloudBackupHistory(context)
        val account = "fingerprint-index-${UUID.randomUUID()}"
        val group = ShelfGroup("原分组", LibraryMode.NOVEL, "owned-group")
        val a = book("a").copy(groupId = group.id)
        val b = book("b")
        val c = book("c").copy(mode = LibraryMode.COMIC)
        var state = LibraryState(books = listOf(a, b, c), groups = listOf(group))
        state.books.forEach { history.record(account, it.id, cloudBackupFingerprint(repository, state, it), 100) }
        val prepared = mutableMapOf<String, Int>()
        val index = CloudDirtyBookIndex(repository) { source, snapshot, current ->
            prepared[current.id] = prepared.getOrDefault(current.id, 0) + 1
            CloudBookFingerprint(source, snapshot, current)
        }
        assertTrue(index.dirtyBookIds(state, account, history).isEmpty())
        assertEquals(mapOf("a" to 1, "b" to 1, "c" to 1), prepared)
        repeat(10) { step ->
            state = state.copy(books = state.books.map { if (it.id == a.id) it.copy(characterOffset = step + 1,
                progress = (step + 1) / 20f, lastReadAt = step.toLong()) else it }, readingSeconds = step.toLong())
            assertEquals(setOf(a.id), index.dirtyBookIds(state, account, history))
        }
        assertEquals("Reading must not serialize any book's chapters again", mapOf("a" to 1, "b" to 1, "c" to 1), prepared)
        val current = state.books.first()
        history.record(account, current.id, cloudBackupFingerprint(repository, state, current), 200)
        assertTrue(index.dirtyBookIds(state, account, history).isEmpty())
        assertEquals(mapOf("a" to 1, "b" to 1, "c" to 1), prepared)

        state = state.copy(annotations = listOf(Annotation(bookId = b.id, chapterIndex = 0, pageIndex = 0, note = "只影响乙")))
        assertEquals(setOf(b.id), index.dirtyBookIds(state, account, history))
        assertEquals(mapOf("a" to 1, "b" to 2, "c" to 1), prepared)
        state = state.copy(settings = state.settings.copy(fontSize = 29f))
        index.dirtyBookIds(state, account, history)
        assertEquals(mapOf("a" to 2, "b" to 3, "c" to 1), prepared)
        state = state.copy(groups = listOf(group.copy(name = "改名"), ShelfGroup("未使用", LibraryMode.NOVEL)))
        index.dirtyBookIds(state, account, history)
        assertEquals(mapOf("a" to 3, "b" to 3, "c" to 1), prepared)
        state = state.copy(books = state.books.map { if (it.id == b.id) it.copy(voiceRoles = listOf(
            BookVoiceRole(name = "角色", nameRegex = "角色", voice = "旁白"))) else it })
        index.dirtyBookIds(state, account, history)
        assertEquals(mapOf("a" to 3, "b" to 4, "c" to 1), prepared)
    }

    @Test fun explicitRefreshAndFreshWorkerFingerprintsStillDetectExternalFileChanges() {
        val context = RuntimeEnvironment.getApplication()
        val source = File(context.cacheDir, "cloud-index-source-${UUID.randomUUID()}.txt").apply { writeText("原文") }
        try {
            val repository = LibraryRepository(context)
            val history = CloudBackupHistory(context)
            val account = "external-index-${UUID.randomUUID()}"
            val book = book("external").copy(sourcePath = source.absolutePath)
            val state = LibraryState(books = listOf(book))
            val original = cloudBackupFingerprint(repository, state, book)
            history.record(account, book.id, original, 100)
            val index = CloudDirtyBookIndex(repository)
            assertTrue(index.dirtyBookIds(state, account, history).isEmpty())
            source.writeText("来自外部的新正文，长度已经变化。")
            assertNotEquals(original, cloudBackupFingerprint(repository, state, book))
            assertEquals(setOf(book.id), index.dirtyBookIds(state, account, history, refreshFiles = true))
        } finally { source.delete() }
    }

    private fun book(id: String) = Book(id = id, title = id, format = BookFormat.TXT, mode = LibraryMode.NOVEL,
        cloudEnabled = true, chapters = listOf(Chapter(title = "第一章", sourceStyles = List(100) {
            SourceTextStyle(start = it, end = it + 1, italic = true)
        })))

    private fun legacyFingerprint(repository: LibraryRepository, state: LibraryState, book: Book): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: String) { digest.update(value.toByteArray()); digest.update(0) }
        add(repository.writeBook(book.copy(cloudBackupAt = 0, cloudEnabled = true, cloudOnly = false, lastReadAt = 0)).toString())
        cloudBackupFingerprintTail(repository, state, book).forEach(::add)
        return "records-v2:" + digest.digest().joinToString("") { "%02x".format(it) }
    }
}
