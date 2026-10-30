package com.eyue.reader.ui

// Settings geometry adapted from the supplied ebook UI, retaining ERead's settings and actions.
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

@Composable internal fun ReaderSettingsHeading(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

@Composable internal fun ReaderCollapsibleSection(title: String, summary: String? = null,
    onReset: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = MaterialTheme.typography.bodySmall
    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth().clip(appShape(6.dp))
            .clickable { expanded = !expanded }.semantics { stateDescription = if (expanded) "已展开" else "已收起" }
            .testTag("reader_section_$title")) {
            val available = with(density) { maxWidth.roundToPx() - 44.dp.roundToPx() } -
                measurer.measure(title, style = MaterialTheme.typography.titleSmall).size.width
            val fits = !summary.isNullOrBlank() && available > 0 && !measurer.measure(summary, style = style,
                maxLines = 1, softWrap = false, constraints = Constraints(maxWidth = available)).hasVisualOverflow
            Row(Modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                if (!expanded && fits) Text(summary, style = style, maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (expanded) "⌃" else "⌄", style = style)
            }
        }
        if (expanded) {
            if (onReset != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onReset, modifier = Modifier.height(30.dp).testTag("reader_reset_$title"),
                    contentPadding = PaddingValues(horizontal = 8.dp)) { Text("重置", style = style) }
            }
            content()
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
