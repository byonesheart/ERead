package com.eyue.reader.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ReaderSourceCssSettingsRobolectricTest {
    @Test fun verticalTextIsOptionalPersistsInProfilesAndResetsOnlyTheSelectedLibraryMode() {
        val repository = LibraryRepository(RuntimeEnvironment.getApplication())
        assertFalse(repository.readSettings(JSONObject()).verticalText)
        val novel = ReaderSettings(verticalText = true).saveAppearanceProfile()
        val saved = repository.readSettings(repository.writeSettings(novel))
        assertTrue(saved.verticalText)
        assertTrue(saved.copy(verticalText = false).applyAppearanceProfile(saved.appearanceProfiles.single()).verticalText)
        val comic = ReaderSettings(verticalText = true)
        val reset = LibraryState(settings = saved, comicSettings = comic).resetReaderPreferences(LibraryMode.NOVEL)
        assertFalse(reset.settings.verticalText)
        assertSame(comic, reset.comicSettings)
    }
    @Test fun continuousScrollIsTheDefaultAndCanPersistTheOriginalChapterModeIndependently() {
        val repository = LibraryRepository(RuntimeEnvironment.getApplication())
        assertTrue(repository.readSettings(JSONObject()).continuousEbookScroll)
        val original = ReaderSettings(continuousEbookScroll = false, useSourceCss = true)
        val saved = repository.readSettings(repository.writeSettings(original))
        assertFalse(saved.continuousEbookScroll)
        assertTrue(saved.useSourceCss)
        val reset = LibraryState(settings = saved).resetReaderPreferences(LibraryMode.NOVEL)
        assertTrue(reset.settings.continuousEbookScroll)
    }
    @Test fun sourceCssDefaultsOffPersistsIndependentlyAndIsNotChangedByAppearanceProfiles() {
        val repository = LibraryRepository(RuntimeEnvironment.getApplication())
        assertFalse(repository.readSettings(JSONObject()).useSourceCss)
        val novel = repository.readSettings(repository.writeSettings(ReaderSettings(useSourceCss = true)))
        val comic = repository.readSettings(repository.writeSettings(ReaderSettings(useSourceCss = false)))
        assertTrue(novel.useSourceCss)
        assertFalse(comic.useSourceCss)
        val profile = novel.saveAppearanceProfile().appearanceProfiles.single()
        assertTrue(novel.applyAppearanceProfile(profile).useSourceCss)
        val reset = LibraryState(settings = novel, comicSettings = comic).resetReaderPreferences(LibraryMode.NOVEL)
        assertFalse(reset.settings.useSourceCss)
        assertSame(comic, reset.comicSettings)
    }

    @Test fun chapterCssProjectionAndSpacingFieldsRoundTripWithoutChangingLegacyStyles() {
        val repository = LibraryRepository(RuntimeEnvironment.getApplication())
        val legacy = SourceTextStyle(0, 2, bold = true)
        val css = SourceTextStyle(0, 4, paragraph = true, lineHeightEm = 1.2f, letterSpacingEm = .15f,
            spaceBeforeEm = .25f, spaceAfterEm = .5f)
        val chapter = Chapter(title = "正文", sourceStyles = listOf(legacy), sourceCssStyles = listOf(css))
        assertEquals(chapter, repository.readChapter(repository.writeChapter(chapter)))
        assertEquals(listOf(legacy), chapter.readerSourceStyles(false))
        assertEquals(listOf(css), chapter.readerSourceStyles(true))
        val old = repository.writeChapter(chapter).apply { remove("sourceCssStyles") }
        assertEquals(listOf(legacy), repository.readChapter(old).readerSourceStyles(true))
    }
}
