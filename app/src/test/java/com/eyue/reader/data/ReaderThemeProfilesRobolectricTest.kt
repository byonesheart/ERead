package com.eyue.reader.data

import android.content.ContextWrapper
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ReaderThemeProfilesRobolectricTest {
    @Test fun novelAndComicThemesPersistIndependentlyWithTheirActiveSelectionAndConversion() {
        val base = RuntimeEnvironment.getApplication()
        val root = File(base.cacheDir, "reader-themes-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir() = root }
        try {
            val novel = ReaderSettings(fontSize = 23f, chineseConversion = ChineseConversion.TRADITIONAL,
                background = ReaderBackground.IMAGE, backgroundImagePath = "/novel.png").saveAppearanceProfile()
            val comic = ReaderSettings(brightness = .4f, background = ReaderBackground.GREEN).saveAppearanceProfile()
            val state = LibraryState(settings = novel, comicSettings = comic)
            LibraryRepository(context).save(state)
            assertEquals(state, LibraryRepository(context).load())
            assertNotEquals(novel.appearanceProfiles.first().id, comic.appearanceProfiles.first().id)
            assertEquals("配置1", novel.appearanceProfiles.first().name)
            assertEquals("配置1", comic.appearanceProfiles.first().name)
        } finally { root.deleteRecursively() }
    }

    @Test fun applyingAnAppearanceNeverRestoresSpeechNavigationRulesOrLanguage() {
        val first = ReaderSettings(fontSize = 30f, fontName = "自选", fontPath = "/font.otf", bold = true,
            lineHeight = 2.2f, paragraphSpacing = 1.4f, letterSpacing = 1.1f,
            background = ReaderBackground.IMAGE, backgroundImagePath = "/background.png", brightness = .7f).saveAppearanceProfile()
        val current = ReaderSettings(ttsSpeed = 1.6f, ttsFollowPage = true, readingMode = ReadingMode.VERTICAL_PAGED,
            pageTurnStyle = PageTurnStyle.COVER, volumePaging = true, keepScreenOn = false, nightMode = true,
            chineseConversion = ChineseConversion.SIMPLIFIED, chapterRule = "custom", appearanceProfiles = first.appearanceProfiles)
        val applied = current.applyAppearanceProfile(first.appearanceProfiles.single())
        assertEquals(first.appearanceSnapshot(), applied.appearanceSnapshot())
        assertEquals(current.copy(fontSize = applied.fontSize, lineHeight = applied.lineHeight, paragraphSpacing = applied.paragraphSpacing,
            letterSpacing = applied.letterSpacing, fontName = applied.fontName, fontPath = applied.fontPath, bold = applied.bold,
            background = applied.background, backgroundImagePath = applied.backgroundImagePath, brightness = applied.brightness,
            showTime = applied.showTime, showBattery = applied.showBattery, showChapter = applied.showChapter,
            activeAppearanceProfileId = first.activeAppearanceProfileId), applied)
    }

    @Test fun resettingAppearanceKeepsSavedProfilesAndImportResourcesButClearsTheActiveTheme() {
        val settings = ReaderSettings(fontSize = 30f, fonts = listOf(ImportedFont("字体", "/font.otf"))).saveAppearanceProfile()
        val state = LibraryState(settings = settings, comicSettings = ReaderSettings(brightness = .2f))
        val reset = state.resetReaderPreferences(LibraryMode.NOVEL)
        assertEquals(settings.appearanceProfiles, reset.settings.appearanceProfiles)
        assertNull(reset.settings.activeAppearanceProfileId)
        assertEquals(ReaderSettings().fontSize, reset.settings.fontSize)
        assertEquals(settings.fonts, reset.settings.fonts)
        assertSame(state.comicSettings, reset.comicSettings)
    }

    @Test fun themePathsCanBeRemappedWithoutChangingTheirAppearanceOrOtherSettings() {
        val saved = ReaderSettings(fontPath = "/font.otf", backgroundImagePath = "/background.png").saveAppearanceProfile()
        val original = saved.appearanceProfiles.single()
        val portable = original.mapPaths { it?.let { value -> "assets/" + File(value).name } }
        assertEquals("assets/font.otf", portable.appearance.fontPath)
        assertEquals("assets/background.png", portable.appearance.backgroundImagePath)
        assertEquals(original.copy(appearance = original.appearance.copy(fontPath = portable.appearance.fontPath,
            backgroundImagePath = portable.appearance.backgroundImagePath)), portable)
    }

    @Test fun oldSettingsWithoutThemesOrWithAnUnknownConversionUseCompatibleDefaults() {
        val repository = LibraryRepository(RuntimeEnvironment.getApplication())
        val restored = repository.readSettings(JSONObject().put("chineseConversion", "UNKNOWN"))
        assertTrue(restored.appearanceProfiles.isEmpty())
        assertNull(restored.activeAppearanceProfileId)
        assertEquals(ChineseConversion.ORIGINAL, restored.chineseConversion)
    }

    @Test fun savingAnEditedThemeUpdatesOnlyThatProfileAndKeepsItsIdentityAndName() {
        val first = ReaderSettings(fontSize = 20f).saveAppearanceProfile()
        val both = first.copy(fontSize = 24f).saveAppearanceProfile()
        val profile = both.appearanceProfiles.first()
        val edited = both.copy(fontSize = 30f, brightness = .6f).saveAppearanceProfile(profile.id)
        assertEquals(2, edited.appearanceProfiles.size)
        assertEquals(profile.id, edited.activeAppearanceProfileId)
        assertEquals(profile.name, edited.appearanceProfiles.first().name)
        assertEquals(30f, edited.appearanceProfiles.first().appearance.fontSize)
        assertEquals(.6f, edited.appearanceProfiles.first().appearance.brightness)
        assertEquals(both.appearanceProfiles.last(), edited.appearanceProfiles.last())
        assertSame(edited, edited.saveAppearanceProfile("deleted-profile"))
    }

    @Test fun deletingTheActiveThemeKeepsItsCurrentAppearanceAndLeavesOtherProfilesUntouched() {
        val first = ReaderSettings(fontSize = 20f).saveAppearanceProfile()
        val both = first.copy(fontSize = 24f).saveAppearanceProfile()
        val removed = both.removeAppearanceProfile(both.activeAppearanceProfileId!!)
        assertNull(removed.activeAppearanceProfileId)
        assertEquals(both.appearanceSnapshot(), removed.appearanceSnapshot())
        assertEquals(listOf(first.appearanceProfiles.single()), removed.appearanceProfiles)
        val removedInactive = both.removeAppearanceProfile(first.activeAppearanceProfileId!!)
        assertEquals(both.activeAppearanceProfileId, removedInactive.activeAppearanceProfileId)
    }
}
