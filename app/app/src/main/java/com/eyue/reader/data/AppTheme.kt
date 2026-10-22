package com.eyue.reader.data

enum class ThemeMode { LIGHT, DARK, SYSTEM }
enum class AppThemeStyle { CLASSIC, PIXEL }

fun ThemeMode.isDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM -> systemDark
}

fun LibraryState.withResolvedTheme(systemDark: Boolean): LibraryState {
    val dark = themeMode.isDark(systemDark)
    return copy(settings = settings.copy(nightMode = dark), comicSettings = comicSettings.copy(nightMode = dark))
}
