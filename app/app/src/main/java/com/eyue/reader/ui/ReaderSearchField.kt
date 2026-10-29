package com.eyue.reader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp

@Composable
internal fun ReaderSearchField(value: String, onValue: (String) -> Unit, placeholder: String,
    enabled: Boolean, modifier: Modifier = Modifier, search: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    BasicTextField(value, onValue, modifier.fillMaxWidth(), singleLine = true, enabled = enabled,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface), cursorBrush = SolidColor(colors.primary),
        decorationBox = { input ->
            Surface(shape = appShape(8.dp), border = BorderStroke(.8.dp, colors.outline), color = colors.surface) {
                Row(Modifier.heightIn(min = 42.dp).padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (search) AppIcon(Icons.Outlined.Search, null, Modifier.size(18.dp))
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        input()
                    }
                    if (value.isNotEmpty()) IconButton(onClick = { onValue("") }, enabled = enabled, modifier = Modifier.size(28.dp)) {
                        AppIcon(Icons.Outlined.Close, if (search) "清空" else "清空替换文字", Modifier.size(16.dp))
                    }
                }
            }
        })
}
