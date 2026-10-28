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
class AutoReadingSettingsRobolectricTest {
    private val repository get() = LibraryRepository(RuntimeEnvironment.getApplication())

    @Test fun speedsRoundTripIndependentlyAndStayOutsideAppearanceProfiles() {
        val novel = ReaderSettings(autoScrollSpeed = 60f, autoPageSeconds = 7f)
        val comic = ReaderSettings(autoScrollSpeed = 120f, autoPageSeconds = 15f)
        for (settings in listOf(novel, comic)) {
            val result = repository.readSettings(repository.writeSettings(settings))
            assertEquals(settings.autoScrollSpeed, result.autoScrollSpeed)
            assertEquals(settings.autoPageSeconds, result.autoPageSeconds)
            assertFalse(repository.writeSettings(settings).has("autoReading"))
        }
        assertEquals(novel.appearanceSnapshot(), comic.appearanceSnapshot())
    }

    @Test fun oldSettingsUseDefaultsAndInvalidSpeedsAreBounded() {
        assertEquals(ReaderSettings().autoScrollSpeed, repository.readSettings(JSONObject()).autoScrollSpeed)
        assertEquals(ReaderSettings().autoPageSeconds, repository.readSettings(JSONObject()).autoPageSeconds)
        val settings = repository.readSettings(JSONObject("""{"autoScrollSpeed":0,"autoPageSeconds":999}"""))
        assertEquals(8f, settings.autoScrollSpeed)
        assertEquals(120f, settings.autoPageSeconds)
        val invalid = repository.readSettings(JSONObject("""{"autoScrollSpeed":"NaN","autoPageSeconds":"NaN"}"""))
        assertEquals(ReaderSettings().autoScrollSpeed, invalid.autoScrollSpeed)
        assertEquals(ReaderSettings().autoPageSeconds, invalid.autoPageSeconds)
    }
}
