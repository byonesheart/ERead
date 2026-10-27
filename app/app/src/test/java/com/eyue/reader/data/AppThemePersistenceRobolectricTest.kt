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
class AppThemePersistenceRobolectricTest {
    private lateinit var directory: File
    private lateinit var repository: LibraryRepository

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        directory = File(context.cacheDir, "app-theme-${UUID.randomUUID()}").apply { mkdirs() }
        repository = LibraryRepository(object : ContextWrapper(context) { override fun getFilesDir() = directory })
    }

    @After fun cleanup() { directory.deleteRecursively() }

    @Test fun oldAndUnrecognizedAppThemesRetainTheExistingClassicAppearance() {
        for (value in listOf<String?>(null, "unknown-future-theme")) {
            val json = JSONObject().put("version", 13).put("themeMode", "DARK")
            if (value != null) json.put("appTheme", value)
            File(directory, "library.json").writeText(json.toString())
            val loaded = repository.load()
            assertEquals(AppThemeStyle.CLASSIC, loaded.appTheme)
            assertEquals(ThemeMode.DARK, loaded.themeMode)
            assertTrue(loaded.showRecentReading)
        }
    }

    @Test fun recentReadingDefaultsToEnabledAndPersistsExplicitChoicesWithoutRemovingHistory() {
        assertTrue(LibraryState().showRecentReading)
        val book = Book(title = "有阅读历史的书", format = BookFormat.TXT, mode = LibraryMode.NOVEL,
            chapters = listOf(Chapter(title = "第一章")), lastReadAt = 1234L, progress = .25f)
        for (enabled in listOf(false, true)) {
            repository.save(LibraryState(books = listOf(book), showRecentReading = enabled))
            val loaded = repository.load()
            assertEquals(enabled, loaded.showRecentReading)
            assertEquals(listOf(book), loaded.books)
        }
    }

    @Test fun appThemesPersistWithoutChangingReaderProfilesOrDarkModePreference() {
        val novel = ReaderSettings(fontSize = 23f, paragraphSpacing = .3f, background = ReaderBackground.GREEN)
        val comic = ReaderSettings(fontSize = 17f, brightness = .6f, background = ReaderBackground.IMAGE,
            backgroundImagePath = "comic-background.png", readingMode = ReadingMode.VERTICAL_PAGED)
        for (theme in listOf(AppThemeStyle.PIXEL, AppThemeStyle.XINGCAI)) {
            repository.save(LibraryState(settings = novel, comicSettings = comic, themeMode = ThemeMode.SYSTEM,
                appTheme = theme))
            val json = JSONObject(File(directory, "library.json").readText())
            assertEquals(13, json.getInt("version"))
            assertEquals(theme.name, json.getString("appTheme"))
            val loaded = repository.load()
            assertEquals(theme, loaded.appTheme)
            assertEquals(ThemeMode.SYSTEM, loaded.themeMode)
            assertEquals(novel, loaded.settings)
            assertEquals(comic, loaded.comicSettings)
            for (systemDark in listOf(false, true)) {
                val resolved = loaded.withResolvedTheme(systemDark)
                assertEquals(theme, resolved.appTheme)
                assertEquals(novel.copy(nightMode = systemDark), resolved.settings)
                assertEquals(comic.copy(nightMode = systemDark), resolved.comicSettings)
            }
        }
    }
}
