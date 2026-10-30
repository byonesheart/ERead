package com.eyue.reader.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReaderInterfaceProfilesRobolectricTest {
    @Test fun profilesPersistPagingIndentAndCropAndLeaveGlobalPreferencesAlone() {
        val first = ReaderSettings(readingMode = ReadingMode.PAGED, pageTurnStyle = PageTurnStyle.CURL,
            tapPaging = TapPagingMode.HORIZONTAL, paragraphIndent = 3f, hideStatusBar = true,
            pdfCrop = PdfCrop(left = .1f)).saveAppearanceProfile()
        val second = first.copy(readingMode = ReadingMode.SCROLL, continuousEbookScroll = false,
            tapPaging = TapPagingMode.VERTICAL, paragraphIndent = 0f).saveAppearanceProfile()
        val repository = LibraryRepository(RuntimeEnvironment.getApplication())
        val restored = repository.readSettings(repository.writeSettings(second))
        assertEquals(second, restored)
        val applied = restored.applyAppearanceProfile(restored.appearanceProfiles.first())
        assertEquals(ReadingMode.PAGED, applied.readingMode); assertEquals(PageTurnStyle.CURL, applied.pageTurnStyle)
        assertEquals(TapPagingMode.HORIZONTAL, applied.tapPaging); assertEquals(3f, applied.paragraphIndent)
        assertTrue(applied.hideStatusBar); assertEquals(.1f, applied.pdfCrop.left)
    }

    @Test fun interfaceResetOnlyTouchesTheSelectedInterfaceAndLeavesSpeechRulesStatusAndOtherProfiles() {
        val saved = ReaderSettings(fontSize = 30f, nightMode = true, hideStatusBar = true, showTime = false,
            ttsSpeed = 1.7f, ttsFollowPage = true, volumePaging = true, chapterRule = "custom").saveAppearanceProfile()
        val both = saved.copy(fontSize = 35f).saveAppearanceProfile()
        val reset = both.resetInterface()
        assertEquals(both.activeAppearanceProfileId, reset.activeAppearanceProfileId)
        assertEquals(saved.appearanceProfiles.first(), reset.appearanceProfiles.first())
        assertEquals(ReaderSettings().fontSize, reset.fontSize)
        assertTrue(reset.nightMode); assertTrue(reset.hideStatusBar); assertFalse(reset.showTime)
        assertEquals(1.7f, reset.ttsSpeed); assertTrue(reset.ttsFollowPage); assertTrue(reset.volumePaging)
        assertEquals("custom", reset.chapterRule)
        assertEquals(0f, both.resetInterface(true).marginLeft)
    }

    @Test fun olderProfilesInheritSavedPagingAndDisabledCropMigratesWithoutLosingMargins() {
        val repository = LibraryRepository(RuntimeEnvironment.getApplication())
        val json = JSONObject("""{"readingMode":"PAGED","pageTurnStyle":"COVER","tapPaging":"HORIZONTAL",
            "appearanceProfiles":[{"id":"old","name":"配置1","appearance":{"fontSize":21}}],
            "pdfCrop":{"enabled":false,"left":0.15}}""")
        val settings = repository.readSettings(json)
        val selected = settings.applyAppearanceProfile(settings.appearanceProfiles.single())
        assertEquals(ReadingMode.PAGED, selected.readingMode); assertEquals(PageTurnStyle.COVER, selected.pageTurnStyle)
        assertEquals(TapPagingMode.HORIZONTAL, selected.tapPaging)
        assertTrue(settings.pdfCrop.enabled); assertEquals(.15f, settings.pdfCrop.left)
        assertTrue(ReaderSettings().pdfCrop.enabled); assertEquals(0f, ReaderSettings().pdfCrop.left)
    }
}
