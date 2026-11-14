package com.eyue.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.SettingsBrightness
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eyue.reader.data.ThemeMode
import com.eyue.reader.data.AppThemeStyle

@Composable internal fun SettingsEntryIcon(icon: ImageVector, tag: String) {
    val colorful = LocalAppThemeStyle.current == AppThemeStyle.XINGCAI && !LocalAppEinkMode.current
    val colors = MaterialTheme.colorScheme
    val color = if (!colorful) colors.primaryContainer else when (tag) {
        "settings_speech" -> Color(0xFF5FD0A0)
        "settings_cloud" -> Color(0xFF7FA9F0)
        "settings_cache", "settings_support" -> Color(0xFFFF8B7B)
        "settings_personalization", "settings_app_theme", "settings_feedback" -> Color(0xFFA78BFA)
        "settings_about", "settings_eink_mode" -> Color(0xFFFFC24B)
        else -> MaterialTheme.colorScheme.primary
    }
    Box(Modifier.size(30.dp).background(color, appShape(9.dp)),
        contentAlignment = Alignment.Center) {
        AppIcon(icon, null, Modifier.size(18.dp),
            tint = if (colorful) colors.surface else colors.onPrimaryContainer)
    }
}

@Composable internal fun SettingsNightModeSegment(value: ThemeMode, onChange: (ThemeMode) -> Unit) {
    SettingsIconSegment(ThemeMode.entries, value, onChange, { it.settingsLabel },
        { "settings_theme_${it.name.lowercase()}" }) {
        when (it) {
            ThemeMode.LIGHT -> Icons.Outlined.LightMode
            ThemeMode.DARK -> Icons.Outlined.DarkMode
            ThemeMode.SYSTEM -> Icons.Outlined.SettingsBrightness
        }
    }
}

internal val ThemeMode.settingsLabel: String get() = when (this) {
    ThemeMode.LIGHT -> "浅色"
    ThemeMode.DARK -> "深色"
    ThemeMode.SYSTEM -> "系统"
}

@Composable internal fun <T> SettingsIconSegment(options: List<T>, value: T, onChange: (T) -> Unit,
    label: (T) -> String, tag: (T) -> String, icon: (T) -> ImageVector) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(appShape(10.dp))
        .background(colors.primaryContainer).padding(3.dp).selectableGroup()) {
        options.forEach { option ->
            val selected = value == option
            val tint = if (selected) colors.primary else colors.onPrimaryContainer
            Row(Modifier.weight(1f).heightIn(min = 40.dp).clip(appShape(8.dp))
                .background(if (selected) colors.surface else Color.Transparent)
                .selectable(selected, role = Role.RadioButton, onClick = { onChange(option) })
                .testTag(tag(option)), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically) {
                AppIcon(icon(option), null, Modifier.size(16.dp), tint = tint)
                Text(label(option), fontSize = 12.sp, maxLines = 1, color = tint,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
            }
        }
    }
}
