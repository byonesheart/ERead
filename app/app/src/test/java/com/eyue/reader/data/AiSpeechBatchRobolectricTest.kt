package com.eyue.reader.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AiSpeechBatchRobolectricTest {
    private val context = RuntimeEnvironment.getApplication()
    private val folder = File(context.cacheDir, "batch-test-${UUID.randomUUID()}").apply { mkdirs() }
    private val config = AiSpeechConfig("https://example.com/v1", "test-key", "speech-test", "voice-test")
    private val books = mutableListOf<Book>()

    @After fun tearDown() {
        books.forEach { AiSpeechCache(context).clear(it.id) }
        folder.deleteRecursively()
    }

    @Test fun selectedChaptersUseLiveNarrationChunksInEachChapterOrderAndReportProgress() = runBlocking {
        val book = book("Not selected", "A".repeat(250) + "\n第二段", "Third")
        val requested = Collections.synchronizedList(mutableListOf<String>())
        val progress = mutableListOf<AiSpeechBatchProgress>()
        val batch = AiSpeechBatch(context) { _, text, target -> requested += text; target.writeBytes(byteArrayOf(1)) }
        val result = batch.generate(book, config, setOf(2, 1)) { progress += it }
        assertEquals(listOf("A".repeat(240), "A".repeat(10), "第二段"), requested.filter { it != "Third" })
        assertEquals(1, requested.count { it == "Third" })
        assertEquals(2, result.total)
        assertEquals(2, result.completed)
        assertEquals(result.chapterTotal, result.chapterCompleted)
        assertEquals(1f, result.fraction!!, 0f)
        assertFalse(result.running)
        assertNull(progress.first().fraction)
        assertEquals(listOf(1, 2), progress.map { it.completed }.filter { it > 0 }.distinct())
        assertTrue(progress.any { it.chapterProgress[1] == AiSpeechChapterProgress(1, 3) })
        assertEquals(book.chapters[result.chapterIndex].title, result.chapterTitle)
        requested.forEach { assertNotNull(AiSpeechCache(context).find(book.id, AiSpeechCache.key(config, it), "mp3")) }
        assertEquals(setOf(1, 2), result.selectedChapters)
        assertEquals(setOf(1, 2), result.completedChapters)
        assertTrue(progress.any { it.completedChapters.size == 1 && it.completed == 1 })
    }

    @Test fun blankChaptersAreExcludedFromTheSelectionAndCompletedChapterSets() = runBlocking {
        val book = book("  \n", "Chapter one", "Chapter two")
        val updates = mutableListOf<AiSpeechBatchProgress>()
        val final = AiSpeechBatch(context) { _, _, target -> target.writeBytes(byteArrayOf(1)) }
            .generate(book, config) { updates += it }
        assertEquals(setOf(1, 2), final.selectedChapters)
        assertEquals(setOf(1, 2), final.completedChapters)
        assertEquals(2, final.total)
        assertTrue(updates.none { 0 in it.selectedChapters || 0 in it.completedChapters })
        val cached = AiSpeechCache(context).chapterCacheStatus(book, config, emptyList())
        assertEquals(setOf(0), cached.emptyChapters)
        assertEquals(setOf(1, 2), cached.currentChapters)
        assertTrue(com.eyue.reader.ui.readerSpeechCacheStatus(book, cached).complete)
    }

    @Test fun partialChapterRegeneratesEveryChunkAndAnAlreadyCompleteChapterMakesNoRequests() = runBlocking {
        val book = book("Existing\nRepeated\nRepeated")
        val cache = AiSpeechCache(context)
        val saved = File(folder, "existing.mp3").apply { writeBytes(byteArrayOf(5)) }
        cache.commit(book.id, AiSpeechCache.key(config, "Existing"), "mp3", saved, cache.generation(book.id))
        val requested = mutableListOf<String>()
        val batch = AiSpeechBatch(context) { _, text, target -> requested += text; target.writeBytes(byteArrayOf(requested.size.toByte())) }
        val result = batch.generate(book, config) { }
        assertEquals(listOf("Existing", "Repeated", "Repeated"), requested)
        assertEquals(1, result.completed)
        assertArrayEquals(byteArrayOf(1), requireNotNull(cache.find(book.id, AiSpeechCache.key(config, "Existing"), "mp3")).readBytes())
        assertArrayEquals(byteArrayOf(3), requireNotNull(cache.find(book.id, AiSpeechCache.key(config, "Repeated"), "mp3")).readBytes())
        requested.clear()
        val second = batch.generate(book, config) { }
        assertEquals(1, second.completed)
        assertTrue(requested.isEmpty())
    }

    @Test fun regenerationSkipsOtherCompleteChaptersAndDoesNotOverwriteTheirAudio() = runBlocking {
        val book = book("Complete", "Old partial\nMissing")
        val cache = AiSpeechCache(context)
        for (text in listOf("Complete", "Old partial")) {
            val saved = File.createTempFile("old-audio", ".mp3", folder).apply { writeBytes(byteArrayOf(5)) }
            cache.commit(book.id, AiSpeechCache.key(config, text), "mp3", saved, cache.generation(book.id))
        }
        val requested = mutableListOf<String>()
        val result = AiSpeechBatch(context) { _, text, target -> requested += text; target.writeBytes(byteArrayOf(9)) }
            .generate(book, config) { }
        assertEquals(listOf("Old partial", "Missing"), requested)
        assertEquals(2, result.completed)
        assertArrayEquals(byteArrayOf(5), requireNotNull(cache.find(book.id, AiSpeechCache.key(config, "Complete"), "mp3")).readBytes())
        assertArrayEquals(byteArrayOf(9), requireNotNull(cache.find(book.id, AiSpeechCache.key(config, "Old partial"), "mp3")).readBytes())
    }

    @Test fun failedRegenerationPreservesTheExistingAudioForThatChunk() = runBlocking {
        val book = book("Old partial\nMissing")
        val cache = AiSpeechCache(context)
        val saved = File(folder, "old.mp3").apply { writeBytes(byteArrayOf(5)) }
        cache.commit(book.id, AiSpeechCache.key(config, "Old partial"), "mp3", saved, cache.generation(book.id))
        var temporary: File? = null
        val error = runCatching {
            AiSpeechBatch(context) { _, _, target ->
                temporary = target; target.writeBytes(byteArrayOf(9)); throw IOException("Generation failed")
            }.generate(book, config) { }
        }.exceptionOrNull()
        assertEquals("Generation failed", error?.message)
        assertFalse(requireNotNull(temporary).exists())
        assertArrayEquals(byteArrayOf(5), requireNotNull(cache.find(book.id, AiSpeechCache.key(config, "Old partial"), "mp3")).readBytes())
        assertEquals(emptySet<Int>(), cache.completedChapters(book, config))
    }

    @Test fun cancelledRegenerationPreservesOldAudioAndRemovesThePendingReplacement() = runBlocking {
        val book = book("Old partial\nMissing")
        val cache = AiSpeechCache(context)
        val saved = File(folder, "old.mp3").apply { writeBytes(byteArrayOf(5)) }
        cache.commit(book.id, AiSpeechCache.key(config, "Old partial"), "mp3", saved, cache.generation(book.id))
        val pending = CompletableDeferred<File>()
        val task = launch {
            AiSpeechBatch(context) { _, _, target ->
                target.writeBytes(byteArrayOf(9)); pending.complete(target); awaitCancellation()
            }.generate(book, config) { }
        }
        val temporary = pending.await()
        task.cancelAndJoin()
        assertFalse(temporary.exists())
        assertArrayEquals(byteArrayOf(5), requireNotNull(cache.find(book.id, AiSpeechCache.key(config, "Old partial"), "mp3")).readBytes())
        assertEquals(emptySet<Int>(), cache.completedChapters(book, config))
    }

    @Test fun cancellingKeepsCompletedChunksAndDeletesTheInFlightTemporaryFile() = runBlocking {
        val book = book("Completed\nPending\nNever requested")
        val pending = CompletableDeferred<File>()
        val requested = mutableListOf<String>()
        val batch = AiSpeechBatch(context) { _, text, target ->
            requested += text
            target.writeBytes(byteArrayOf(1))
            if (text == "Pending") { pending.complete(target); awaitCancellation() }
        }
        val task = launch { batch.generate(book, config) { } }
        val temporary = pending.await()
        task.cancelAndJoin()
        assertFalse(temporary.exists())
        assertEquals(listOf("Completed", "Pending"), requested)
        val cache = AiSpeechCache(context)
        assertNotNull(cache.find(book.id, AiSpeechCache.key(config, "Completed"), "mp3"))
        assertNull(cache.find(book.id, AiSpeechCache.key(config, "Pending"), "mp3"))
    }

    @Test fun clearingCacheDuringGenerationPreventsThePendingChunkFromWritingBack() = runBlocking {
        val book = book("First\nPending\nNever requested")
        val cache = AiSpeechCache(context)
        val requested = mutableListOf<String>()
        var pending: File? = null
        val batch = AiSpeechBatch(context) { _, text, target ->
            requested += text
            target.writeBytes(byteArrayOf(1))
            if (text == "Pending") { pending = target; cache.clear(book.id) }
        }
        val error = runCatching { batch.generate(book, config) { } }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("缓存已清理"))
        assertEquals(listOf("First", "Pending"), requested)
        assertFalse(requireNotNull(pending).exists())
        assertEquals(0L, cache.bytes(book.id))
    }

    @Test fun failureStopsAtTheFailingChunkAndPreservesCompletedCache() = runBlocking {
        val book = book("First\nFailed\nNever requested")
        val requested = mutableListOf<String>()
        var failed: File? = null
        val progress = mutableListOf<AiSpeechBatchProgress>()
        val batch = AiSpeechBatch(context) { _, text, target ->
            requested += text
            target.writeBytes(byteArrayOf(1))
            if (text == "Failed") { failed = target; throw IOException("Service failed") }
        }
        val error = runCatching { batch.generate(book, config) { progress += it } }.exceptionOrNull()
        assertEquals("Service failed", error?.message)
        assertEquals(listOf("First", "Failed"), requested)
        assertEquals(0, progress.last().completed)
        assertEquals(1, progress.last().chapterCompleted)
        assertFalse(requireNotNull(failed).exists())
        assertNotNull(AiSpeechCache(context).find(book.id, AiSpeechCache.key(config, "First"), "mp3"))
    }

    @Test fun pdfPageTextIsGeneratedWithoutRequiringAChapterTextFile() = runBlocking {
        val book = book("unused").let { original -> original.copy(format = BookFormat.PDF,
            chapters = listOf(Chapter(title = "PDF", pages = listOf(
                PageSource("source.pdf", 0, textFile("Page one\nNext paragraph")),
                PageSource("source.pdf", 1, textFile("Page two")))))) }
        val requested = mutableListOf<String>()
        val batch = AiSpeechBatch(context) { _, text, target -> requested += text; target.writeBytes(byteArrayOf(1)) }
        assertEquals(1, batch.generate(book, config) { }.completed)
        assertEquals(listOf("Page one", "Next paragraph", "Page two"), requested)
    }

    @Test fun comicsUndownloadedBooksInvalidSelectionsAndEmptyTextNeverSendRequests() = runBlocking {
        val book = book("Text")
        var requests = 0
        val batch = AiSpeechBatch(context) { _, _, _ -> requests++ }
        assertTrue(runCatching { batch.generate(book.copy(mode = LibraryMode.COMIC), config) { } }.isFailure)
        assertTrue(runCatching { batch.generate(book.copy(cloudOnly = true), config) { } }.isFailure)
        assertTrue(runCatching { batch.generate(book, config, emptySet()) { } }.isFailure)
        assertTrue(runCatching { batch.generate(book, config, setOf(10)) { } }.isFailure)
        assertTrue(runCatching { batch.generate(book("\n  \n"), config) { } }.isFailure)
        assertEquals(0, requests)
    }

    @Test fun selectedChaptersRestoreKnownDialogueSeedAndShareTheCacheVerificationPlan() = runBlocking {
        val original = book("张三说：“先说一句。”", "“后续没有提到名字。”", "“第三章继续说。”")
        val role = BookVoiceRole("张三", "张三", "青年男声", id = "zhang")
        val book = original.copy(voiceRoles = listOf(role))
        val enabled = AiSpeechConfig.fromJson(config.toJson().put("rolePromptEnabled", true))
        val cache = AiSpeechCache(context)
        cache.recordChapterRoleSeed(book, 0, emptyList(), null, role.id, cache.generation(book.id))
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val result = AiSpeechBatch(context, commonRoles = emptyList()) { _, text, target ->
            requests += text; target.writeBytes(byteArrayOf(1))
        }.generate(book, enabled, setOf(1, 2)) {}
        assertEquals(setOf(1, 2), result.completedChapters)
        assertEquals(2, requests.size)
        for (index in 1..2) {
            val expected = AiSpeechRolePlanner(book.id, book.voiceRoles).plan(aiSpeechChapterSegments(book, index).toList(), role.id).single()
            assertEquals(role.id, expected.roleId)
            assertNotNull(cache.find(book.id, AiSpeechCache.key(enabled, expected.chunk.text, expected.roleInstructions), "mp3"))
        }
        assertEquals(setOf(1, 2), cache.chapterCacheStatus(book, enabled, emptyList()).currentChapters)
        assertEquals(setOf(1, 2), cache.chapterCacheStatus(book,
            AiSpeechConfig.fromJson(enabled.toJson().put("voice", "changed")), emptyList()).otherChapters)
    }

    @Test fun aColdSelectedChapterSeedNeverReadsAnUnselectedEarlierChapter() = runBlocking {
        val original = book("unused", "张三说：“从这里开始。”", "“后面仍然是我。”")
        val role = BookVoiceRole("张三", "张三", "青年男声", id = "zhang")
        val book = original.copy(voiceRoles = listOf(role), chapters = original.chapters.mapIndexed { index, chapter ->
            if (index == 0) chapter.copy(textPath = File(folder, "unread-previous-chapter.txt").absolutePath) else chapter
        })
        val enabled = AiSpeechConfig.fromJson(config.toJson().put("rolePromptEnabled", true))
        val seeds = aiSpeechChapterSeeds(book, enabled, setOf(1, 2), emptyList())
        assertEquals(setOf(1, 2), seeds.keys)
        assertNull(seeds[1])
        assertEquals(role.id, seeds[2])
    }

    private fun book(vararg texts: String): Book = Book(title = "Batch book", format = BookFormat.EPUB,
        mode = LibraryMode.NOVEL, chapters = texts.mapIndexed { index, text ->
            Chapter(title = "Chapter $index", textPath = textFile(text))
        }).also { books += it }

    private fun textFile(text: String): String = File(folder, "${UUID.randomUUID()}.txt").apply { writeText(text) }.absolutePath
}
