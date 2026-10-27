package com.eyue.reader.ui

// Settings geometry adapted from the supplied ebook UI, retaining ERead's settings and actions.
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable internal fun ReaderSettingsHeading(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun ReaderSettingSection(title: String, trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            trailing?.invoke()
        }
        content()
        HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable internal fun ReaderSettingChoice(label: String, selected: Boolean, onClick: () -> Unit,
    modifier: Modifier = Modifier, role: Role = Role.RadioButton, leading: (@Composable () -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val shape = appShape(6.dp)
    val action = when (role) {
        Role.Checkbox -> Modifier.toggleable(selected, role = role, onValueChange = { onClick() })
        Role.Button -> Modifier.clickable(role = role, onClick = onClick)
        else -> Modifier.selectable(selected, role = role, onClick = onClick)
    }
    Surface(color = if (selected) colors.primaryContainer else colors.surface,
        contentColor = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
        shape = shape, border = BorderStroke(if (selected) 1.5.dp else .5.dp, if (selected) colors.primary else colors.outlineVariant),
        modifier = modifier.clip(shape).then(action)) {
        Row(Modifier.heightIn(min = 34.dp).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            leading?.let { it(); Spacer(Modifier.width(5.dp)) }
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

@Composable internal fun ReaderSettingOptions(label: String?, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null) Text(label, Modifier.width(40.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ReaderSettingRow(label: String?, content: @Composable FlowRowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        if (label != null) Text(label, Modifier.width(40.dp).padding(top = 9.dp),
            style = MaterialTheme.typography.bodySmall, maxLines = 1)
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}
