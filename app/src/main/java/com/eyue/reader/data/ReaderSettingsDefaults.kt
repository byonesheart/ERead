package com.eyue.reader.data

/** Reset reading preferences while retaining imported fonts and saved table-of-contents rules. */
internal fun LibraryState.resetReaderPreferences(mode: LibraryMode): LibraryState {
    val current = if (mode == LibraryMode.COMIC) comicSettings else settings
    val defaults = ReaderSettings(nightMode = settings.nightMode, fonts = current.fonts,
        chapterRule = current.chapterRule, chapterRules = current.chapterRules, activeChapterRuleId = current.activeChapterRuleId,
        appearanceProfiles = current.appearanceProfiles, activeAppearanceProfileId = current.activeAppearanceProfileId)
        .autosaveAppearanceProfile()
    return if (mode == LibraryMode.COMIC) copy(comicSettings = defaults) else copy(settings = defaults)
}
