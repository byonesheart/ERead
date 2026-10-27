package com.eyue.reader.ui

import com.eyue.reader.ui.AppIcon as Icon

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.eyue.reader.data.AppThemeStyle

@Composable
internal fun AppThemeSettingsPage(selected: AppThemeStyle, onSelect: (AppThemeStyle) -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val eink = LocalAppEinkMode.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("软件主题用于整体界面，阅读内的背景和字体配置独立保留。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AppThemeStyle.entries.forEach { style ->
            val colors = appColors(style, dark, eink)
            val chosen = selected == style
            Surface(onClick = { onSelect(style) },
                modifier = Modifier.fillMaxWidth().testTag("app_theme_${style.name.lowercase()}")
                    .semantics { this.selected = chosen; role = Role.RadioButton },
                shape = if (style == AppThemeStyle.PIXEL) PixelAppShape() else RoundedCornerShape(20.dp),
                color = colors.background, contentColor = colors.onBackground,
                border = BorderStroke(if (chosen) 2.dp else 1.dp,
                    if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (style == AppThemeStyle.PIXEL) Canvas(Modifier.size(52.dp)) {
                        val p = size.minDimension / 12f
                        fun block(x: Int, y: Int, w: Int, h: Int) =
                            drawRect(colors.primary, Offset(x * p, y * p), Size(w * p, h * p))
                        block(1, 2, 4, 1); block(7, 2, 4, 1)
                        block(0, 3, 1, 7); block(11, 3, 1, 7)
                        block(1, 9, 4, 1); block(7, 9, 4, 1)
                        block(5, 3, 2, 8)
                        block(2, 4, 2, 1); block(8, 4, 2, 1)
                        block(2, 6, 2, 1); block(8, 6, 2, 1)
                    } else if (style == AppThemeStyle.XINGCAI) XingcaiNightModeGlyph(true, glyphSize = 52.dp)
                    else Icon(Icons.AutoMirrored.Outlined.MenuBook, null, tint = colors.primary, modifier = Modifier.size(52.dp))
                    Column(Modifier.weight(1f)) {
                        Text(style.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(style.description,
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    if (chosen) Icon(Icons.Outlined.CheckCircle, "当前主题", tint = colors.primary)
                }
            }
        }
    }
}
