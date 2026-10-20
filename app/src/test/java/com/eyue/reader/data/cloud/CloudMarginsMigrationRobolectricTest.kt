package com.eyue.reader.data.cloud

import com.eyue.reader.data.Book
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.Chapter
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.LibraryRepository
import com.eyue.reader.data.ReaderSettings
import com.eyue.reader.data.saveAppearanceProfile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CloudMarginsMigrationRobolectricTest {
    @Test fun restoringOldComicMetadataRetainsItsFullPageWhileNewMarginsRemainExplicit() {
        val context = RuntimeEnvironment.getApplication()
        val repository = LibraryRepository(context)
        val service = CloudBackupService(context)
        val read = CloudBackupService::class.java.getDeclaredMethod("readMetadata", JSONObject::class.java)
            .apply { isAccessible = true }
        fun restored(mode: LibraryMode, old: Boolean): ReaderSettings {
            val book = Book(title = "云端边距", format = BookFormat.TXT, mode = mode,
                chapters = listOf(Chapter(title = "第一章", textPath = "chapter.txt")))
            val settings = repository.writeSettings(ReaderSettings(marginLeft = 9f, marginRight = 15f,
                marginTop = 21f, marginBottom = 27f).saveAppearanceProfile())
            if (old) listOf("marginLeft", "marginRight", "marginTop", "marginBottom").forEach { key ->
                settings.remove(key)
                settings.getJSONArray("appearanceProfiles").getJSONObject(0).getJSONObject("appearance").remove(key)
            }
            val metadata = JSONObject().put("version", 1).put("book", repository.writeBook(book))
                .put("annotations", JSONArray()).put("settings", settings).put("backupAt", 1L)
            return (read.invoke(service, metadata) as CloudRestoredBook).settings
        }
        val comic = restored(LibraryMode.COMIC, old = true)
        assertEquals(0f, comic.marginLeft)
        assertEquals(0f, comic.marginBottom)
        assertEquals(0f, comic.appearanceProfiles.single().appearance.marginTop)
        val novel = restored(LibraryMode.NOVEL, old = true)
        assertEquals(24f, novel.marginRight)
        assertEquals(18f, novel.marginTop)
        val custom = restored(LibraryMode.COMIC, old = false)
        assertEquals(9f, custom.marginLeft)
        assertEquals(27f, custom.marginBottom)
        assertEquals(21f, custom.appearanceProfiles.single().appearance.marginTop)
    }
}
