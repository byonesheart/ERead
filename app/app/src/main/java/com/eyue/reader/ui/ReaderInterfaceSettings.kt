package com.eyue.reader.ui

import com.eyue.reader.ui.AppIcon as Icon

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eyue.reader.data.ChineseConversion
import com.eyue.reader.data.PageTurnStyle
import com.eyue.reader.data.ReaderBackground
import com.eyue.reader.data.ReaderSettings
import com.eyue.reader.data.ReadingMode
import com.eyue.reader.data.autosaveAppearanceProfile
import com.eyue.reader.data.saveAppearanceProfile
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun ReaderCompactSettings(
    settings: ReaderSettings, raster: Boolean, onSettings: (ReaderSettings) -> Unit,
    onImportFont: () -> Unit, onImportBackground: () -> Unit,
    onFonts: () -> Unit = onImportFont
) {
    var editingProfileId by remember { mutableStateOf<String?>(null) }
    val themedSettings = remember(settings) { settings.autosaveAppearanceProfile() }
    LaunchedEffect(themedSettings) {
        if (themedSettings != settings) onSettings(themedSettings)
    }
    val editingProfile = themedSettings.appearanceProfiles.firstOrNull { it.id == editingProfileId }
    val updateSettings: (ReaderSettings) -> Unit = { onSettings(it.autosaveAppearanceProfile()) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (editingProfile == null) "界面" else "界面 · ${editingProfile.name}",
                Modifier.weight(1f).testTag("reader_interface_title"),
                style = MaterialTheme.typography.titleSmall)
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ReaderThemeProfiles(themedSettings, updateSettings, editingProfileId, { editingProfileId = it }, onNewProfile = {
                editingProfileId = null
                updateSettings(themedSettings.saveAppearanceProfile())
            })
            ReaderInterfaceSettings(themedSettings, raster, updateSettings, onImportBackground, onFonts)
        }
    }
}

@Composable
internal fun ReaderPreferencesPanel(settings: ReaderSettings, onSettings: (ReaderSettings) -> Unit,
    onResetDefaults: () -> Unit = {}, sourceCssAvailable: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text("设置", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 12.dp))
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            ReaderPreferenceSettings(settings, onSettings, onResetDefaults, sourceCssAvailable)
        }
    }
}

