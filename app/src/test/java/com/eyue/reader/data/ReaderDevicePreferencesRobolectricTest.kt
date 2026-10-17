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
class ReaderDevicePreferencesRobolectricTest {
    @Test fun pageStyleIsIndependentPerModeAndDevicePreferencesSurviveRepositoryRestart() {
        val base = RuntimeEnvironment.getApplication()
        val root = File(base.cacheDir, "reader-devices-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir() = root }
        try {
            val state = LibraryState(settings = ReaderSettings(pageTurnStyle = PageTurnStyle.CURL),
                comicSettings = ReaderSettings(pageTurnStyle = PageTurnStyle.COVER), einkMode = true, tabletDualPage = true)
            LibraryRepository(context).save(state)
            val restored = LibraryRepository(context).load()
            assertEquals(state, restored)
            assertEquals(PageTurnStyle.CURL, restored.settings.pageTurnStyle)
            assertEquals(PageTurnStyle.COVER, restored.comicSettings.pageTurnStyle)
            assertTrue(restored.einkMode)
            assertTrue(restored.tabletDualPage)
        } finally { root.deleteRecursively() }
    }

    @Test fun oldOrUnknownStyleUsesCompatibleDefault() {
        val repository = LibraryRepository(RuntimeEnvironment.getApplication())
        assertEquals(PageTurnStyle.SLIDE, repository.readSettings(JSONObject()).pageTurnStyle)
        assertEquals(PageTurnStyle.SLIDE, repository.readSettings(JSONObject().put("pageTurnStyle", "UNKNOWN")).pageTurnStyle)
        assertEquals(PageTurnStyle.COVER, repository.readSettings(repository.writeSettings(
            ReaderSettings(pageTurnStyle = PageTurnStyle.COVER))).pageTurnStyle)
    }

    @Test fun resettingOneReadingModeDoesNotResetDeviceOptionsOrTheOtherMode() {
        val state = LibraryState(settings = ReaderSettings(pageTurnStyle = PageTurnStyle.CURL),
            comicSettings = ReaderSettings(pageTurnStyle = PageTurnStyle.COVER), einkMode = true, tabletDualPage = true)
        val reset = state.resetReaderPreferences(LibraryMode.NOVEL)
        assertEquals(PageTurnStyle.SLIDE, reset.settings.pageTurnStyle)
        assertEquals(PageTurnStyle.COVER, reset.comicSettings.pageTurnStyle)
        assertTrue(reset.einkMode)
        assertTrue(reset.tabletDualPage)
    }
}
