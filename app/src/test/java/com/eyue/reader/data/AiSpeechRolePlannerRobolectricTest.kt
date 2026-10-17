package com.eyue.reader.data

import com.eyue.reader.ui.ReaderSpeechSegment
import com.eyue.reader.ui.aiSpeechChunks
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AiSpeechRolePlannerRobolectricTest {
    private val zhang = BookVoiceRole("张三", "张三|小张", "低沉青年男声", id = "zhang")
    private val li = BookVoiceRole("李四", "李四|小李", "清亮成年女声", id = "li")
    private fun segment(text: String, chapter: Int = 0) = ReaderSpeechSegment(chapter, 0, 0, text)

    @Test fun severalCharactersInOneParagraphUseOnlyOneRolePerSentenceAndKeepOriginalOffsets() {
        val text = "张三说：“你好。”李四答道：“再见！”"
        val segment = segment(text)
        val plan = AiSpeechRolePlanner("book", listOf(zhang, li)).plan(listOf(segment)).toList()
        assertEquals(listOf("zhang", "li"), plan.map { it.roleId })
        assertEquals(text, plan.joinToString("") { it.chunk.text })
        plan.forEach {
            assertEquals(text.substring(it.chunk.start, it.chunk.end), it.chunk.text)
            assertEquals(1, Regex("角色：").findAll(it.roleInstructions).count())
            assertFalse(it.roleInstructions.contains(if (it.roleId == "zhang") li.voice else zhang.voice))
        }
    }

    @Test fun quoteAttributionSupportsBeforeAndAfterNamesAndChineseOrEnglishQuotes() {
        val examples = listOf("张三对李四说：“你好。”" to "zhang", "“你好。”张三答道，李四点头。" to "zhang",
            "李四问张三：『真的？』" to "li", "\"Hello!\" 李四 replied，张三听着。" to "li",
            "张三说：「等等！」李四停下。" to "zhang")
        examples.forEach { (text, role) ->
            val plan = AiSpeechRolePlanner("book", listOf(zhang, li)).plan(listOf(segment(text))).toList()
            assertEquals(text, plan.joinToString("") { it.chunk.text })
            assertEquals(role, plan.first { it.chunk.text.any { char -> char in "“「『\"" } }.roleId)
        }
    }

    @Test fun englishSentenceStopsSeparateSpeakersWhileDecimalPointsStayWithinTheirSentence() {
        val text = "张三 said: \"I paid 3.5 yuan.\" 李四 replied: \"I paid 4.5 yuan.\""
        val plan = AiSpeechRolePlanner("book", listOf(zhang, li)).plan(listOf(segment(text))).toList()
        assertEquals(listOf("zhang", "li"), plan.map { it.roleId })
        assertEquals(text, plan.joinToString("") { it.chunk.text })
        assertTrue(plan[0].chunk.text.contains("3.5"))
        assertTrue(plan[1].chunk.text.contains("4.5"))
        plan.forEach { assertEquals(1, Regex("角色：").findAll(it.roleInstructions).count()) }
    }

    @Test fun priorityUsesBookCharacterThenBookNarratorThenCommonCharacterAndNarrator() {
        val bookNarrator = zhang.copy(id = "book-narrator", kind = VoiceRoleKind.NARRATOR)
        val commonCharacter = zhang.copy(id = "common-character")
        val commonNarrator = zhang.copy(id = "common-narrator", kind = VoiceRoleKind.NARRATOR)
        fun chosen(book: List<BookVoiceRole>, common: List<BookVoiceRole>) = AiSpeechRolePlanner("book", book, common)
            .plan(listOf(segment("张三走来。"))).single().roleId
        assertEquals(zhang.id, chosen(listOf(zhang, bookNarrator), listOf(commonCharacter, commonNarrator)))
        assertEquals(bookNarrator.id, chosen(listOf(zhang.copy(enabled = false), bookNarrator), listOf(commonCharacter, commonNarrator)))
        assertEquals(commonCharacter.id, chosen(emptyList(), listOf(commonCharacter, commonNarrator)))
        assertEquals(commonNarrator.id, chosen(emptyList(), listOf(commonCharacter.copy(enabled = false), commonNarrator)))
    }

    @Test fun unattributedDialogueInheritsTheLastDialogueVoiceAcrossParagraphsAndChapterSeeds() {
        val first = segment("张三说：“你好。”\n普通叙述。\n“下一句没有名字。”")
        val planner = AiSpeechRolePlanner("book", listOf(zhang, li), defaultCommonVoiceRoles())
        val plan = planner.plan(listOf(first, segment("“换章后仍然是我。”", 1))).toList()
        assertEquals(zhang.id, plan.last().roleId)
        val seed = planner.seedRoleId(listOf(first))
        assertEquals(zhang.id, seed)
        val separate = AiSpeechRolePlanner("book", listOf(zhang, li), defaultCommonVoiceRoles())
            .plan(listOf(segment("“换章后仍然是我。”", 1)), seed).toList()
        assertEquals(plan.last().roleInstructions, separate.single().roleInstructions)
        assertEquals(plan.last().roleId, separate.single().roleId)
    }

    @Test fun aNameMentionedInsideUnattributedDialogueDoesNotReplaceItsPreviousSpeaker() {
        val text = "张三说：“你好。”\n“我去找李四。”\n李四说：“请回来。”\n“张三也在等。”"
        val planner = AiSpeechRolePlanner("book", listOf(zhang, li))
        val full = planner.plan(listOf(segment(text))).toList()
        assertEquals(listOf("zhang", "zhang", "li", "li"), full.map { it.roleId })
        val start = text.indexOf('\n') + 1
        val partial = planner.plan(listOf(ReaderSpeechSegment(0, 0, start, text.substring(start), text))).toList()
        assertEquals(full.drop(1).map { it.roleInstructions }, partial.map { it.roleInstructions })
        assertEquals(full.drop(1).map { it.chunk.text }, partial.map { it.chunk.text })
    }

    @Test fun aMentionedObjectKeepsTheCrossChapterSeedUntilAnExplicitSpeakerChangesIt() {
        val planner = AiSpeechRolePlanner("book", listOf(zhang, li))
        val first = segment("张三说：“你好。”\n“我去找李四。”")
        val second = segment("“李四就在门外。”\n李四说：“张三在这里。”", 1)
        val seed = planner.seedRoleId(listOf(first))
        assertEquals(zhang.id, seed)
        val independent = planner.plan(listOf(second), seed).toList()
        assertEquals(listOf("zhang", "li"), independent.map { it.roleId })
        assertEquals(planner.plan(listOf(first, second)).toList().takeLast(2), independent)
    }

    @Test fun narratorChoiceIsStableAcrossSessionsAndDisabledNarratorsAreNeverUsed() {
        val narrators = defaultCommonVoiceRoles()
        val source = listOf(segment("清晨天色明亮。\n街道恢复平静。"))
        val first = AiSpeechRolePlanner("book", emptyList(), narrators).plan(source).toList()
        val second = AiSpeechRolePlanner("book", emptyList(), narrators).plan(source).toList()
        assertEquals(first, second)
        assertTrue(first.all { it.roleId in narrators.map { it.id } })
        val only = AiSpeechRolePlanner("book", emptyList(), narrators.mapIndexed { index, role -> role.copy(enabled = index == 1) }).plan(source).toList()
        assertTrue(only.all { it.roleId == narrators[1].id })
        assertTrue(AiSpeechRolePlanner("book", listOf(zhang.copy(enabled = false))).plan(source).all { it.roleId == null && it.roleInstructions.isEmpty() })
    }

    @Test fun midParagraphContextAndIndependentPlanningKeepTheSameRoleAndRequestKey() {
        val text = "张三走来。" + "正文".repeat(300)
        val offset = 100
        val full = segment(text)
        val partial = ReaderSpeechSegment(0, 0, offset, text.substring(offset), text)
        val planner = AiSpeechRolePlanner("book", listOf(zhang))
        val expected = planner.plan(listOf(full)).first().roleInstructions
        val result = planner.plan(listOf(partial)).first()
        assertEquals(expected, result.roleInstructions)
        assertEquals(text.substring(offset, offset + result.chunk.text.length), result.chunk.text)
        assertEquals(0, result.chunk.start)
        val config = AiSpeechConfig("https://voice.example/v1", "", "tts", "voice", rolePromptEnabled = true)
        assertEquals(AiSpeechCache.key(config, result.chunk.text, expected), AiSpeechCache.key(config, result.chunk.text, result.roleInstructions))
    }

    @Test fun transportChunksWithinOneSentenceReuseTheSameRoleInstructionsObject() {
        val text = "张三说：“" + "长句".repeat(1_000) + "。”"
        val planned = AiSpeechRolePlanner("book", listOf(zhang)).plan(listOf(segment(text))).toList()
        assertTrue(planned.size > 3)
        assertEquals(text, planned.joinToString("") { it.chunk.text })
        planned.forEach {
            assertEquals(zhang.id, it.roleId)
            assertSame(planned.first().roleInstructions, it.roleInstructions)
        }
    }

    @Test fun noEnabledRolesRetainsThePreviousChunkLayoutIncludingSurrogateBoundaries() {
        val source = listOf(segment("甲".repeat(239) + "😀" + "乙".repeat(250) + "\n下一段"))
        val original = aiSpeechChunks(source).toList()
        val planned = AiSpeechRolePlanner("book", emptyList()).plan(source).toList()
        assertEquals(original, planned.map { it.chunk })
        assertTrue(planned.all { it.roleInstructions.isEmpty() && it.roleId == null })
    }

    @Test fun legacyRoleJsonDefaultsEnabledCharacterAndCommonDefaultsHaveStableIds() {
        val restored = BookVoiceRole.fromJson(JSONObject(zhang.toJson().toString()).apply { remove("enabled"); remove("kind") })
        assertTrue(restored.enabled)
        assertEquals(VoiceRoleKind.CHARACTER, restored.kind)
        assertEquals(defaultCommonVoiceRoles(), defaultCommonVoiceRoles())
        assertTrue(defaultCommonVoiceRoles().all { it.enabled && it.kind == VoiceRoleKind.NARRATOR })
        assertEquals(listOf("男人", "女人"), defaultCommonVoiceRoles().map { it.name })
    }
}
