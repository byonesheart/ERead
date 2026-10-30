package com.eyue.reader.ui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class EbookReaderControllerRobolectricTest {
    @Test fun retiredModeCannotDisconnectOrConsumeTheNewModesChapterAndProgressJumps() {
        val controller = EbookReaderController()
        val old = Any(); val current = Any()
        controller.bind(0, owner = old) {}
        controller.loaded(owner = old)
        val commands = mutableListOf<JSONObject>()
        controller.bind(0, continuous = true, owner = current) { commands += it }
        controller.navigate(120, expectedChapterIndex = 1)
        controller.loaded(owner = old)
        controller.unbind(old)
        assertTrue(commands.isEmpty())
        controller.loaded(owner = current)
        assertEquals(1, commands.single().getInt("chapter"))
        commands.clear()
        controller.navigate(450, expectedChapterIndex = 2)
        assertEquals(450, commands.single().getInt("offset"))
        val paged = Any()
        controller.bind(2, owner = paged) { commands += it }
        controller.unbind(current)
        controller.loaded(owner = paged)
        commands.clear()
        controller.navigate(220, expectedChapterIndex = 2)
        assertEquals(220, commands.single().getInt("offset"))
    }
    @Test fun holdPagingCancelsTheCurrentNativeGestureWithoutQueuingAnotherPageTurn() {
        val controller = EbookReaderController()
        val commands = mutableListOf<JSONObject>()
        controller.bind(0) { commands += it }
        controller.cancelTouch()
        assertEquals("cancelTouch", commands.single().getString("type"))
        assertFalse(controller.consumePending())
        controller.unbind()
        val count = commands.size
        controller.cancelTouch()
        assertEquals(count, commands.size)
    }
    @Test fun continuousWindowNavigatesAcrossChaptersWithoutRebindingItsBrowser() {
        val controller = EbookReaderController()
        val commands = mutableListOf<JSONObject>()
        controller.bind(0, continuous = true) { commands += it }
        controller.loaded()
        commands.clear()
        controller.navigate(480, expectedChapterIndex = 3)
        assertEquals(3, commands.single().getInt("chapter"))
        assertEquals(480, commands.single().getInt("offset"))
        commands.clear()
        controller.checkpoint(720, expectedChapterIndex = 4)
        assertTrue(controller.consumePending())
        assertEquals(4, commands.single().getInt("chapter"))
        controller.move(1, expectedChapterIndex = 4)
        assertEquals("move", commands.last().getString("type"))
        controller.unbind()
        assertFalse(controller.consumePending())
    }
    @Test fun startupCanReadItsRestoreAnchorWithoutConsumingItBeforeTheBridgeIsReady() {
        val controller = EbookReaderController()
        val commands = mutableListOf<JSONObject>()
        controller.navigate(345, imagePath = "art/cover.png", lastPage = true, expectedChapterIndex = 2)
        controller.bind(2) { commands += it }
        assertNull(controller.initialNavigation(0))
        val initial = requireNotNull(controller.initialNavigation(2))
        assertEquals(345, initial.getInt("offset"))
        assertEquals("art/cover.png", initial.getString("image"))
        assertTrue(initial.getBoolean("lastPage"))
        assertTrue(commands.isEmpty())
        controller.loaded()
        assertEquals(initial.toString(), commands.single().toString())
        assertNull(controller.initialNavigation(2))
    }

    @Test fun reflowCheckpointIsConsumedOnceWithoutReplayingOnLaterPageCallbacks() {
        val controller = EbookReaderController()
        val commands = mutableListOf<JSONObject>()
        controller.bind(0) { commands += it }
        controller.loaded()
        commands.clear()
        controller.checkpoint(340, expectedChapterIndex = 0)
        assertTrue(commands.isEmpty())
        assertTrue(controller.consumePending())
        assertEquals(340, commands.single().getInt("offset"))
        assertFalse(controller.consumePending())
        controller.loaded()
        assertEquals(listOf("navigate", "report"), commands.map { it.getString("type") })
    }

    @Test fun oldResourceCannotConsumeTheNextChaptersJump() {
        val controller = EbookReaderController()
        val commands = mutableListOf<JSONObject>()
        controller.bind(0) { commands += it }
        controller.loaded()
        commands.clear()
        controller.navigate(420, expectedChapterIndex = 2)
        assertFalse(controller.consumePending())
        assertTrue(commands.isEmpty())
        controller.bind(2) { commands += it }
        controller.loaded()
        assertEquals(2, commands.single().getInt("chapter"))
        assertEquals(420, commands.single().getInt("offset"))
        assertFalse(controller.consumePending())
    }

    @Test fun anImmediatelyDispatchedJumpDoesNotRepeatWhenItsPageLoads() {
        val controller = EbookReaderController()
        val commands = mutableListOf<JSONObject>()
        controller.bind(0) { commands += it }
        controller.loaded()
        commands.clear()
        controller.navigate(88, expectedChapterIndex = 0)
        assertEquals(88, commands.single().getInt("offset"))
        assertFalse(controller.consumePending())
    }

    @Test fun htmlRestoredAnchorIsAcknowledgedWithoutNavigatingTwice() {
        val controller = EbookReaderController()
        val commands = mutableListOf<JSONObject>()
        controller.navigate(320, expectedChapterIndex = 1)
        controller.bind(1) { commands += it }
        controller.loaded(requireNotNull(controller.initialNavigation(1)))
        assertEquals(listOf("report"), commands.map { it.getString("type") })
        assertNull(controller.initialNavigation(1))
    }

    @Test fun aNewerJumpWhileHtmlLoadsIsPreserved() {
        val controller = EbookReaderController()
        val commands = mutableListOf<JSONObject>()
        controller.navigate(320, expectedChapterIndex = 1)
        controller.bind(1) { commands += it }
        val initial = requireNotNull(controller.initialNavigation(1))
        controller.navigate(720, expectedChapterIndex = 1)
        controller.loaded(initial)
        assertEquals(720, commands.single().getInt("offset"))
    }
}
