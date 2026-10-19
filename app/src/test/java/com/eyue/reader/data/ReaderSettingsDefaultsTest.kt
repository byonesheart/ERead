package com.eyue.reader.data

import org.junit.Assert.*
import org.junit.Test

class ReaderSettingsDefaultsTest {
    @Test fun novelResetPreservesThemeResourcesRulesAndComicPreferences() {
        val font = ImportedFont("导入字体", "font.ttf")
        val rule = ChapterRulePreset("custom", "卷规则", "^第.*卷$")
        val novel = ReaderSettings(fontSize = 31f, bold = true, readingMode = ReadingMode.PAGED,
            nightMode = true, fonts = listOf(font), fontPath = font.path, fontName = font.name,
            chapterRules = listOf(rule), activeChapterRuleId = rule.id, chapterRule = rule.pattern, hierarchicalContents = false)
        val comic = ReaderSettings(brightness = .3f, readingMode = ReadingMode.VERTICAL_PAGED, nightMode = true)
        val state = LibraryState(settings = novel, comicSettings = comic, themeMode = ThemeMode.SYSTEM)
        val result = state.resetReaderPreferences(LibraryMode.NOVEL)
        assertEquals(ReaderSettings().fontSize, result.settings.fontSize)
        assertFalse(result.settings.bold)
        assertNull(result.settings.fontPath)
        assertEquals(ReadingMode.SCROLL, result.settings.readingMode)
        assertTrue(result.settings.hierarchicalContents)
        assertEquals(novel.fonts, result.settings.fonts)
        assertEquals(novel.chapterRules, result.settings.chapterRules)
        assertEquals(novel.chapterRule, result.settings.chapterRule)
        assertEquals(novel.activeChapterRuleId, result.settings.activeChapterRuleId)
        assertTrue(result.settings.nightMode)
        assertEquals(ThemeMode.SYSTEM, result.themeMode)
        assertSame(comic, result.comicSettings)
    }

    @Test fun comicResetLeavesNovelPreferencesAndBookStateUntouched() {
        val novel = ReaderSettings(fontSize = 26f, ttsSpeed = 1.5f)
        val comic = ReaderSettings(background = ReaderBackground.IMAGE, backgroundImagePath = "image.png",
            readingMode = ReadingMode.VERTICAL_PAGED, brightness = .7f)
        val book = Book(title = "保留进度", format = BookFormat.TXT, mode = LibraryMode.NOVEL,
            chapters = emptyList(), characterOffset = 100, progress = .4f)
        val state = LibraryState(settings = novel, comicSettings = comic, books = listOf(book), readingSeconds = 300)
        val result = state.resetReaderPreferences(LibraryMode.COMIC)
        assertEquals(ReaderSettings(), result.comicSettings.copy(appearanceProfiles = emptyList(), activeAppearanceProfileId = null))
        val selected = result.comicSettings.appearanceProfiles.single { it.id == result.comicSettings.activeAppearanceProfileId }
        assertEquals(ReaderSettings().appearanceSnapshot(), selected.appearance)
        assertSame(novel, result.settings)
        assertSame(state.books, result.books)
        assertEquals(300L, result.readingSeconds)
    }
}
