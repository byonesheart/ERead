package com.eyue.reader.ui

import com.eyue.reader.ui.AppIcon as Icon

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.eyue.reader.data.ChineseConversion
import com.eyue.reader.data.PageTurnStyle
import com.eyue.reader.data.ReaderBackground
import com.eyue.reader.data.ReaderSettings
import com.eyue.reader.data.ReadingMode
import com.eyue.reader.data.TapPagingMode
import com.eyue.reader.data.PdfCrop
import com.eyue.reader.data.autosaveAppearanceProfile
import com.eyue.reader.data.saveAppearanceProfile
import com.eyue.reader.data.resetInterface
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun ReaderCompactSettings(
    settings: ReaderSettings, raster: Boolean, onSettings: (ReaderSettings) -> Unit,
    onImportFont: () -> Unit, onImportBackground: () -> Unit,
    onFonts: () -> Unit = onImportFont,
    sourceCssAvailable: Boolean = false, pdf: Boolean = false
) {
    var editingProfileId by remember { mutableStateOf<String?>(null) }
    val themedSettings = remember(settings) { settings.autosaveAppearanceProfile() }
    LaunchedEffect(themedSettings) {
        if (themedSettings != settings) onSettings(themedSettings)
    }
    val editingProfile = themedSettings.appearanceProfiles.firstOrNull { it.id == editingProfileId }
    val updateSettings: (ReaderSettings) -> Unit = { onSettings(it.autosaveAppearanceProfile()) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { ReaderSettingsHeading(if (editingProfile == null) "界面" else "界面 · ${editingProfile.name}",
                "调整会自动保存到当前配置", Modifier.testTag("reader_interface_title")) }
            TextButton(onClick = { updateSettings(themedSettings.resetInterface(raster)) },
                modifier = Modifier.testTag("reader_interface_reset")) { Text("重置") }
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            ReaderThemeProfiles(themedSettings, updateSettings, editingProfileId, { editingProfileId = it }, onNewProfile = {
                editingProfileId = null
                updateSettings(themedSettings.saveAppearanceProfile())
            })
            ReaderInterfaceSettings(themedSettings, raster, updateSettings, onImportBackground, onFonts, sourceCssAvailable, pdf)
        }
    }
}

