package com.eyue.reader.data

import android.content.ContextWrapper
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ReaderMarginsPersistenceRobolectricTest {
    @get:Rule val files = TemporaryFolder()
    private fun repository() = LibraryRepository(object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir() = files.root
    })

    @Test fun oldNovelAndComicSettingsAndThemesRetainTheirOriginalPageMargins() {
        val repository = repository()
        val legacy = repository.writeSettings(ReaderSettings(fontSize = 23f).saveAppearanceProfile())
        val keys = listOf("marginLeft", "marginRight", "marginTop", "marginBottom")
        keys.forEach { legacy.remove(it) }
        val appearance = legacy.getJSONArray("appearanceProfiles").getJSONObject(0).getJSONObject("appearance")
        keys.forEach { appearance.remove(it) }
        val json = JSONObject().put("settings", legacy).put("comicSettings", legacy)
        File(files.root, "library.json").writeText(json.toString())
        val state = repository.load()
        assertEquals(24f, state.settings.marginLeft)
        assertEquals(18f, state.settings.marginTop)
        assertEquals(0f, state.comicSettings.marginLeft)
        assertEquals(0f, state.comicSettings.marginRight)
        assertEquals(0f, state.comicSettings.marginTop)
        assertEquals(0f, state.comicSettings.marginBottom)
        assertEquals(24f, state.settings.appearanceProfiles.single().appearance.marginRight)
        assertEquals(0f, state.comicSettings.appearanceProfiles.single().appearance.marginBottom)
        json.remove("comicSettings")
        File(files.root, "library.json").writeText(json.toString())
        assertEquals(0f, repository.load().comicSettings.appearanceProfiles.single().appearance.marginLeft)
        assertEquals(0f, LibraryState().comicSettings.marginTop)
    }

    @Test fun independentMarginsPersistInThemesAndResetOnlyTheSelectedReadingMode() {
        val novel = ReaderSettings(marginLeft = 7f, marginRight = 11f, marginTop = 13f, marginBottom = 17f).saveAppearanceProfile()
        val second = novel.copy(marginLeft = 33f).saveAppearanceProfile()
        val applied = second.applyAppearanceProfile(novel.appearanceProfiles.single())
        assertEquals(novel.appearanceSnapshot(), applied.appearanceSnapshot())
        val comic = ReaderSettings(marginLeft = 2f, marginRight = 4f, marginTop = 6f, marginBottom = 8f).saveAppearanceProfile()
        val state = LibraryState(settings = applied, comicSettings = comic)
        val repository = repository()
        repository.save(state)
        assertEquals(state, repository.load())
        val resetNovel = state.resetReaderPreferences(LibraryMode.NOVEL)
        assertEquals(24f, resetNovel.settings.marginLeft)
        assertEquals(18f, resetNovel.settings.marginBottom)
        assertSame(comic, resetNovel.comicSettings)
        val resetComic = state.resetReaderPreferences(LibraryMode.COMIC)
        assertEquals(0f, resetComic.comicSettings.marginLeft)
        assertEquals(0f, resetComic.comicSettings.marginBottom)
        assertSame(applied, resetComic.settings)
        assertEquals(0f, resetComic.comicSettings.appearanceProfiles.single().appearance.marginTop)
    }

    @Test fun invalidMarginsAreBoundedWithoutChangingOtherSettings() {
        val restored = repository().readSettings(JSONObject().put("fontSize", 27f)
            .put("marginLeft", -5).put("marginRight", 100).put("marginTop", "NaN").put("marginBottom", "Infinity"))
        assertEquals(0f, restored.marginLeft)
        assertEquals(64f, restored.marginRight)
        assertEquals(18f, restored.marginTop)
        assertEquals(18f, restored.marginBottom)
        assertEquals(27f, restored.fontSize)
    }
}
