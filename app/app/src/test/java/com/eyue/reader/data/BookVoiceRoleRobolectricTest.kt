package com.eyue.reader.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class BookVoiceRoleRobolectricTest {
    private val role = BookVoiceRole("张三", "张三", "低沉青年男声", "沉稳", "战斗时急促", id = "zhang")

    @Test fun eachRoleHasOneCombinedUnicodeLimitAndRequiresAValidRegex() {
        val available = 3000 - role.characterCount
        assertEquals(3000, role.copy(scenes = role.scenes + "😀".repeat(available)).characterCount)
        assertThrows(IllegalArgumentException::class.java) { role.copy(scenes = role.scenes + "字".repeat(available + 1)) }
        assertThrows(IllegalArgumentException::class.java) { role.copy(nameRegex = "[") }
        assertThrows(IllegalArgumentException::class.java) { role.copy(name = " ") }
    }

    @Test fun addingAnAliasOnlyExtendsItsRegexAndRejectsExistingNames() {
        val updated = role.addName("小三(青年)")
        assertEquals(role.name, updated.name)
        assertEquals(role.voice, updated.voice)
        assertEquals(role.description, updated.description)
        assertEquals(role.scenes, updated.scenes)
        assertTrue(Regex(updated.nameRegex).containsMatchIn("小三(青年)来了"))
        assertFalse(Regex(updated.nameRegex).containsMatchIn("小三青年"))
        assertThrows(IllegalArgumentException::class.java) { updated.addName("小三(青年)") }
        assertThrows(IllegalArgumentException::class.java) { updated.addName("张三") }
    }

    @Test fun matchingUsesTheFullParagraphAndKeepsUnmatchedRolesOutOfTheRequest() {
        val text = "前".repeat(260) + "张三走来。\n没有角色的下一段。"
        val prompt = roleVoicePrompt(listOf(role, BookVoiceRole("李四", "李四", "高音")), text, 0, 240)
        assertTrue(prompt.contains("张三"))
        assertTrue(prompt.contains(role.voice))
        assertTrue(prompt.contains(role.description))
        assertTrue(prompt.contains(role.scenes))
        assertFalse(prompt.contains("李四"))
        assertEquals("", roleVoicePrompt(listOf(role), text, text.indexOf("没有"), text.length))
        assertEquals("", roleVoicePrompt(emptyList(), text))
    }

    @Test fun rolePromptsAreInstructionsInBothProtocolsAndParticipateInTheAudioCacheKey() {
        val text = "张三说你好。"
        val hint = roleVoicePrompt(listOf(role), text)
        for (protocol in SpeechApiProtocol.entries) {
            val config = AiSpeechConfig("https://voice.example/v1", "private-test-key", "tts", "voice", "旁白自然", protocol, rolePromptEnabled = true)
            val request = JSONObject(config.request(text, hint).toString(Charsets.UTF_8))
            val instructions = if (protocol == SpeechApiProtocol.MIMO) {
                assertEquals(text, request.getJSONArray("messages").getJSONObject(1).getString("content"))
                request.getJSONArray("messages").getJSONObject(0).getString("content")
            } else {
                assertEquals(text, request.getString("input")); request.getString("instructions")
            }
            assertTrue(instructions.startsWith("旁白自然"))
            assertTrue(instructions.contains(role.voice))
            assertNotEquals(AiSpeechCache.key(config, text), AiSpeechCache.key(config, text, hint))
            assertNotEquals(AiSpeechCache.key(config, text, hint), AiSpeechCache.key(config, text,
                roleVoicePrompt(listOf(role.copy(voice = "明亮女声")), text)))
            assertEquals(AiSpeechCache.key(config, "普通旁白"), AiSpeechCache.key(config, "普通旁白",
                roleVoicePrompt(listOf(role), "普通旁白")))
        }
    }

    @Test fun chunksOfALongParagraphReuseOneResolvedPromptAndKeepNextParagraphSeparate() {
        val text = "字".repeat(100_000) + "张三。\n没有角色的下一段。"
        val resolver = RoleVoicePromptResolver(listOf(role))
        val prompt = resolver.prompt(text, 0, 240)
        for (start in 240 until 100_000 step 240) {
            assertSame(prompt, resolver.prompt(text, start, minOf(start + 240, 100_000)))
        }
        assertEquals("", resolver.prompt(text, text.indexOf("没有"), text.length))
    }

    @Test fun cachedParagraphKeepsDistinctSentenceVoicesAndReusesTheirPromptsAcrossChunks() {
        val second = BookVoiceRole("李四", "李四", "清亮女声", id = "li")
        val text = "张三说：“" + "甲".repeat(800) + "。”李四答道：“" + "乙".repeat(800) + "！”\n下一段。\n末段。"
        val resolver = RoleVoicePromptResolver(listOf(role, second))
        val firstPrompt = resolver.prompt(text, 250, 490)
        val secondStart = text.indexOf("李四")
        val secondPrompt = resolver.prompt(text, secondStart + 250, secondStart + 490)
        assertTrue(firstPrompt.contains(role.voice))
        assertFalse(firstPrompt.contains(second.voice))
        assertTrue(secondPrompt.contains(second.voice))
        assertFalse(secondPrompt.contains(role.voice))
        assertSame(firstPrompt, resolver.prompt(text, 500, 740))
        assertSame(secondPrompt, resolver.prompt(text, secondStart + 500, secondStart + 740))
        resolver.prompt(text, text.indexOf("下一段"), text.indexOf("末段"))
        resolver.prompt(text, text.indexOf("末段"), text.length)
        assertSame(firstPrompt, resolver.prompt(text, 0, 240))
    }

    @Test fun cachedParagraphInheritsItsPreviousDialogueWhenFirstRequestedFromTheMiddle() {
        val second = BookVoiceRole("李四", "李四", "清亮女声", id = "li")
        val text = "张三说：“前文。”\n“" + "后".repeat(800) + "。”\n李四说：“接下来。”\n“末段。”"
        val resolver = RoleVoicePromptResolver(listOf(role, second))
        val inheritedStart = text.indexOf('\n') + 1
        val inherited = resolver.prompt(text, inheritedStart + 300, inheritedStart + 540)
        assertTrue(inherited.contains(role.voice))
        assertFalse(inherited.contains(second.voice))
        val last = resolver.prompt(text, text.lastIndexOf('\n') + 1, text.length)
        assertTrue(last.contains(second.voice))
        assertSame(inherited, resolver.prompt(text, inheritedStart, inheritedStart + 240))
    }

    @Test fun bookSerializationPreservesIndependentRolesAndLegacyBooksHaveNone() {
        val context = RuntimeEnvironment.getApplication()
        val repository = LibraryRepository(context)
        val first = Book(title = "第一本", format = BookFormat.TXT, mode = LibraryMode.NOVEL, chapters = emptyList(), voiceRoles = listOf(role))
        val second = first.copy(id = "another-book", title = "第二本", voiceRoles = listOf(role.copy(voice = "不同音色")))
        assertEquals(first, repository.readBook(repository.writeBook(first)))
        assertEquals(second.voiceRoles, repository.readBook(repository.writeBook(second)).voiceRoles)
        assertNotEquals(first.voiceRoles, second.voiceRoles)
        assertFalse(repository.writeBook(first.copy(voiceRoles = emptyList())).has("voiceRoles"))
        assertTrue(repository.readBook(repository.writeBook(first).apply { remove("voiceRoles") }).voiceRoles.isEmpty())
    }

    @Test fun changedRolesInvalidateChapterCacheWithoutDiscardingTheExistingAudio() = kotlinx.coroutines.runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val folder = File(context.cacheDir, "role-cache-${UUID.randomUUID()}").apply { mkdirs() }
        val text = "张三说你好。"
        val book = Book(title = "缓存角色", format = BookFormat.TXT, mode = LibraryMode.NOVEL,
            chapters = listOf(Chapter(title = "第一章", textPath = File(folder, "text.txt").apply { writeText(text) }.absolutePath)), voiceRoles = listOf(role))
        val config = AiSpeechConfig("https://voice.example/v1", "", "tts", "voice", rolePromptEnabled = true)
        val cache = AiSpeechCache(context)
        try {
            val key = AiSpeechCache.key(config, text, roleVoicePrompt(book.voiceRoles, text))
            cache.commit(book.id, key, "mp3", File(folder, "audio.mp3").apply { writeBytes(byteArrayOf(1)) }, cache.generation(book.id))
            assertEquals(setOf(0), cache.completedChapters(book, config))
            assertEquals(emptySet<Int>(), cache.completedChapters(book.copy(voiceRoles = listOf(role.copy(voice = "不同声音"))), config))
            assertNotNull(cache.find(book.id, key, "mp3"))
        } finally { cache.clear(book.id); folder.deleteRecursively() }
    }
}
