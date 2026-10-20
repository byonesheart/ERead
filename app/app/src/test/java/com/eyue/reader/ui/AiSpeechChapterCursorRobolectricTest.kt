package com.eyue.reader.ui

import com.eyue.reader.data.AiSpeechRolePlanner
import com.eyue.reader.data.BookVoiceRole
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AiSpeechChapterCursorRobolectricTest {
    @Test fun lateStartUsesCurrentChapterContextAndCarriesDialogueIntoTheNextChapter() = runBlocking {
        val role = BookVoiceRole("张三", "张三", "低沉男声", id = "zhang")
        val text = "张三说：“你好。”\n“后面这句。”"
        val offset = text.indexOf('\n') + 1
        val loaded = mutableListOf<Int>()
        val cursor = AiSpeechChapterCursor(ReaderSpeechSource(98, 100,
            listOf(ReaderSpeechSegment(98, 0, offset, text.substring(offset), text))) { chapter ->
            loaded += chapter
            listOf(ReaderSpeechSegment(chapter, 0, 0, "“换章后仍然是我。”"))
        }, AiSpeechRolePlanner("book", listOf(role)))
        val current = requireNotNull(cursor.next())
        assertEquals("zhang", current.planned.roleId)
        assertTrue(current.chapterEnd)
        assertTrue(loaded.isEmpty())
        val next = requireNotNull(cursor.next())
        assertEquals(listOf(99), loaded)
        assertEquals("zhang", next.seedRoleId)
        assertEquals(current.planned.roleInstructions, next.planned.roleInstructions)
        assertNull(cursor.next())
    }

    @Test fun aKnownSeedKeepsUnattributedDialogueStableWithoutLoadingAnyPreviousChapter() = runBlocking {
        val role = BookVoiceRole("张三", "张三", "低沉男声", id = "zhang")
        val cursor = AiSpeechChapterCursor(ReaderSpeechSource(98, 99,
            listOf(ReaderSpeechSegment(98, 0, 0, "“继续。”"))) { error("Unexpected chapter read") },
            AiSpeechRolePlanner("book", listOf(role)), "zhang")
        val current = requireNotNull(cursor.next())
        assertEquals("zhang", current.planned.roleId)
        assertEquals("zhang", current.seedRoleId)
        assertNull(cursor.next())
    }

    @Test fun completeBlankChaptersAreIndexedButATrimmedNonemptyChapterIsNot() = runBlocking {
        val empty = mutableListOf<Int>()
        val text = "Already spoken"
        val cursor = AiSpeechChapterCursor(ReaderSpeechSource(0, 3,
            listOf(ReaderSpeechSegment(0, 0, text.length, "", text))) { chapter ->
            listOf(ReaderSpeechSegment(chapter, 0, 0, if (chapter == 1) "  \n" else "Next"))
        }, null, onChapterPlanned = { chapter, _, _, blank -> if (blank) empty += chapter })
        assertEquals(2, requireNotNull(cursor.next()).planned.chunk.segment.chapter)
        assertEquals(listOf(1), empty)
        assertNull(cursor.next())
    }
}
