package com.eyue.reader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eyue.reader.data.ChineseConversion
import com.eyue.reader.data.PageTurnStyle
import com.eyue.reader.data.ReaderBackground
import com.eyue.reader.data.ReaderSettings
import com.eyue.reader.data.ReadingMode
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun ReaderCompactSettings(
    settings: ReaderSettings, raster: Boolean, onSettings: (ReaderSettings) -> Unit,
    onImportFont: () -> Unit, onImportBackground: () -> Unit, onMore: () -> Unit,
    onFonts: () -> Unit = onImportFont, onResetDefaults: () -> Unit = {}
) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("界面", "设置").forEachIndexed { index, label ->
                FilterChip(tab == index, { tab = index }, { Text(label, fontSize = 13.sp) },
                    Modifier.height(34.dp).testTag(if (index == 0) "reader_settings_tab_interface" else "reader_settings_tab_preferences"))
            }
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (tab == 0) ReaderInterfaceSettings(settings, raster, onSettings, onImportBackground, onFonts)
            else ReaderPreferenceSettings(settings, onSettings, onResetDefaults)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReaderInterfaceSettings(settings: ReaderSettings, raster: Boolean, onSettings: (ReaderSettings) -> Unit,
    onImportBackground: () -> Unit, onFonts: () -> Unit) {
    ReaderThemeProfiles(settings, onSettings)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("背景", Modifier.width(34.dp), fontSize = 12.sp)
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(ReaderBackground.PAPER to "纸白", ReaderBackground.WHITE to "纯白", ReaderBackground.GREEN to "护眼").forEach { (value, name) ->
                FilterChip(!settings.nightMode && settings.background == value,
                    { onSettings(settings.copy(background = value, nightMode = false)) }, { Text(name, fontSize = 12.sp) }, Modifier.height(32.dp))
            }
            if (settings.backgroundImagePath != null) FilterChip(settings.background == ReaderBackground.IMAGE,
                { onSettings(settings.copy(background = ReaderBackground.IMAGE)) }, { Text("图片", fontSize = 12.sp) }, Modifier.height(32.dp))
            TextButton(onClick = onImportBackground, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("导入图片", fontSize = 12.sp) }
        }
    }
    ReaderAdjustableSlider("亮度", "阅读亮度", if (settings.brightness < 0f) .5f else settings.brightness, .01f..1f, .01f,
        { onSettings(settings.copy(brightness = it)) },
        { if (settings.brightness < 0f && it == .5f) "自动" else "${(it * 100).roundToInt()}%" }, trailing = {
            IconButton(onClick = { onSettings(settings.copy(brightness = -1f)) }, Modifier.size(36.dp)) {
                Icon(Icons.Outlined.BrightnessAuto, "亮度跟随系统", Modifier.size(18.dp))
            }
        })
    if (!raster) {
        ReaderAdjustableSlider("字号", "阅读字号", settings.fontSize, 12f..36f, 1f,
            { onSettings(settings.copy(fontSize = it)) }, { it.roundToInt().toString() })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("字体", Modifier.width(34.dp), fontSize = 12.sp)
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("跟随系统" to "系统", "系统宋体" to "宋体", "系统黑体" to "黑体").forEach { (name, label) ->
                    FilterChip(settings.fontPath == null && settings.fontName == name,
                        { onSettings(settings.copy(fontName = name, fontPath = null)) }, { Text(label, fontSize = 12.sp) }, Modifier.height(32.dp))
                }
                FilterChip(settings.bold, { onSettings(settings.copy(bold = !settings.bold)) }, { Text("加粗", fontSize = 12.sp) }, Modifier.height(32.dp))
                TextButton(onClick = onFonts, contentPadding = PaddingValues(horizontal = 4.dp),
                    modifier = Modifier.testTag("reader_more_fonts")) { Text("更多", fontSize = 12.sp) }
            }
        }
        ReaderAdjustableSlider("行距", "阅读行间距", settings.lineHeight, 1.2f..2.4f, .05f,
            { onSettings(settings.copy(lineHeight = it)) }, { String.format(Locale.getDefault(), "%.2f", it) })
        ReaderAdjustableSlider("段距", "阅读段间距", settings.paragraphSpacing, 0f..2f, .1f,
            { onSettings(settings.copy(paragraphSpacing = it)) }, { String.format(Locale.getDefault(), "%.1f", it) })
        ReaderAdjustableSlider("字距", "阅读字间距", settings.letterSpacing, -.2f..3f, .1f,
            { onSettings(settings.copy(letterSpacing = it)) }, { String.format(Locale.getDefault(), "%.1f", it) })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("简繁", Modifier.width(34.dp), fontSize = 12.sp)
            listOf(ChineseConversion.ORIGINAL to "原文", ChineseConversion.SIMPLIFIED to "简体", ChineseConversion.TRADITIONAL to "繁体").forEach { (value, label) ->
                FilterChip(settings.chineseConversion == value, { onSettings(settings.copy(chineseConversion = value)) },
                    { Text(label, fontSize = 12.sp) }, Modifier.padding(end = 4.dp).height(32.dp).testTag("reader_conversion_${value.name.lowercase()}"))
            }
        }
    } else Text("保持原页排版，双指可缩放", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("模式", Modifier.width(34.dp), fontSize = 12.sp)
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(Triple(ReadingMode.SCROLL, "滚动", Icons.Outlined.UnfoldMore),
                Triple(ReadingMode.PAGED, "翻页", Icons.Outlined.SwapHoriz),
                Triple(ReadingMode.VERTICAL_PAGED, "上下", Icons.Outlined.SwapVert)).forEach { (mode, label, icon) ->
                FilterChip(settings.readingMode == mode, { onSettings(settings.copy(readingMode = mode)) },
                    { Text(label, fontSize = 12.sp) }, Modifier.height(32.dp).testTag("reader_mode_${mode.name.lowercase()}"),
                    leadingIcon = { Icon(icon, null, Modifier.size(15.dp)) })
            }
        }
    }
    if (settings.readingMode == ReadingMode.PAGED) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("翻页", Modifier.width(34.dp), fontSize = 12.sp)
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(PageTurnStyle.COVER to "覆盖", PageTurnStyle.SLIDE to "滑动", PageTurnStyle.CURL to "仿真").forEach { (style, label) ->
                FilterChip(settings.pageTurnStyle == style, { onSettings(settings.copy(pageTurnStyle = style)) },
                    { Text(label, fontSize = 12.sp) }, Modifier.height(32.dp).testTag("reader_turn_${style.name.lowercase()}"))
            }
        }
    }
}
