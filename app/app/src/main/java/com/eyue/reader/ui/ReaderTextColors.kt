package com.eyue.reader.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FormatColorReset
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.eyue.reader.data.DEFAULT_DIALOGUE_COLOR
import com.eyue.reader.data.opaqueReaderColor
import java.util.Locale

private val textColorPresets = listOf("墨黑" to 0xFF252522L, "蓝色" to 0xFF4A79B3L,
    "紫色" to 0xFF8A5DA8L, "红色" to 0xFFB54C56L, "绿色" to 0xFF467A56L)

private fun readerColorHex(color: Long) = "#%06X".format(Locale.ROOT, color and 0xFFFFFF)

internal fun parseReaderColor(input: String): Long? {
    val value = input.trim().removePrefix("#")
    return value.takeIf { it.length == 6 && it.all { char -> char in '0'..'9' || char.lowercaseChar() in 'a'..'f' } }
        ?.toLongOrNull(16)?.let(::opaqueReaderColor)
}

internal class ReaderCustomColors(context: Context) {
    private val preferences = context.getSharedPreferences("reader-custom-colors", Context.MODE_PRIVATE)

    fun read(): List<Long> = preferences.getString("colors", "").orEmpty().split(',').mapNotNull(::parseReaderColor).distinct()

    fun add(color: Long) {
        val updated = (listOf(opaqueReaderColor(color)) + read()).distinct()
        preferences.edit().putString("colors", updated.joinToString(",", transform = ::readerColorHex)).apply()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderTextColorRow(label: String, selected: Long?, showLabel: Boolean = true, onSelect: (Long?) -> Unit) {
    val context = LocalContext.current
    val palette = remember(context) { ReaderCustomColors(context) }
    val font = label == "字体颜色"
    val prefix = if (font) "reader_font_color" else "reader_dialogue_color"
    var more by remember { mutableStateOf(false) }
    val limit = if (font) 4 else 5
    val selectedSwatch = selected?.let { value -> textColorPresets.find { it.second == value } ?: (readerColorHex(value) to value) }
    val presets = textColorPresets.take(limit)
    val choices = if (selectedSwatch == null || selectedSwatch in presets) presets else listOf(selectedSwatch) + presets.dropLast(1)
    ReaderSettingRow(if (showLabel) label else "", labelWidth = 56.dp) {
        if (font) ReaderTextColorSwatch(null, "默认字体颜色", selected == null,
            Modifier.testTag("${prefix}_default")) { onSelect(null) }
        choices.forEach { (name, color) -> ReaderTextColorSwatch(color, name, selected == color,
            Modifier.testTag("${prefix}_${readerColorHex(color).drop(1)}")) { onSelect(color) } }
        ReaderSettingChoice("更多", false, { more = true }, Modifier.testTag("${prefix}_more"), role = Role.Button, horizontalPadding = 6.dp)
    }
    if (more) ReaderCustomColorsDialog(label, selected, palette, onDismiss = { more = false }, onSelect = {
        onSelect(it); more = false
    })
}

@Composable
private fun ReaderTextColorSwatch(value: Long?, label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.size(30.dp).clip(CircleShape).selectable(selected, role = Role.RadioButton, onClick = onClick)
        .semantics { contentDescription = label }
        .border(if (selected) 2.dp else .5.dp, if (selected) colors.primary else colors.outlineVariant, CircleShape),
        contentAlignment = Alignment.Center) {
        if (value == null) AppIcon(Icons.Outlined.FormatColorReset, null, Modifier.size(17.dp), tint = colors.onSurfaceVariant)
        else Box(Modifier.size(22.dp).background(Color(value), CircleShape).border(.5.dp, colors.outlineVariant, CircleShape))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReaderCustomColorsDialog(label: String, selected: Long?, palette: ReaderCustomColors, onDismiss: () -> Unit,
    onSelect: (Long) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    if (adding) {
        ReaderAddColorDialog(selected ?: DEFAULT_DIALOGUE_COLOR, onDismiss = { adding = false }, onSave = {
            palette.add(it); onSelect(it)
        })
    } else AlertDialog(onDismissRequest = onDismiss, title = { Text("$label · 自定义颜色") }, text = {
        val saved = remember { palette.read() }
        if (saved.isEmpty()) Text("暂无自定义颜色", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else FlowRow(Modifier.fillMaxWidth().heightIn(max = 230.dp).verticalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            saved.forEach { color -> ReaderTextColorSwatch(color, readerColorHex(color), selected == color,
                Modifier.testTag("reader_custom_color_${readerColorHex(color).drop(1)}")) { onSelect(color) } }
        }
    }, confirmButton = { TextButton(onClick = { adding = true }, modifier = Modifier.testTag("reader_add_custom_color")) { Text("添加颜色") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

@Composable
private fun ReaderAddColorDialog(initial: Long, onDismiss: () -> Unit, onSave: (Long) -> Unit) {
    var hex by remember { mutableStateOf(readerColorHex(initial)) }
    val initialHsv = remember { FloatArray(3).apply { android.graphics.Color.colorToHSV(initial.toInt(), this) } }
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initialHsv[1]) }
    var brightness by remember { mutableFloatStateOf(initialHsv[2]) }
    val color = parseReaderColor(hex)
    val preview = Color.hsv(hue, saturation, brightness)
    fun updateHex() { hex = readerColorHex(Color.hsv(hue, saturation, brightness).toArgb().toLong()) }
    fun acceptCode(value: String) {
        hex = value.take(8)
        parseReaderColor(hex)?.let { code ->
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(code.toInt(), hsv)
            hue = hsv[0]; saturation = hsv[1]; brightness = hsv[2]
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("添加颜色") }, text = {
        Column(Modifier.heightIn(max = 370.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.fillMaxWidth().height(36.dp).background(preview, appShape(6.dp)))
            ReaderColorSlider("色相", hue, 0f..360f, "reader_custom_hue") { hue = it; updateHex() }
            ReaderColorSlider("饱和度", saturation, 0f..1f, "reader_custom_saturation") { saturation = it; updateHex() }
            ReaderColorSlider("明度", brightness, 0f..1f, "reader_custom_brightness") { brightness = it; updateHex() }
            OutlinedTextField(hex, ::acceptCode, singleLine = true, label = { Text("颜色编码") },
                isError = color == null, supportingText = { Text("6 位十六进制，例如 #4A79B3") },
                modifier = Modifier.fillMaxWidth().testTag("reader_custom_color_hex"))
        }
    }, confirmButton = { TextButton(enabled = color != null, onClick = { color?.let(onSave) },
        modifier = Modifier.testTag("reader_save_custom_color")) { Text("保存并使用") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun ReaderColorSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, tag: String, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(49.dp), style = MaterialTheme.typography.bodySmall)
        Slider(value, onChange, Modifier.weight(1f).testTag(tag).semantics { contentDescription = label }, valueRange = range)
    }
}
