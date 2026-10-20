package com.eyue.reader.data

import android.content.ContextWrapper
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ThemeModePersistenceRobolectricTest {
    private lateinit var directory: File
    private lateinit var repository: LibraryRepository

    @Before fun setUp() {
        val base = RuntimeEnvironment.getApplication()
        directory = File(base.cacheDir, "theme-mode-${UUID.randomUUID()}").apply { mkdirs() }
        repository = LibraryRepository(object : ContextWrapper(base) { override fun getFilesDir() = directory })
    }

    @After fun tearDown() { directory.deleteRecursively() }

    @Test fun oldNightModeMigratesToAnExplicitThemeAndKeepsNovelAndComicTypography() {
        for (night in listOf(false, true)) {
            File(directory, "library.json").writeText(JSONObject().put("version", 10)
                .put("settings", JSONObject().put("nightMode", night).put("fontSize", 23).put("lineHeight", 1.3))
                .put("comicSettings", JSONObject().put("nightMode", !night).put("fontSize", 17).put("lineHeight", 2.1)).toString())
            val state = repository.load()
            assertEquals(if (night) ThemeMode.DARK else ThemeMode.LIGHT, state.themeMode)
            val resolved = state.withResolvedTheme(systemDark = !night)
            assertEquals(night, resolved.settings.nightMode)
            assertEquals(night, resolved.comicSettings.nightMode)
            assertEquals(23f, resolved.settings.fontSize, 0f)
            assertEquals(17f, resolved.comicSettings.fontSize, 0f)
            assertEquals(1.3f, resolved.settings.lineHeight, 0f)
            assertEquals(2.1f, resolved.comicSettings.lineHeight, 0f)
        }
    }

    @Test fun legacyDarkBackgroundRemainsAnExplicitDarkTheme() {
        File(directory, "library.json").writeText("""{"version":1,"settings":{"background":"DARK"}}""")
        val state = repository.load().withResolvedTheme(systemDark = false)
        assertEquals(ThemeMode.DARK, state.themeMode)
        assertTrue(state.settings.nightMode)
        assertTrue(state.comicSettings.nightMode)
        assertEquals(ReaderBackground.PAPER, state.settings.background)
    }

    @Test fun systemThemeRoundTripsAndFollowsBothSystemModesWithoutChangingReaderPreferences() {
        val novel = ReaderSettings(fontSize = 23f, lineHeight = 1.3f, fontName = "系统黑体", bold = true,
            background = ReaderBackground.GREEN, letterSpacing = .6f, readingMode = ReadingMode.PAGED, nightMode = false)
        val comic = ReaderSettings(fontSize = 17f, lineHeight = 2.1f, fontName = "跟随系统", brightness = .37f,
            background = ReaderBackground.IMAGE, backgroundImagePath = "custom-background.png",
            readingMode = ReadingMode.VERTICAL_PAGED, nightMode = false)
        repository.save(LibraryState(settings = novel, comicSettings = comic, readingSeconds = 123,
            themeMode = ThemeMode.SYSTEM))
        assertEquals("SYSTEM", JSONObject(File(directory, "library.json").readText()).getString("themeMode"))
        val restored = repository.load()
        assertEquals(ThemeMode.SYSTEM, restored.themeMode)
        for (systemDark in listOf(true, false, true)) {
            val resolved = restored.withResolvedTheme(systemDark)
            assertEquals(ThemeMode.SYSTEM, resolved.themeMode)
            assertEquals(novel.copy(nightMode = systemDark), resolved.settings)
            assertEquals(comic.copy(nightMode = systemDark), resolved.comicSettings)
            assertEquals(123L, resolved.readingSeconds)
            repository.save(resolved)
            assertEquals(ThemeMode.SYSTEM, repository.load().themeMode)
        }
    }

    @Test fun explicitLightAndDarkIgnoreTheSystemAppearance() {
        for (systemDark in listOf(false, true)) {
            assertFalse(ThemeMode.LIGHT.isDark(systemDark))
            assertTrue(ThemeMode.DARK.isDark(systemDark))
            assertEquals(systemDark, ThemeMode.SYSTEM.isDark(systemDark))
            for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                val dark = mode == ThemeMode.DARK
                val initial = LibraryState(settings = ReaderSettings(nightMode = !dark, fontSize = 24f),
                    comicSettings = ReaderSettings(nightMode = !dark, fontSize = 16f), themeMode = mode)
                val resolved = initial.withResolvedTheme(systemDark)
                assertEquals(dark, resolved.settings.nightMode)
                assertEquals(dark, resolved.comicSettings.nightMode)
                assertEquals(24f, resolved.settings.fontSize, 0f)
                assertEquals(16f, resolved.comicSettings.fontSize, 0f)
            }
        }
    }

    @Test fun stateConstructorsPreserveTheExistingNightModeDefault() {
        assertEquals(ThemeMode.LIGHT, LibraryState().themeMode)
        assertEquals(ThemeMode.DARK, LibraryState(settings = ReaderSettings(nightMode = true)).themeMode)
    }

    @Test fun paragraphSpacingPersistsIndependentlyAndOldProfilesKeepTheirExistingGap() {
        val initial = LibraryState(settings = ReaderSettings(paragraphSpacing = .4f),
            comicSettings = ReaderSettings(paragraphSpacing = 1.4f))
        repository.save(initial)
        val loaded = repository.load()
        assertEquals(.4f, loaded.settings.paragraphSpacing, 0f)
        assertEquals(1.4f, loaded.comicSettings.paragraphSpacing, 0f)
        assertEquals(.8f, repository.readSettings(JSONObject()).paragraphSpacing, 0f)
    }
}
