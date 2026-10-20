package com.eyue.reader.data

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DirectoryRuleStorageRobolectricTest {
    private val repository get() = LibraryRepository(RuntimeEnvironment.getApplication())

    @Test fun oldCustomRuleBecomesASavedActivePreset() {
        val pattern = "^卷[0-9]+$"
        val settings = repository.readSettings(JSONObject().put("chapterRule", pattern))
        assertEquals(pattern, settings.chapterRule)
        assertEquals(2, settings.chapterRules.size)
        assertEquals(pattern, settings.chapterRules.single { it.id == settings.activeChapterRuleId }.pattern)
        assertEquals(DEFAULT_CHAPTER_RULE, settings.chapterRules.single { it.id == "default" }.pattern)
    }

    @Test fun multipleRulesPersistWithExactlyOneActiveRule() {
        val defaults = ReaderSettings().chapterRules.single()
        val other = ChapterRulePreset("volumes", "分卷", "^卷[0-9]+$")
        val settings = ReaderSettings(chapterRule = other.pattern, chapterRules = listOf(defaults, other), activeChapterRuleId = other.id)
        repository.save(LibraryState(settings = settings, cloudAutoBackupMetered = true,
            cloudIdentity = "https://example.org/dav/\nreader"))
        val loaded = repository.load()
        assertEquals(settings, loaded.settings)
        assertTrue(loaded.cloudAutoBackupMetered)
        assertEquals("https://example.org/dav/\nreader", loaded.cloudIdentity)
    }

    @Test fun existingSettingsCopyUsingOnlyChapterRuleStillMigratesCorrectly() {
        val pattern = "^章节[0-9]+$"
        val settings = repository.readSettings(repository.writeSettings(ReaderSettings(chapterRule = pattern)))
        assertEquals(pattern, settings.chapterRule)
        assertEquals(pattern, settings.chapterRules.single { it.id == settings.activeChapterRuleId }.pattern)
    }

    @Test fun oldLibraryUsesUnmeteredDefaultAndLegacyDefaultRuleIsUpgraded() {
        File(RuntimeEnvironment.getApplication().filesDir, "library.json").writeText(
            JSONObject().put("version", 8).put("settings", JSONObject().put("chapterRule", LEGACY_DEFAULT_CHAPTER_RULE)).toString())
        val loaded = repository.load()
        assertFalse(loaded.cloudAutoBackupMetered)
        assertNull(loaded.cloudIdentity)
        assertEquals(DEFAULT_CHAPTER_RULE, loaded.settings.chapterRule)
        assertEquals("default", loaded.settings.activeChapterRuleId)
    }
}
