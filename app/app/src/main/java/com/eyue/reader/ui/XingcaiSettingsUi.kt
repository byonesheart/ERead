package com.eyue.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
        "settings_app_theme", "settings_feedback" -> Color(0xFFA78BFA)
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
    val colors = MaterialTheme.colorScheme
    Row(Modifier.width(168.dp).heightIn(min = 32.dp).clip(appShape(10.dp))
        .background(colors.primaryContainer).padding(3.dp).selectableGroup()) {
        listOf(ThemeMode.LIGHT to "浅色", ThemeMode.DARK to "深色", ThemeMode.SYSTEM to "系统").forEach { (mode, label) ->
            val selected = value == mode
            Box(Modifier.weight(1f).heightIn(min = 26.dp).clip(appShape(8.dp))
                .background(if (selected) colors.surface else Color.Transparent)
                .selectable(selected, role = Role.RadioButton, onClick = { onChange(mode) })
                .testTag("settings_theme_${mode.name.lowercase()}"), contentAlignment = Alignment.Center) {
                Text(label, fontSize = 12.sp, maxLines = 1,
                    color = if (selected) colors.primary else colors.onSurfaceVariant,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
            }
        }
    }
}