@Composable
private fun ReaderInterfaceSettings(settings: ReaderSettings, raster: Boolean, onSettings: (ReaderSettings) -> Unit,
    onImportBackground: () -> Unit, onFonts: () -> Unit) {
    ReaderInterfaceOptions("背景") {
        listOf(ReaderBackground.PAPER to "纸白", ReaderBackground.WHITE to "纯白", ReaderBackground.GREEN to "护眼").forEach { (value, name) ->
            ReaderInterfaceOption(name, !settings.nightMode && settings.background == value,
                { onSettings(settings.copy(background = value, nightMode = false)) })
        }
        if (settings.backgroundImagePath != null) ReaderInterfaceOption("图片", settings.background == ReaderBackground.IMAGE,
            { onSettings(settings.copy(background = ReaderBackground.IMAGE)) })
        ReaderInterfaceOption("导入图片", false, onImportBackground, role = Role.Button)
    }
    ReaderInterfaceOptions("模式") {
        listOf(Triple(ReadingMode.SCROLL, "滚动", Icons.Outlined.UnfoldMore),
            Triple(ReadingMode.PAGED, "翻页", Icons.Outlined.SwapHoriz),
            Triple(ReadingMode.VERTICAL_PAGED, "上下", Icons.Outlined.SwapVert)).forEach { (mode, label, icon) ->
            ReaderInterfaceOption(label, settings.readingMode == mode, { onSettings(settings.copy(readingMode = mode)) },
                Modifier.testTag("reader_mode_${mode.name.lowercase()}"), leadingIcon = { Icon(icon, null, Modifier.size(15.dp)) })
        }
    }
    if (settings.readingMode == ReadingMode.PAGED) ReaderInterfaceOptions("翻页") {
        listOf(PageTurnStyle.COVER to "覆盖", PageTurnStyle.SLIDE to "滑动", PageTurnStyle.CURL to "仿真").forEach { (style, label) ->
            ReaderInterfaceOption(label, settings.pageTurnStyle == style, { onSettings(settings.copy(pageTurnStyle = style)) },
                Modifier.testTag("reader_turn_${style.name.lowercase()}"))
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
        ReaderInterfaceOptions("字体") {
            listOf("跟随系统" to "系统", "系统宋体" to "宋体", "系统黑体" to "黑体").forEach { (name, label) ->
                ReaderInterfaceOption(label, settings.fontPath == null && settings.fontName == name,
                    { onSettings(settings.copy(fontName = name, fontPath = null)) })
            }
            ReaderInterfaceOption("加粗", settings.bold, { onSettings(settings.copy(bold = !settings.bold)) }, role = Role.Checkbox)
            ReaderInterfaceOption("更多", false, onFonts, Modifier.testTag("reader_more_fonts"), role = Role.Button)
        }
        ReaderInterfaceOptions("简繁") {
            listOf(ChineseConversion.ORIGINAL to "原文", ChineseConversion.SIMPLIFIED to "简体", ChineseConversion.TRADITIONAL to "繁体").forEach { (value, label) ->
                ReaderInterfaceOption(label, settings.chineseConversion == value,
                    { onSettings(settings.copy(chineseConversion = value)) }, Modifier.testTag("reader_conversion_${value.name.lowercase()}"))
            }
        }
        ReaderAdjustableSlider("行距", "阅读行间距", settings.lineHeight, 1.2f..2.4f, .05f,
            { onSettings(settings.copy(lineHeight = it)) }, { String.format(Locale.getDefault(), "%.2f", it) })
        ReaderAdjustableSlider("段距", "阅读段间距", settings.paragraphSpacing, 0f..2f, .1f,
            { onSettings(settings.copy(paragraphSpacing = it)) }, { String.format(Locale.getDefault(), "%.1f", it) })
        ReaderAdjustableSlider("字距", "阅读字间距", settings.letterSpacing, -.2f..3f, .1f,
            { onSettings(settings.copy(letterSpacing = it)) }, { String.format(Locale.getDefault(), "%.1f", it) })
    } else Text("保持原页排版，双指可缩放", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    ReaderAdjustableSlider("左距", "阅读左边距", settings.marginLeft, 0f..64f, 1f,
        { onSettings(settings.copy(marginLeft = it)) }, { "${it.roundToInt()}dp" })
    ReaderAdjustableSlider("右距", "阅读右边距", settings.marginRight, 0f..64f, 1f,
        { onSettings(settings.copy(marginRight = it)) }, { "${it.roundToInt()}dp" })
    ReaderAdjustableSlider("上距", "阅读上边距", settings.marginTop, 0f..64f, 1f,
        { onSettings(settings.copy(marginTop = it)) }, { "${it.roundToInt()}dp" })
    ReaderAdjustableSlider("下距", "阅读下边距", settings.marginBottom, 0f..64f, 1f,
        { onSettings(settings.copy(marginBottom = it)) }, { "${it.roundToInt()}dp" })
}

@Composable
private fun ReaderInterfaceOptions(label: String, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.widthIn(min = 34.dp), fontSize = 12.sp, maxLines = 1)
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

@Composable
private fun ReaderInterfaceOption(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
    role: Role = Role.RadioButton, leadingIcon: (@Composable () -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val height = with(LocalDensity.current) { (16.sp.toDp() + 12.dp).coerceAtLeast(36.dp) }
    OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = height).widthIn(min = 52.dp).semantics {
        this.role = role
        if (role == Role.Checkbox) toggleableState = ToggleableState(selected)
        else if (role == Role.RadioButton) this.selected = selected
    },
        shape = appShape(8.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        border = BorderStroke(1.dp, if (selected) colors.primary else colors.outline),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (selected) colors.secondaryContainer else colors.surface,
            contentColor = if (selected) colors.onSecondaryContainer else colors.onSurface)) {
        if (leadingIcon != null) { leadingIcon(); Spacer(Modifier.width(4.dp)) }
        Text(label, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1)
    }
}
