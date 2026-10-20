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

    @Test fun badgeRefreshUsesRecordedChaptersWithoutReadingUntouchedTextOrDiscoveringLooseAudio() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val folder = File(context.cacheDir, "completion-index-${UUID.randomUUID()}").apply { mkdirs() }
        val source = File(folder, "ready.txt").apply { writeText("Ready") }
        val book = Book(title = "Indexed", format = BookFormat.EPUB, mode = LibraryMode.NOVEL,
            chapters = listOf(Chapter(title = "Ready", textPath = source.absolutePath),
                Chapter(title = "Untouched", textPath = File(folder, "not-loaded.txt").absolutePath)))
        val config = AiSpeechConfig("https://example.org/v1", "key", "tts", "voice", rolePromptEnabled = true)
        val other = AiSpeechConfig.fromJson(config.toJson().put("voice", "other"))
        val cache = AiSpeechCache(context)
        try {
            val temporary = File(folder, "audio.mp3").apply { writeBytes(byteArrayOf(1)) }
            cache.commit(book.id, AiSpeechCache.key(config, "Ready"), "mp3", temporary, cache.generation(book.id))
            assertEquals(AiSpeechChapterCacheStatus(), cache.chapterCacheStatus(book, config, emptyList()))
            assertTrue(cache.recordChapterCompletion(book, config, 0, emptyList()))
            repeat(3) {
                assertEquals(setOf(0), AiSpeechCache(context).chapterCacheStatus(book, config, emptyList()).currentChapters)
                assertEquals(setOf(0), cache.chapterCacheStatus(book, other, emptyList()).otherChapters)
            }
            assertFalse(File(requireNotNull(book.chapters[1].textPath)).exists())
        } finally { cache.clear(book.id); folder.deleteRecursively() }
        Unit
    }

    @Test fun sourceMetadataInvalidatesOnlyAffectedChaptersAndInheritedRoleSeeds() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val folder = File(context.cacheDir, "completion-sources-${UUID.randomUUID()}").apply { mkdirs() }
        val first = File(folder, "first.txt").apply { writeText("First") }
        val second = File(folder, "second.txt").apply { writeText("Second") }
        val book = Book(title = "Sources", format = BookFormat.TXT, mode = LibraryMode.NOVEL,
            chapters = listOf(Chapter(title = "First", textPath = first.absolutePath), Chapter(title = "Second", textPath = second.absolutePath)))
        val disabled = AiSpeechConfig("https://example.org/v1", "key", "tts", "voice")
        val enabled = AiSpeechConfig.fromJson(disabled.toJson().put("rolePromptEnabled", true))
        val cache = AiSpeechCache(context)
        try {
            for ((index, text) in listOf("First", "Second").withIndex()) {
                val temporary = File(folder, "audio-$index.mp3").apply { writeBytes(byteArrayOf(1)) }
                cache.commit(book.id, AiSpeechCache.key(disabled, text), "mp3", temporary, cache.generation(book.id))
                assertTrue(cache.recordChapterCompletion(book, disabled, index, emptyList()))
                assertTrue(cache.recordChapterCompletion(book, enabled, index, emptyList()))
            }
            val previousModified = first.lastModified()
            first.writeText("Other")
            assertTrue(first.setLastModified(previousModified + 2_000))
            assertEquals(setOf(1), cache.chapterCacheStatus(book, disabled, emptyList()).currentChapters)
            assertEquals(emptySet<Int>(), cache.chapterCacheStatus(book, enabled, emptyList()).currentChapters)
            assertEquals(setOf(1), cache.chapterCacheStatus(book, enabled, emptyList()).otherChapters)
            assertEquals(emptySet<Int>(), cache.chapterCacheStatus(book.copy(chapters = listOf(book.chapters[0])), disabled, emptyList()).currentChapters)
            second.delete()
            assertEquals(AiSpeechChapterCacheStatus(), cache.chapterCacheStatus(book, disabled, emptyList()))
        } finally { cache.clear(book.id); folder.deleteRecursively() }
        Unit
    }

    @Test fun dialogueCheckpointsInvalidateColdCompletionsAndRejectChangedSourcesRolesVersionsAndOldGenerations() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val folder = File(context.cacheDir, "completion-seed-${UUID.randomUUID()}").apply { mkdirs() }
        val source = File(folder, "first.txt").apply { writeText("First") }
        val second = File(folder, "second.txt").apply { writeText("Second") }
        val role = BookVoiceRole("张三", "张三", "男声", id = "zhang")
        val book = Book(title = "Checkpoints", format = BookFormat.TXT, mode = LibraryMode.NOVEL,
            chapters = listOf(Chapter(title = "First", textPath = source.absolutePath), Chapter(title = "Second", textPath = second.absolutePath)),
            voiceRoles = listOf(role))
        val config = AiSpeechConfig("https://example.org/v1", "key", "tts", "voice", rolePromptEnabled = true)
        val cache = AiSpeechCache(context)
        try {
            val generation = cache.generation(book.id)
            val key = AiSpeechCache.key(config, "Second")
            val temporary = File(folder, "audio.mp3").apply { writeBytes(byteArrayOf(1)) }
            cache.commit(book.id, key, "mp3", temporary, generation)
            assertTrue(cache.recordPlannedChapterCompletion(book, config, 1, emptyList(), null, listOf(key), generation))
            assertEquals(setOf(1), cache.chapterCacheStatus(book, config, emptyList()).currentChapters)
            assertNull(cache.chapterRoleSeed(book, 0, emptyList()))
            assertNull(cache.chapterRoleSeed(book, 1, emptyList()))
            cache.recordChapterRoleSeed(book, 0, emptyList(), null, role.id, generation)
            assertEquals(role.id, AiSpeechCache(context).chapterRoleSeed(book, 1, emptyList()))
            assertEquals(emptySet<Int>(), cache.chapterCacheStatus(book, config, emptyList()).currentChapters)
            assertTrue(cache.recordPlannedChapterCompletion(book, config, 1, emptyList(), role.id, listOf(key), generation))
            assertEquals(setOf(1), cache.chapterCacheStatus(book, config, emptyList()).currentChapters)
            assertNull(cache.chapterRoleSeed(book.copy(contentVersion = book.contentVersion + 1), 1, emptyList()))
            assertNull(cache.chapterRoleSeed(book.copy(voiceRoles = listOf(role.copy(voice = "新音色"))), 1, emptyList()))
            assertNull(cache.chapterRoleSeed(book, 1, listOf(role.copy(id = "common"))))
            val previousModified = source.lastModified()
            source.writeText("Other")
            assertTrue(source.setLastModified(previousModified + 2_000))
            assertNull(cache.chapterRoleSeed(book, 1, emptyList()))
            cache.clear(book.id)
            cache.recordChapterRoleSeed(book, 0, emptyList(), null, role.id, generation)
            assertEquals(0L, cache.bytes(book.id))
            assertNull(cache.chapterRoleSeed(book, 1, emptyList()))
        } finally { cache.clear(book.id); folder.deleteRecursively() }
        Unit
    }

    @Test fun aNewEarlierCheckpointInvalidatesDependentColdCheckpointTailsAndBadges() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val folder = File(context.cacheDir, "completion-seed-chain-${UUID.randomUUID()}").apply { mkdirs() }
        val texts = listOf("First", "Second", "Third")
        val firstRole = BookVoiceRole("张三", "张三", "男声", id = "zhang")
        val secondRole = BookVoiceRole("李四", "李四", "女声", id = "li")
        val book = Book(title = "Checkpoint chain", format = BookFormat.TXT, mode = LibraryMode.NOVEL,
            chapters = texts.mapIndexed { index, text -> Chapter(title = text,
                textPath = File(folder, "$index.txt").apply { writeText(text) }.absolutePath) },
            voiceRoles = listOf(firstRole, secondRole))
        val config = AiSpeechConfig("https://example.org/v1", "key", "tts", "voice", rolePromptEnabled = true)
        val cache = AiSpeechCache(context)
        try {
            val generation = cache.generation(book.id)
            cache.recordChapterRoleSeed(book, 1, emptyList(), null, secondRole.id, generation)
            for (index in 1..2) {
                val key = AiSpeechCache.key(config, texts[index])
                val temporary = File(folder, "audio-$index.mp3").apply { writeBytes(byteArrayOf(1)) }
                cache.commit(book.id, key, "mp3", temporary, generation)
                assertTrue(cache.recordPlannedChapterCompletion(book, config, index, emptyList(),
                    if (index == 1) null else secondRole.id, listOf(key), generation))
            }
            assertEquals(secondRole.id, cache.chapterRoleSeed(book, 2, emptyList()))
            assertEquals(setOf(1, 2), cache.chapterCacheStatus(book, config, emptyList()).currentChapters)
            cache.recordChapterRoleSeed(book, 0, emptyList(), null, firstRole.id, generation)
            assertEquals(firstRole.id, cache.chapterRoleSeed(book, 1, emptyList()))
            assertNull(cache.chapterRoleSeed(book, 2, emptyList()))
            assertEquals(emptySet<Int>(), cache.chapterCacheStatus(book, config, emptyList()).currentChapters)
            cache.recordChapterRoleSeed(book, 1, emptyList(), firstRole.id, secondRole.id, generation)
            assertEquals(secondRole.id, cache.chapterRoleSeed(book, 2, emptyList()))
            assertEquals(setOf(2), cache.chapterCacheStatus(book, config, emptyList()).currentChapters)
        } finally { cache.clear(book.id); folder.deleteRecursively() }
        Unit
    }

    @Test fun emptyChapterRecordsNeedNoAudioOrRoleContextButStillValidateSourcesVersionsAndClears() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val folder = File(context.cacheDir, "completion-empty-${UUID.randomUUID()}").apply { mkdirs() }
        val source = File(folder, "empty.txt").apply { writeText(" \n") }
        val book = Book(title = "Empty", format = BookFormat.TXT, mode = LibraryMode.NOVEL,
            chapters = listOf(Chapter(title = "Untouched", textPath = File(folder, "missing.txt").absolutePath),
                Chapter(title = "Empty", textPath = source.absolutePath)))
        val config = AiSpeechConfig("https://example.org/v1", "key", "tts", "voice", rolePromptEnabled = true)
        val cache = AiSpeechCache(context)
        try {
            val generation = cache.generation(book.id)
            assertTrue(cache.recordPlannedChapterCompletion(book, config, 1, emptyList(), null, emptyList(), generation))
            val changedRoles = book.copy(voiceRoles = listOf(BookVoiceRole("张三", "张三", "男声")))
            val result = cache.chapterCacheStatus(changedRoles, null, emptyList())
            assertEquals(emptySet<Int>(), result.currentChapters)
            assertEquals(emptySet<Int>(), result.otherChapters)
            assertEquals(setOf(1), result.emptyChapters)
            assertEquals(emptySet<Int>(), cache.chapterCacheStatus(book.copy(contentVersion = book.contentVersion + 1), config, emptyList()).emptyChapters)
            source.writeText("Now readable")
            assertEquals(emptySet<Int>(), cache.chapterCacheStatus(book, config, emptyList()).emptyChapters)
            cache.clear(book.id)
            assertFalse(cache.recordPlannedChapterCompletion(book, config, 1, emptyList(), null, emptyList(), generation))
            assertEquals(0L, cache.bytes(book.id))
        } finally { cache.clear(book.id); folder.deleteRecursively() }
        Unit
    }
}
