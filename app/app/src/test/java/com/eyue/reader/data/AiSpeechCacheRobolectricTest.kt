package com.eyue.reader.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AiSpeechCacheRobolectricTest {
    @Test fun completedAudioIsReusablePerBookAndClearingOneBookKeepsAnother() {
        val context = RuntimeEnvironment.getApplication()
        val cache = AiSpeechCache(context)
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        val config = AiSpeechConfig("https://voice.example.org/v1", "secret", "tts", "voice")
        val key = AiSpeechCache.key(config, "正文")
        fun save(book: String): File {
            val temporary = File.createTempFile("speech", ".mp3", context.cacheDir).apply { writeBytes(byteArrayOf(1, 2, 3)) }
            return requireNotNull(cache.commit(book, key, "mp3", temporary, cache.generation(book)))
        }
        try {
            val file = save(first)
            save(second)
            assertEquals(file, cache.find(first, key, "mp3"))
            assertEquals(3L, cache.bytes(first))
            cache.clear(first)
            assertNull(cache.find(first, key, "mp3"))
            assertEquals(0L, cache.bytes(first))
            assertEquals(3L, cache.bytes(second))
        } finally { cache.clear(first); cache.clear(second) }
    }
    @Test fun cacheIdentityDependsOnAudioInputsButNotProfileNameKeyOrPlaybackSpeed() {
        fun key(url: String = "https://voice.example.org/v1", secret: String = "secret", model: String = "tts", voice: String = "voice",
            style: String = "", name: String = "默认", text: String = "正文") = AiSpeechCache.key(AiSpeechConfig(url, secret, model, voice, style, name = name), text)
        val base = key()
        assertEquals(base, key(secret = "changed", name = "新名称"))
        assertNotEquals(base, key(url = "https://other.example.org/v1"))
        assertNotEquals(base, key(model = "other"))
        assertNotEquals(base, key(voice = "other"))
        assertNotEquals(base, key(style = "轻快"))
        assertNotEquals(base, key(text = "修改后正文"))
    }
    @Test fun clearingDuringDownloadRejectsTheOldCommitButAllowsLaterRequests() {
        val context = RuntimeEnvironment.getApplication()
        val cache = AiSpeechCache(context)
        val book = UUID.randomUUID().toString()
        val temporary = File.createTempFile("speech", ".wav", context.cacheDir).apply { writeBytes(byteArrayOf(1)) }
        try {
            val generation = cache.generation(book)
            cache.clear(book)
            assertNull(cache.commit(book, "key", "wav", temporary, generation, overwrite = true))
            assertTrue(temporary.exists())
            assertEquals(0L, cache.bytes(book))
            assertNotNull(cache.commit(book, "key", "wav", temporary, cache.generation(book)))
            assertEquals(1L, cache.bytes(book))
        } finally { temporary.delete(); cache.clear(book) }
    }

    @Test fun replacingAudioIsExplicitAndInvalidNewAudioCannotDestroyTheExistingFile() {
        val context = RuntimeEnvironment.getApplication()
        val cache = AiSpeechCache(context)
        val book = UUID.randomUUID().toString()
        val files = mutableListOf<File>()
        fun temporary(bytes: ByteArray) = File.createTempFile("speech-replace", ".mp3", context.cacheDir)
            .apply { writeBytes(bytes); files += this }
        try {
            val generation = cache.generation(book)
            val original = requireNotNull(cache.commit(book, "same-key", "mp3", temporary(byteArrayOf(1)), generation))
            cache.commit(book, "same-key", "mp3", temporary(byteArrayOf(2)), generation)
            assertArrayEquals(byteArrayOf(1), original.readBytes())
            val invalid = temporary(byteArrayOf())
            assertTrue(runCatching { cache.commit(book, "same-key", "mp3", invalid, generation, overwrite = true) }.isFailure)
            assertArrayEquals(byteArrayOf(1), original.readBytes())
            val replacement = temporary(byteArrayOf(3, 4))
            assertEquals(original, cache.commit(book, "same-key", "mp3", replacement, generation, overwrite = true))
            assertFalse(replacement.exists())
            assertArrayEquals(byteArrayOf(3, 4), original.readBytes())
        } finally { files.forEach { it.delete() }; cache.clear(book) }
    }

    @Test fun fullLiveChaptersRetainOtherConfigurationBadgesWithoutSkippingANewVoiceOrCountingPartialAudio() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val folder = File(context.cacheDir, "completion-${UUID.randomUUID()}").apply { mkdirs() }
        val source = File(folder, "chapter.txt").apply { writeText("First\nSecond") }
        val book = Book(title = "Completion", format = BookFormat.EPUB, mode = LibraryMode.NOVEL,
            chapters = listOf(Chapter(title = "One", textPath = source.absolutePath)))
        val cache = AiSpeechCache(context)
        val first = AiSpeechConfig("https://example.org/v1", "key", "tts", "first")
        val second = AiSpeechConfig.fromJson(first.toJson().put("voice", "second"))
        fun save(config: AiSpeechConfig, text: String) {
            val temp = File.createTempFile("completion-audio", ".mp3", folder).apply { writeBytes(byteArrayOf(1)) }
            cache.commit(book.id, AiSpeechCache.key(config, text), "mp3", temp, cache.generation(book.id))
        }
        try {
            save(first, "First")
            assertFalse(cache.recordChapterCompletion(book, first, 0, emptyList()))
            assertEquals(emptySet<Int>(), cache.chapterCacheStatus(book, second, emptyList()).otherChapters)
            save(first, "Second")
            assertTrue(cache.recordChapterCompletion(book, first, 0, emptyList()))
            assertTrue("Reported storage includes audio and the completion index", cache.bytes(book.id) > 2L)
            assertEquals(setOf(0), cache.chapterCacheStatus(book, first, emptyList()).currentChapters)
            assertEquals(setOf(0), cache.chapterCacheStatus(book, second, emptyList()).otherChapters)
            assertEquals(emptySet<Int>(), cache.chapterCacheStatus(book, second, emptyList()).currentChapters)

            save(second, "First")
            assertFalse(cache.recordChapterCompletion(book, second, 0, emptyList()))
            val requested = mutableListOf<String>()
            AiSpeechBatch(context) { _, text, target -> requested += text; target.writeBytes(byteArrayOf(2)) }
                .generate(book, second) {}
            assertEquals(listOf("First", "Second"), requested)
            requested.clear()
            AiSpeechBatch(context) { _, text, _ -> requested += text }.generate(book, first) {}
            assertTrue(requested.isEmpty())
            cache.remove(book.id, AiSpeechCache.key(first, "First"), "mp3")
            val switched = cache.chapterCacheStatus(book, first, emptyList())
            assertEquals(emptySet<Int>(), switched.currentChapters)
            assertEquals(setOf(0), switched.otherChapters)
            cache.clear(book.id)
            assertEquals(AiSpeechChapterCacheStatus(), cache.chapterCacheStatus(book, second, emptyList()))
        } finally { cache.clear(book.id); folder.deleteRecursively() }
        Unit
    }

    @Test fun otherConfigurationRecordsValidateTextVersionRolesAndRealAudioFiles() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val folder = File(context.cacheDir, "completion-invalid-${UUID.randomUUID()}").apply { mkdirs() }
        val source = File(folder, "chapter.txt").apply { writeText("张三说：“你好。”") }
        val role = BookVoiceRole("张三", "张三", "青年男声", id = "zhang")
        val book = Book(title = "Validation", format = BookFormat.MOBI, mode = LibraryMode.NOVEL,
            chapters = listOf(Chapter(title = "One", textPath = source.absolutePath)), voiceRoles = listOf(role))
        val enabled = AiSpeechConfig("https://example.org/v1", "key", "tts", "voice", rolePromptEnabled = true)
        val other = AiSpeechConfig.fromJson(enabled.toJson().put("voice", "other"))
        val cache = AiSpeechCache(context)
        try {
            AiSpeechBatch(context, commonRoles = emptyList()) { _, _, target -> target.writeBytes(byteArrayOf(1)) }
                .generate(book, enabled) {}
            assertEquals(setOf(0), cache.chapterCacheStatus(book, other, emptyList()).otherChapters)
            assertEquals(emptySet<Int>(), cache.chapterCacheStatus(book.copy(contentVersion = book.contentVersion + 1), other, emptyList()).otherChapters)
            assertEquals(emptySet<Int>(), cache.chapterCacheStatus(book.copy(voiceRoles = listOf(role.copy(voice = "低沉男声"))), other, emptyList()).otherChapters)
            source.writeText("张三说：“修改后的正文。”")
            assertEquals(emptySet<Int>(), cache.chapterCacheStatus(book, other, emptyList()).otherChapters)
            assertEquals(emptySet<Int>(), cache.completedChapters(book, enabled))
            source.writeText("张三说：“你好。”")
            val planned = aiSpeechPlannedChapter(book, enabled, 0, emptyList(), null).single()
            cache.remove(book.id, AiSpeechCache.key(enabled, planned.chunk.text, planned.roleInstructions), "mp3")
            assertEquals(emptySet<Int>(), cache.chapterCacheStatus(book, other, emptyList()).otherChapters)

            val disabled = AiSpeechConfig.fromJson(enabled.toJson().put("rolePromptEnabled", false))
            AiSpeechBatch(context) { _, _, target -> target.writeBytes(byteArrayOf(1)) }.generate(book, disabled) {}
            val changedRoles = book.copy(voiceRoles = listOf(role.copy(voice = "完全不同")))
            assertEquals(setOf(0), cache.chapterCacheStatus(changedRoles, disabled, emptyList()).currentChapters)
            assertEquals(setOf(0), cache.chapterCacheStatus(changedRoles,
                AiSpeechConfig.fromJson(disabled.toJson().put("voice", "another")), emptyList()).otherChapters)
        } finally { cache.clear(book.id); folder.deleteRecursively() }
        Unit
    }

    @Test fun aClearBetweenPlanningAndRecordingCannotRestoreACompletionIndex() {
        val context = RuntimeEnvironment.getApplication()
        val source = File.createTempFile("completion-guard", ".txt", context.cacheDir).apply { writeText("Text") }
        val book = Book(title = "Guard", format = BookFormat.TXT, mode = LibraryMode.NOVEL,
            chapters = listOf(Chapter(title = "One", textPath = source.absolutePath)))
        val cache = AiSpeechCache(context)
        val config = AiSpeechConfig("https://example.org/v1", "key", "tts", "voice")
        try {
            val oldGeneration = cache.generation(book.id)
            val key = AiSpeechCache.key(config, "Text")
            val temp = File.createTempFile("completion-guard", ".mp3", context.cacheDir).apply { writeBytes(byteArrayOf(1)) }
            cache.commit(book.id, key, "mp3", temp, oldGeneration)
            cache.clear(book.id)
            assertFalse(cache.recordPlannedChapterCompletion(book, config, 0, emptyList(), null, listOf(key), oldGeneration))
            assertEquals(0L, cache.bytes(book.id))
        } finally { cache.clear(book.id); source.delete() }
    }
}
