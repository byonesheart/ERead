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
class ReaderPagingSettingsRobolectricTest {
    @Test fun clickPagingAndPdfCropPersistWithoutAddingDefaultFieldsToOldBackupFingerprints() {
        val repository = LibraryRepository(RuntimeEnvironment.getApplication())
        val defaults = repository.writeSettings(ReaderSettings())
        assertFalse(defaults.has("tapPaging")); assertFalse(defaults.has("pdfCrop"))
        val saved = ReaderSettings(tapPaging = TapPagingMode.VERTICAL, pdfCrop = PdfCrop(true, .1f, .2f, .15f, .05f))
        assertEquals(saved, repository.readSettings(repository.writeSettings(saved)))
        val malformed = repository.readSettings(JSONObject().put("tapPaging", "invalid").put("pdfCrop",
            JSONObject().put("enabled", true).put("left", -1).put("right", 50).put("top", "NaN")))
        assertEquals(TapPagingMode.OFF, malformed.tapPaging)
        assertEquals(PdfCrop(true, 0f, .4f, 0f, 0f), malformed.pdfCrop)
    }
}
