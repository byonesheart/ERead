package com.eyue.reader.ui

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import com.eyue.reader.data.ReadingMode
import org.junit.Assert.*
import org.junit.Test

class ReaderPageTurnTest {
    @Test fun onlyEnabledLandscapeTabletsUseTwoPages() {
        val tablet = IntSize(1280, 800)
        val density = Density(1f)
        assertTrue(readerUseDualPage(true, ReadingMode.PAGED, tablet, density))
        assertTrue(readerUseDualPage(true, ReadingMode.VERTICAL_PAGED, tablet, density))
        assertFalse(readerUseDualPage(false, ReadingMode.PAGED, tablet, density))
        assertFalse(readerUseDualPage(true, ReadingMode.SCROLL, tablet, density))
        assertFalse(readerUseDualPage(true, ReadingMode.PAGED, IntSize(800, 1280), density))
        assertFalse(readerUseDualPage(true, ReadingMode.PAGED, IntSize(2400, 1080), Density(3f)))
        assertFalse(readerUseDualPage(true, ReadingMode.PAGED, IntSize.Zero, density))
        assertEquals(IntSize(639, 800), readerPageViewport(tablet, true, density))
        assertEquals(tablet, readerPageViewport(tablet, false, density))
    }

    @Test fun spreadNavigationDoesNotSkipThePageAfterNarrationMovesToTheRightPane() {
        val counts = listOf(5, 4)
        assertEquals(ReaderPageLocation(0, 2), readerAdjacentPage(0, 0, counts, 1, true))
        assertEquals(ReaderPageLocation(0, 2), readerAdjacentPage(0, 1, counts, 1, true))
        assertEquals(ReaderPageLocation(0, 0), readerAdjacentPage(0, 3, counts, -1, true))
        assertEquals(ReaderPageLocation(0, 4), readerAdjacentPage(0, 2, counts, 1, true))
        assertEquals(ReaderPageLocation(1, 0), readerAdjacentPage(0, 4, counts, 1, true))
        assertEquals(ReaderPageLocation(0, 4), readerAdjacentPage(1, 0, counts, -1, true))
        assertNull(readerAdjacentPage(0, 0, counts, -1, true))
        assertNull(readerAdjacentPage(1, 3, counts, 1, true))
    }

    @Test fun singlePageAndUnknownPreviousChapterStillHaveStableTargets() {
        assertEquals(ReaderPageLocation(0, 2), readerAdjacentPage(0, 1, listOf(3), 1, false))
        assertEquals(ReaderPageLocation(0, 1), readerAdjacentPage(0, 2, listOf(3), -1, false))
        assertEquals(ReaderPageLocation(0, 0), readerAdjacentPage(1, 0, listOf(0, 2), -1, true))
        assertNull(readerAdjacentPage(0, 0, emptyList(), 1, true))
    }

    @Test fun spreadAnchorIsIndependentOfEarlierChapterPageCounts() {
        assertEquals(2, readerSpreadStart(3, true))
        assertEquals(3, readerSpreadStart(3, false))
        assertEquals(0, readerSpreadStart(-1, true))
    }

    @Test fun speechOnTheRightPageIsStillVisibleFromTheLeftPageAnchor() {
        assertTrue(readerPageIsVisible(1, 3, 1, 2, true))
        assertTrue(readerPageIsVisible(1, 2, 1, 3, true))
        assertFalse(readerPageIsVisible(1, 4, 1, 3, true))
        assertFalse(readerPageIsVisible(0, 3, 1, 2, true))
        assertFalse(readerPageIsVisible(1, 3, 1, 2, false))
    }

    @Test fun theLastTwoPageSpreadReachesOneHundredPercentWithoutChangingItsFocus() {
        val focusedPage = 2
        assertEquals(4, readerVisiblePageEnd(focusedPage, 4, true))
        assertEquals(1f, readerVisiblePageEnd(focusedPage, 4, true).toFloat() / 4)
        assertEquals(4, readerVisiblePageEnd(3, 4, true))
        assertEquals(5, readerVisiblePageEnd(4, 5, true))
        assertEquals(2, readerVisiblePageEnd(0, 4, true))
        assertEquals(3, readerVisiblePageEnd(focusedPage, 4, false))
        assertEquals(0, readerVisiblePageEnd(0, 0, true))
        assertEquals(2, focusedPage)
    }
}
