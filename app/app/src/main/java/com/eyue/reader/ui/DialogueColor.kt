package com.eyue.reader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

internal val LocalReaderDialogueColor = compositionLocalOf<Long?> { null }
internal data class ReaderDialogueSettings(val enabled: Boolean, val color: Long, val onSave: (Boolean, Long) -> Unit)
internal val LocalReaderDialogueSettings = compositionLocalOf<ReaderDialogueSettings?> { null }
private val dialogue = Regex("“[^”\\r\\n]+”|「[^」\\r\\n]+」|『[^』\\r\\n]+』|\"[^\"\\r\\n]+\"")
private val dialogueColors = listOf("蓝色" to "4A79B3", "紫色" to "8A5DA8", "红色" to "B54C56", "绿色" to "467A56")

internal fun AnnotatedString.Builder.colorDialogue(text: String, start: Int, end: Int, color: Long) {
    if (start >= end || start < 0 || end > text.length) return
    val paragraphStart = text.lastIndexOf('\n', start - 1) + 1
    val paragraphEnd = text.indexOf('\n', end).takeIf { it >= 0 } ?: text.length
    val style = SpanStyle(color = Color(color))
    for (match in dialogue.findAll(text.substring(paragraphStart, paragraphEnd))) {
        val from = maxOf(start, paragraphStart + match.range.first)
        val to = minOf(end, paragraphStart + match.range.last + 1)
        if (from < to) addStyle(style, from - start, to - start)
    }
}

@Composable
internal fun DialogueColorDialog(initiallyEnabled: Boolean, initialColor: Long,
    onDismiss: () -> Unit, onSave: (Boolean, Long) -> Unit) {
    var enabled by remember { mutableStateOf(initiallyEnabled) }
    var hex by remember { mutableStateOf("%06X".format(initialColor and 0xFFFFFF)) }
    val color = hex.removePrefix("#").takeIf { it.matches(Regex("[0-9a-fA-F]{6}")) }
        ?.toLong(16)?.or(0xFF000000)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("对话颜色") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("启用对话着色")
                    Switch(enabled, { enabled = it }, Modifier.testTag("dialogue_color_enabled"))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    dialogueColors.forEach { (name, value) ->
                        Box(Modifier.size(44.dp).clickable { hex = value }.semantics { contentDescription = name }.padding(6.dp)
                            .background(Color(value.toLong(16) or 0xFF000000), CircleShape)
                            .then(if (hex.removePrefix("#").equals(value, true))
                                Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier))
                    }
                }
                OutlinedTextField(hex, { if (it.length <= 7) hex = it }, singleLine = true,
                    label = { Text("颜色 HEX") }, isError = color == null,
                    supportingText = { Text("输入 6 位十六进制颜色，例如 4A79B3") })
                Text("“对话文字预览”", color = color?.let(::Color) ?: Color.Unspecified)
            }
        },
        confirmButton = {
            TextButton(enabled = color != null, onClick = { color?.let { onSave(enabled, it) } }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
