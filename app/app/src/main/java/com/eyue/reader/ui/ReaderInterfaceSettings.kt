package com.eyue.reader.ui

import com.eyue.reader.ui.AppIcon as Icon

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
        ReaderSettingsHeading(if (editingProfile == null) "界面" else "界面 · ${editingProfile.name}",
            "调整会自动保存到当前配置", Modifier.testTag("reader_interface_title"))
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
    onResetDefaults: () -> Unit = {}, autoReading: Boolean = false, onAutoReading: ((Boolean) -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text("设置", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 12.dp))
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            if (onAutoReading != null) ReaderSettingSection("自动阅读") {
                val scroll = settings.readingMode == ReadingMode.SCROLL
                ReaderToggle(if (scroll) "自动滚动" else "自动翻页", autoReading,
                    Modifier.testTag("reader_auto_enabled"), onAutoReading)
                if (scroll) ReaderAdjustableSlider("速度", "自动滚动速度", settings.autoScrollSpeed, 8f..160f, 4f,
                    { onSettings(settings.copy(autoScrollSpeed = it)) }, { "${it.roundToInt()}像素/秒" })
                else ReaderAdjustableSlider("间隔", "自动翻页间隔", settings.autoPageSeconds, 2f..120f, 1f,
                    { onSettings(settings.copy(autoPageSeconds = it)) }, { "${it.roundToInt()}秒/页" })
                Text("手动操作或切到后台会暂停，可在工具栏继续。", style = MaterialTheme.typography.bodySmall)
            }
            ReaderPreferenceSettings(settings, onSettings, onResetDefaults)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReaderInterfaceSettings(settings: ReaderSettings, raster: Boolean, onSettings: (ReaderSettings) -> Unit,
    onImportBackground: () -> Unit, onFonts: () -> Unit, sourceCssAvailable: Boolean, pdf: Boolean) {
    val colors = MaterialTheme.colorScheme
    ReaderSettingSection("翻页方式") {
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
        if (settings.tapPaging != TapPagingMode.OFF) Text("两侧区域翻页，中央区域开关工具栏。",
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
    if (pdf || raster || sourceCssAvailable) ReaderSettingSection("图片／PDF 裁边", trailing = {
        TextButton(onClick = { onSettings(settings.copy(pdfCrop = PdfCrop())) }, modifier = Modifier.testTag("reader_pdf_crop_reset")) { Text("重置") }
    }) {
        ReaderToggle("裁去页面边缘", settings.pdfCrop.enabled, Modifier.testTag("reader_pdf_crop_enabled")) {
            onSettings(settings.copy(pdfCrop = settings.pdfCrop.copy(enabled = it)))
        }
        if (settings.pdfCrop.enabled) {
            val crop = settings.pdfCrop
            listOf("左" to crop.left, "右" to crop.right, "上" to crop.top, "下" to crop.bottom).forEach { (label, value) ->
                ReaderAdjustableSlider("${label}边", "图片${label}侧裁边", value, 0f..0.4f, .01f, { amount ->
                    val changed = when (label) { "左" -> crop.copy(left = amount); "右" -> crop.copy(right = amount)
                        "上" -> crop.copy(top = amount); else -> crop.copy(bottom = amount) }
                    onSettings(settings.copy(pdfCrop = changed))
                }, { "${(it * 100).roundToInt()}%" })
            }
            Text("适用于漫画页面和正文插图，仅调整显示，不修改原文件。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
    if (!raster) ReaderSettingSection("文字与排版") {
        ReaderAdjustableSlider("字号", "阅读字号", settings.fontSize, 12f..36f, 1f,
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
        ReaderAdjustableSlider("行距", "阅读行间距", settings.lineHeight, 1.2f..2.4f, .05f,
            { onSettings(settings.copy(lineHeight = it)) }, { String.format(Locale.getDefault(), "%.2f", it) })
        ReaderAdjustableSlider("段距", "阅读段间距", settings.paragraphSpacing, 0f..2f, .1f,
            { onSettings(settings.copy(paragraphSpacing = it)) }, { String.format(Locale.getDefault(), "%.1f", it) })
        ReaderAdjustableSlider("字距", "阅读字间距", settings.letterSpacing, -.2f..3f, .1f,
            { onSettings(settings.copy(letterSpacing = it)) }, { String.format(Locale.getDefault(), "%.1f", it) })
        if (sourceCssAvailable) {
            ReaderToggle("使用原书 CSS", settings.useSourceCss, Modifier.testTag("reader_use_source_css")) {
                onSettings(settings.copy(useSourceCss = it))
            }
            Text("保留原书文字排版，字体与段距使用当前设置。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
    ReaderSettingSection("纸张与亮度", trailing = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("夜间", style = MaterialTheme.typography.bodySmall)
            Switch(settings.nightMode, { onSettings(settings.copy(nightMode = it)) }, Modifier.testTag("reader_night_mode"))
        }
    }) {
        if (raster) Text("保持原页排版，双指可缩放", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        ReaderSettingOptions("背景") {
            listOf(ReaderBackground.PAPER to "纸白", ReaderBackground.WHITE to "纯白", ReaderBackground.GREEN to "护眼").forEach { (value, name) ->
                ReaderSettingChoice(name, !settings.nightMode && settings.background == value,
                    { onSettings(settings.copy(background = value, nightMode = false)) }, Modifier.weight(1f), leading = {
                        val paper = when (value) {
                            ReaderBackground.WHITE -> androidx.compose.ui.graphics.Color.White
                            ReaderBackground.GREEN -> androidx.compose.ui.graphics.Color(0xFFE5EBD9)
                            else -> androidx.compose.ui.graphics.Color(0xFFF6F6EF)
                        }
                        Box(Modifier.size(16.dp).clip(appShape(3.dp)).background(paper)
                            .border(.5.dp, colors.outlineVariant, appShape(3.dp)))
                    })
            }
        }
        ReaderSettingRow(null) {
            if (settings.backgroundImagePath != null) ReaderSettingChoice("图片", settings.background == ReaderBackground.IMAGE,
                { onSettings(settings.copy(background = ReaderBackground.IMAGE)) })
            ReaderSettingChoice("导入图片", false, onImportBackground, role = Role.Button)
        }
        ReaderAdjustableSlider("亮度", "阅读亮度", if (settings.brightness < 0f) .5f else settings.brightness, .01f..1f, .01f,
            { onSettings(settings.copy(brightness = it)) },
            { if (settings.brightness < 0f && it == .5f) "自动" else "${(it * 100).roundToInt()}%" }, trailing = {
                IconButton(onClick = { onSettings(settings.copy(brightness = -1f)) }, Modifier.size(30.dp)) {
                    Icon(Icons.Outlined.BrightnessAuto, "亮度跟随系统", Modifier.size(18.dp))
                }
            })
    }
    ReaderSettingSection("页边距") {
        ReaderAdjustableSlider("左距", "阅读左边距", settings.marginLeft, 0f..64f, 1f,
            { onSettings(settings.copy(marginLeft = it)) }, { "${it.roundToInt()}dp" })
        ReaderAdjustableSlider("右距", "阅读右边距", settings.marginRight, 0f..64f, 1f,
            { onSettings(settings.copy(marginRight = it)) }, { "${it.roundToInt()}dp" })
        ReaderAdjustableSlider("上距", "阅读上边距", settings.marginTop, 0f..64f, 1f,
            { onSettings(settings.copy(marginTop = it)) }, { "${it.roundToInt()}dp" })
        ReaderAdjustableSlider("下距", "阅读下边距", settings.marginBottom, 0f..64f, 1f,
            { onSettings(settings.copy(marginBottom = it)) }, { "${it.roundToInt()}dp" })
    }
}
