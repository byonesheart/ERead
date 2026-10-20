package com.eyue.reader.data

/** Legacy comics used the full page, including themes saved before margins were configurable. */
internal fun ReaderSettings.withDefaultComicMargins(): ReaderSettings = copy(
    marginLeft = 0f, marginRight = 0f, marginTop = 0f, marginBottom = 0f,
    appearanceProfiles = appearanceProfiles.map { profile -> profile.copy(appearance = profile.appearance.copy(
        marginLeft = 0f, marginRight = 0f, marginTop = 0f, marginBottom = 0f)) })

/** Reset reading preferences while retaining imported fonts and saved table-of-contents rules. */
internal fun LibraryState.resetReaderPreferences(mode: LibraryMode): LibraryState {
    val current = if (mode == LibraryMode.COMIC) comicSettings else settings
    val defaults = ReaderSettings(nightMode = settings.nightMode, fonts = current.fonts,
        chapterRule = current.chapterRule, chapterRules = current.chapterRules, activeChapterRuleId = current.activeChapterRuleId,
        appearanceProfiles = current.appearanceProfiles, activeAppearanceProfileId = current.activeAppearanceProfileId)
        .let { if (mode == LibraryMode.COMIC) it.copy(marginLeft = 0f, marginRight = 0f, marginTop = 0f, marginBottom = 0f) else it }
        .autosaveAppearanceProfile()
    return if (mode == LibraryMode.COMIC) copy(comicSettings = defaults) else copy(settings = defaults)
}
