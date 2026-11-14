package com.eyue.reader.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.eyue.reader.data.AppThemeStyle

@Composable
internal fun AppThemeSettingsPage(selected: AppThemeStyle, onSelect: (AppThemeStyle) -> Unit) {
    SettingsIconSegment(AppThemeStyle.entries, selected, onSelect, { it.displayName },
        { "app_theme_${it.name.lowercase()}" }, { it.settingsIcon })
}

internal val AppThemeStyle.settingsIcon: ImageVector
    get() = when (this) {
        AppThemeStyle.CLASSIC -> Icons.AutoMirrored.Outlined.MenuBook
        AppThemeStyle.PIXEL -> Icons.Outlined.GridView
        AppThemeStyle.XINGCAI -> Icons.Outlined.AutoAwesome
    }