@Composable
internal fun ReaderPreferencesPanel(settings: ReaderSettings, onSettings: (ReaderSettings) -> Unit,
    onResetDefaults: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text("设置", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 12.dp))
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            ReaderPreferenceSettings(settings, onSettings, onResetDefaults)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReaderInterfaceSettings(settings: ReaderSettings, raster: Boolean, onSettings: (ReaderSettings) -> Unit,
    onImportBackground: () -> Unit, onFonts: () -> Unit, sourceCssAvailable: Boolean, pdf: Boolean) {
    val colors = MaterialTheme.colorScheme
    ReaderCollapsibleSection("翻页方式", readerPagingSummary(settings)) {
        ReaderSettingOptions("模式") {
            listOf(Triple(ReadingMode.SCROLL, "滚动", Icons.Outlined.UnfoldMore),
                Triple(ReadingMode.PAGED, "翻页", Icons.Outlined.SwapHoriz),
                Triple(ReadingMode.VERTICAL_PAGED, "上下", Icons.Outlined.SwapVert)).forEach { (mode, label, icon) ->
                ReaderSettingChoice(label, settings.readingMode == mode, { onSettings(settings.copy(readingMode = mode)) },
                    Modifier.weight(1f).testTag("reader_mode_${mode.name.lowercase()}"), leading = { Icon(icon, null, Modifier.size(15.dp)) })
            }
        }
        if (settings.readingMode == ReadingMode.SCROLL && sourceCssAvailable) {
            Column(Modifier.fillMaxWidth().padding(start = 44.dp).testTag("reader_scroll_options")) {
                ReaderToggle("连贯滚动", settings.continuousEbookScroll, Modifier.testTag("reader_continuous_scroll")) {
                    onSettings(settings.copy(continuousEbookScroll = it))
                }
                Text("自然衔接相邻章节；关闭后逐章滚动。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        if (settings.readingMode == ReadingMode.PAGED) ReaderSettingOptions("效果") {
            listOf(PageTurnStyle.COVER to "覆盖", PageTurnStyle.SLIDE to "滑动", PageTurnStyle.CURL to "仿真").forEach { (style, label) ->
                ReaderSettingChoice(label, settings.pageTurnStyle == style, { onSettings(settings.copy(pageTurnStyle = style)) },
                    Modifier.weight(1f).testTag("reader_turn_${style.name.lowercase()}"))
            }
        }
        ReaderSettingOptions("点击") {
            listOf(TapPagingMode.OFF to "关闭", TapPagingMode.HORIZONTAL to "左右", TapPagingMode.VERTICAL to "上下").forEach { (mode, label) ->
                ReaderSettingChoice(label, settings.tapPaging == mode, { onSettings(settings.copy(tapPaging = mode)) },
                    Modifier.weight(1f).testTag("reader_tap_${mode.name.lowercase()}"))
            }
        }
        if (settings.tapPaging != TapPagingMode.OFF) Text(if (raster) "两侧区域翻页，中央区域开关工具栏。翻页模式下按住可连续翻页，松开停止。"
            else "两侧区域翻页，中央区域开关工具栏。长按正文选取文字，短按后横向拖动翻页。",
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
    if (!raster) ReaderCollapsibleSection("文字与排版", readerTypographySummary(settings), onReset = {
        val defaults = ReaderSettings()
        onSettings(settings.copy(fontSize = defaults.fontSize, fontName = defaults.fontName, fontPath = defaults.fontPath,
            bold = defaults.bold, chineseConversion = defaults.chineseConversion, verticalText = defaults.verticalText,
            lineHeight = defaults.lineHeight, paragraphSpacing = defaults.paragraphSpacing,
            letterSpacing = defaults.letterSpacing, paragraphIndent = defaults.paragraphIndent, useSourceCss = defaults.useSourceCss))
    }) {
        ReaderInterfaceSlider("字号", "阅读字号", settings.fontSize, 12f..36f, 1f,
            { onSettings(settings.copy(fontSize = it)) }, { it.roundToInt().toString() })
        ReaderSettingRow("字体") {
            listOf("跟随系统" to "系统", "系统宋体" to "宋体", "系统黑体" to "黑体").forEach { (name, label) ->
                ReaderSettingChoice(label, settings.fontPath == null && settings.fontName == name,
                    { onSettings(settings.copy(fontName = name, fontPath = null)) })
            }
            ReaderSettingChoice("加粗", settings.bold, { onSettings(settings.copy(bold = !settings.bold)) }, role = Role.Checkbox)
            ReaderSettingChoice("更多", false, onFonts, Modifier.testTag("reader_more_fonts"), role = Role.Button)
        }
        ReaderSettingOptions("简繁") {
            listOf(ChineseConversion.ORIGINAL to "原文", ChineseConversion.SIMPLIFIED to "简体", ChineseConversion.TRADITIONAL to "繁体").forEach { (value, label) ->
                ReaderSettingChoice(label, settings.chineseConversion == value,
                    { onSettings(settings.copy(chineseConversion = value)) }, Modifier.weight(1f).testTag("reader_conversion_${value.name.lowercase()}"))
            }
        }
        ReaderSettingOptions("排版") {
            listOf(false to "横排", true to "竖排").forEach { (vertical, label) ->
                ReaderSettingChoice(label, settings.verticalText == vertical,
                    { onSettings(settings.copy(verticalText = vertical)) }, Modifier.weight(1f).testTag("reader_vertical_text_$vertical"))
            }
        }
        ReaderInterfaceSlider("行距", "阅读行间距", settings.lineHeight, 1.2f..2.4f, .05f,
            { onSettings(settings.copy(lineHeight = it)) }, { String.format(Locale.getDefault(), "%.2f", it) })
        ReaderInterfaceSlider("段距", "阅读段间距", settings.paragraphSpacing, 0f..2f, .1f,
            { onSettings(settings.copy(paragraphSpacing = it)) }, { String.format(Locale.getDefault(), "%.1f", it) })
        ReaderInterfaceSlider("字距", "阅读字间距", settings.letterSpacing, -.2f..3f, .1f,
            { onSettings(settings.copy(letterSpacing = it)) }, { String.format(Locale.getDefault(), "%.1f", it) })
        ReaderInterfaceSlider("缩进", "首行缩进", settings.paragraphIndent, 0f..4f, .5f,
            { onSettings(settings.copy(paragraphIndent = it)) }, { "${it}字" })
        if (sourceCssAvailable) {
            ReaderToggle("使用原书 CSS", settings.useSourceCss, Modifier.testTag("reader_use_source_css")) {
                onSettings(settings.copy(useSourceCss = it))
            }
            Text("保留原书文字排版，字体与段距使用当前设置。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
    ReaderCollapsibleSection("纸张", ReaderBackgroundChoices.firstOrNull { it.first == settings.background }?.second
        ?: if (settings.background == ReaderBackground.IMAGE) "图片" else null) {
        if (raster) Text("保持原页排版，双指可缩放", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        ReaderBackgroundChoices.chunked(3).forEachIndexed { index, choices -> ReaderSettingOptions(if (index == 0) "背景" else "") {
            choices.forEach { (value, name) ->
                val preview = readerPalette(value, false, colors)
                ReaderSettingChoice(name, settings.background == value,
                    { onSettings(settings.copy(background = value)) },
                    Modifier.weight(1f).testTag("reader_background_${value.name.lowercase()}"), leading = {
                        Box(Modifier.size(16.dp).clip(appShape(3.dp)).background(preview.background)
                            .border(.5.dp, colors.outlineVariant, appShape(3.dp)))
                    })
            }
            repeat(3 - choices.size) { Spacer(Modifier.weight(1f)) }
        } }
        ReaderSettingRow(null) {
            if (settings.backgroundImagePath != null) ReaderSettingChoice("图片", settings.background == ReaderBackground.IMAGE,
                { onSettings(settings.copy(background = ReaderBackground.IMAGE)) })
            ReaderSettingChoice("导入图片", false, onImportBackground, role = Role.Button)
        }
    }
    ReaderCollapsibleSection("页边距", onReset = {
        onSettings(settings.copy(marginLeft = if (raster) 0f else 24f, marginRight = if (raster) 0f else 24f,
            marginTop = if (raster) 0f else 18f, marginBottom = if (raster) 0f else 18f))
    }) {
        ReaderInterfaceSlider("左距", "阅读左边距", settings.marginLeft, 0f..64f, 1f,
            { onSettings(settings.copy(marginLeft = it)) }, { "${it.roundToInt()}dp" })
        ReaderInterfaceSlider("右距", "阅读右边距", settings.marginRight, 0f..64f, 1f,
            { onSettings(settings.copy(marginRight = it)) }, { "${it.roundToInt()}dp" })
        ReaderInterfaceSlider("上距", "阅读上边距", settings.marginTop, 0f..64f, 1f,
            { onSettings(settings.copy(marginTop = it)) }, { "${it.roundToInt()}dp" })
        ReaderInterfaceSlider("下距", "阅读下边距", settings.marginBottom, 0f..64f, 1f,
            { onSettings(settings.copy(marginBottom = it)) }, { "${it.roundToInt()}dp" })
    }
    if (pdf || raster || sourceCssAvailable) ReaderCollapsibleSection("图片／PDF 裁边",
        onReset = { onSettings(settings.copy(pdfCrop = PdfCrop())) }) {
        val crop = settings.pdfCrop.normalized()
        listOf("左" to crop.left, "右" to crop.right, "上" to crop.top, "下" to crop.bottom).forEach { (label, value) ->
            ReaderInterfaceSlider("${label}边", "图片${label}侧裁边", value, 0f..0.4f, .01f, { amount ->
                val changed = when (label) { "左" -> crop.copy(left = amount); "右" -> crop.copy(right = amount)
                    "上" -> crop.copy(top = amount); else -> crop.copy(bottom = amount) }
                onSettings(settings.copy(pdfCrop = changed))
            }, { "${(it * 100).roundToInt()}%" })
        }
        Text("始终启用，0% 保留完整页面；仅调整显示，不修改原文件。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}

internal fun readerPagingSummary(settings: ReaderSettings): String {
    val mode = when (settings.readingMode) {
        ReadingMode.SCROLL -> if (settings.continuousEbookScroll) "连贯滚动" else "滚动"
        ReadingMode.VERTICAL_PAGED -> "上下"
        ReadingMode.PAGED -> when (settings.pageTurnStyle) {
            PageTurnStyle.COVER -> "覆盖"; PageTurnStyle.SLIDE -> "滑动"; PageTurnStyle.CURL -> "仿真"
        }
    }
    val tap = when (settings.tapPaging) {
        TapPagingMode.OFF -> "关闭点击"; TapPagingMode.HORIZONTAL -> "左右点击"; TapPagingMode.VERTICAL -> "上下点击"
    }
    return "$mode | $tap"
}

internal fun readerTypographySummary(settings: ReaderSettings): String {
    val font = when (settings.fontName) { "跟随系统" -> "系统"; "系统宋体" -> "宋体"; "系统黑体" -> "黑体"; else -> settings.fontName }
    val language = when (settings.chineseConversion) {
        ChineseConversion.ORIGINAL -> "原文"; ChineseConversion.SIMPLIFIED -> "简体"; ChineseConversion.TRADITIONAL -> "繁体"
    }
    return "$font | $language | ${if (settings.verticalText) "竖排" else "横排"}"
}

@Composable private fun ReaderInterfaceSlider(label: String, description: String, value: Float,
    range: ClosedFloatingPointRange<Float>, step: Float, onCommit: (Float) -> Unit,
    display: (Float) -> String) {
    ReaderAdjustableSlider(label, description, value, range, step, onCommit, display)
}
