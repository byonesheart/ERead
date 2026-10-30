package com.eyue.reader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.eyue.reader.data.ImportedFont
import com.eyue.reader.data.ReaderSettings
import com.eyue.reader.data.PageTurnStyle
import com.eyue.reader.data.ReadingMode
import com.eyue.reader.data.saveAppearanceProfile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderCompactSettingsRobolectricTest {
    @get:Rule val compose = createComposeRule()

    private fun expand(vararg titles: String) {
        titles.forEach { title ->
            val header = compose.onNodeWithTag("reader_section_$title")
            if (header.fetchSemanticsNode().config[SemanticsProperties.StateDescription] == "已收起")
                header.performScrollTo().performClick()
        }
    }

    private fun setting(description: String): SemanticsNodeInteraction {
        val label = when {
            description.startsWith("阅读字号") -> "字号"
            description.startsWith("阅读左") -> "左距"
            description.startsWith("阅读右") -> "右距"
            description.startsWith("阅读上") -> "上距"
            description.startsWith("阅读下") -> "下距"
            description.startsWith("图片左") -> "左边"
            description.startsWith("图片右") -> "右边"
            else -> "下边"
        }
        expand(when {
            label == "字号" -> "文字与排版"
            label.endsWith("距") -> "页边距"
            else -> "图片／PDF 裁边"
        })
        return compose.onNodeWithContentDescription(description)
    }

    private fun paging(tag: String): SemanticsNodeInteraction {
        expand("翻页方式")
        return compose.onNodeWithTag(tag)
    }

    @Test fun interfaceSectionsStartCollapsedAndExpandedGroupsExposeAllSliders() {
        var settings by mutableStateOf(ReaderSettings(fontSize = 28f, lineHeight = 2.2f, nightMode = true,
            readingMode = ReadingMode.PAGED, pageTurnStyle = PageTurnStyle.CURL))
        compose.setContent { MaterialTheme { ReaderCompactSettings(settings, false, { settings = it }, {}, {}) } }
        compose.onNodeWithContentDescription("阅读字号").assertDoesNotExist()
        compose.onNodeWithTag("reader_more_fonts").assertDoesNotExist()
        compose.onNodeWithText("仿真 | 关闭点击").assertIsDisplayed()
        expand("文字与排版")
        compose.onNodeWithTag("reader_section_字号").assertDoesNotExist()
        compose.onNodeWithContentDescription("阅读字号").assertExists()
        compose.onNodeWithContentDescription("阅读行间距").assertExists()
        compose.onNodeWithTag("reader_reset_文字与排版").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(19f, settings.fontSize); assertEquals(1.85f, settings.lineHeight); assertTrue(settings.nightMode) }
        expand("纸张")
        compose.onNodeWithTag("reader_background_white").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(settings.nightMode) }
        compose.onNodeWithTag("reader_night_mode").assertDoesNotExist()
        compose.onNodeWithContentDescription("阅读亮度").assertDoesNotExist()
    }

    @Config(qualifiers = "w320dp-h800dp-mdpi")
    @Test fun longCollapsedSummaryIsOmittedInsteadOfWrappingOrClipping() {
        val settings = ReaderSettings(fontName = "这是一个很长很长无法放进单行的自定义字体名称")
        compose.setContent { MaterialTheme { ReaderCompactSettings(settings, false, {}, {}, {}) } }
        compose.onNodeWithText(readerTypographySummary(settings)).assertDoesNotExist()
        compose.onNodeWithTag("reader_section_文字与排版").assertIsDisplayed()
    }

    @Test fun savedConfigurationCanBeRenamedWithoutLosingSelectionOrAppearance() {
        var settings by mutableStateOf(ReaderSettings(fontSize = 25f).saveAppearanceProfile())
        val id = settings.activeAppearanceProfileId!!
        compose.setContent { MaterialTheme { ReaderCompactSettings(settings, false, { settings = it }, {}, {}) } }
        compose.onNodeWithText("配置1").performTouchInput { longClick() }
        compose.onNodeWithTag("reader_rename_theme_$id").performClick()
        compose.onNodeWithTag("reader_theme_name").performTextReplacement("夜读")
        compose.onNodeWithTag("reader_theme_rename_save").performClick()
        compose.onNodeWithText("夜读", substring = false).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(id, settings.activeAppearanceProfileId)
            assertEquals(25f, settings.fontSize)
            assertEquals("夜读", settings.appearanceProfiles.single().name)
        }
    }

    @Test fun verticalBrightnessOnlyCommitsOnReleaseAndCanReturnToTheSystemSetting() {
        var brightness by mutableStateOf(-1f)
        var commits = 0
        compose.setContent { MaterialTheme { ReaderBrightnessControl(brightness, { brightness = it; commits++ }) } }
        val slider = compose.onNodeWithContentDescription("阅读亮度")
        slider.performTouchInput { down(center); moveBy(Offset(0f, -height * .2f)) }
        compose.runOnIdle { assertEquals(0, commits); assertEquals(-1f, brightness) }
        slider.performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, commits); assertTrue(brightness > .5f) }
        compose.onNodeWithContentDescription("亮度跟随系统").performClick()
        compose.runOnIdle { assertEquals(-1f, brightness) }
    }

    @Test fun clickLayoutAndPdfCropControlsCommitAndResetTheActualReaderSettings() {
        var settings by mutableStateOf(ReaderSettings())
        compose.setContent { MaterialTheme { Surface(Modifier.fillMaxWidth().height(400.dp)) {
            ReaderCompactSettings(settings, true, { settings = it }, {}, {}, pdf = true)
        } } }
        paging("reader_tap_horizontal").performScrollTo().performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(com.eyue.reader.data.TapPagingMode.HORIZONTAL, settings.tapPaging) }
        compose.onNodeWithTag("reader_pdf_crop_enabled").assertDoesNotExist()
        setting("图片左侧裁边 增大").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(.01f, settings.pdfCrop.left, .0001f) }
        compose.onNodeWithTag("reader_reset_图片／PDF 裁边").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(com.eyue.reader.data.PdfCrop(), settings.pdfCrop) }
        compose.runOnIdle { assertTrue(settings.pdfCrop.enabled) }
    }

    @Test fun imageAndEbookReadersExposeTheSameCropControlsWithoutRequiringAPdf() {
        var settings by mutableStateOf(ReaderSettings())
        var raster by mutableStateOf(true)
        compose.setContent { MaterialTheme { Surface(Modifier.fillMaxWidth().height(500.dp)) {
            ReaderCompactSettings(settings, raster, { settings = it }, {}, {}, sourceCssAvailable = !raster)
        } } }
        compose.onNodeWithTag("reader_pdf_crop_enabled").assertDoesNotExist()
        setting("图片右侧裁边 增大").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(.01f, settings.pdfCrop.right, .0001f); raster = false }
        compose.onNodeWithText("图片／PDF 裁边").performScrollTo().assertIsDisplayed()
        setting("图片下侧裁边 增大").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(.01f, settings.pdfCrop.bottom, .0001f) }
        compose.onNodeWithTag("reader_reset_图片／PDF 裁边").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(com.eyue.reader.data.PdfCrop(), settings.pdfCrop) }
    }

    @Test fun ebookTypographyAndScrollSubSettingsStayWithTheirRelatedControls() {
        var settings by mutableStateOf(ReaderSettings())
        compose.setContent { MaterialTheme { Surface(Modifier.fillMaxWidth().height(600.dp)) {
            ReaderCompactSettings(settings, false, { settings = it }, {}, {}, sourceCssAvailable = true)
        } } }
        expand("翻页方式")
        compose.onNodeWithTag("reader_scroll_options").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("reader_continuous_scroll").assertExists()
        expand("文字与排版")
        compose.onNodeWithTag("reader_use_source_css").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("reader_vertical_text_true").performScrollTo().performClick().assertIsSelected()
        compose.runOnIdle { assertTrue(settings.verticalText) }
        paging("reader_mode_paged").performScrollTo().performClick()
        compose.onNodeWithTag("reader_continuous_scroll").assertDoesNotExist()
        compose.runOnIdle { assertTrue(settings.continuousEbookScroll) }
    }

    @Config(qualifiers = "w360dp-h800dp-mdpi")
    @Test fun interfaceAutosavesWhileHalfScreenSettingsScrollWithoutNestedTabsOrSaveButtons() {
        var dark by mutableStateOf(false)
        var preferences by mutableStateOf(false)
        var settings by mutableStateOf(ReaderSettings().saveAppearanceProfile())
        val host = java.util.concurrent.atomic.AtomicReference<android.view.View>()
        compose.setContent {
            host.set(LocalView.current)
            MaterialTheme(colorScheme = if (dark) DarkAppColors else LightAppColors) {
            Surface(Modifier.fillMaxWidth().height(400.dp).testTag("reader_half_panel")) {
                if (preferences) ReaderPreferencesPanel(settings, { settings = it })
                else ReaderCompactSettings(settings, false, { settings = it }, {}, {})
            }
        } }
        fun capture(name: String) {
            val directory = System.getenv("EREAD_TEST_ARTIFACTS")?.let { java.io.File(it) } ?: return
            directory.mkdirs()
            val bounds = compose.onNodeWithTag("reader_half_panel").fetchSemanticsNode().boundsInRoot
            val bitmap = compose.runOnIdle {
                val view = host.get()
                val full = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(full))
                android.graphics.Bitmap.createBitmap(full, bounds.left.roundToInt(), bounds.top.roundToInt(), bounds.width.roundToInt(), bounds.height.roundToInt())
                    .also { if (it !== full) full.recycle() }
            }
            val bytes = try { java.io.ByteArrayOutputStream().use { output ->
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray()
            } } finally { bitmap.recycle() }
            java.io.File(directory, "$name.png").writeBytes(bytes)
        }
        compose.onNodeWithTag("reader_save_theme").assertDoesNotExist()
        val profile = compose.onNodeWithText("配置", substring = false).fetchSemanticsNode().boundsInRoot
        val pageTurn = compose.onNodeWithText("翻页方式", substring = false).fetchSemanticsNode().boundsInRoot
        val typography = compose.onNodeWithText("文字与排版", substring = false).fetchSemanticsNode().boundsInRoot
        assertTrue("Page turn controls must follow profiles and precede typography", profile.bottom <= pageTurn.top && pageTurn.bottom <= typography.top)
        capture("reader-interface-light")
        compose.onNodeWithText("配置1", substring = false).performTouchInput { longClick() }
        compose.onNodeWithTag("reader_delete_theme_${settings.appearanceProfiles.single().id}").assertIsDisplayed()
        capture("reader-interface-edit-light")
        expand("文字与排版")
        compose.onNodeWithTag("reader_conversion_traditional").performScrollTo().assertIsDisplayed()
        setting("阅读下边距 增大").performScrollTo().assertIsDisplayed()
        capture("reader-interface-margins")
        expand("翻页方式")
        compose.onNodeWithText("模式", substring = false).performScrollTo()
        paging("reader_mode_vertical_paged").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("reader_save_theme").assertDoesNotExist()
        compose.onNodeWithTag("reader_finish_theme_edit").assertDoesNotExist()
        compose.onNodeWithTag("reader_settings_tab_preferences").assertDoesNotExist()
        compose.onNodeWithTag("reader_settings_tab_interface").assertDoesNotExist()
        compose.onNodeWithTag("reader_reset_defaults").assertDoesNotExist()
        compose.runOnIdle { dark = true }
        capture("reader-interface-dark")
        compose.runOnIdle { preferences = true }
        compose.onNodeWithText("音量键与实体键翻页").assertIsDisplayed()
        capture("reader-settings-dark")
    }

    @Config(qualifiers = "w360dp-h800dp-mdpi")
    @Test fun compactFontChoicesKeepBoldIndependentAndOpenMoreBeforeImporting() {
        var settings by mutableStateOf(ReaderSettings(fontName = "自选字体", fontPath = "/font.ttf",
            fonts = listOf(ImportedFont("自选字体", "/font.ttf"))))
        var moreFonts = false
        var imports = 0
        var fontScale by mutableStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) { MaterialTheme {
            ReaderCompactSettings(settings, false, { settings = it }, { imports++ }, {}, onFonts = { moreFonts = true })
        } } }
        compose.onNodeWithText("自选字体").assertDoesNotExist()
        compose.onNodeWithText("等宽字体").assertDoesNotExist()
        expand("文字与排版")
        compose.onNodeWithText("字体", substring = false).performScrollTo()
        val choices = listOf("系统", "宋体", "黑体", "加粗", "更多").map { label ->
            compose.onNodeWithText(label, substring = false).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        }
        choices.zipWithNext().forEach { (left, right) ->
            assertEquals("The five font choices must stay aligned on a single row", left.top, right.top, 1f)
            assertEquals("Ordinary two-character choices use the same height", left.height, right.height, 1f)
            assertEquals("Ordinary two-character choices use the same width", left.width, right.width, 1f)
            assertTrue("Font choices must not overlap", right.left >= left.right)
        }
        val conversion = compose.onNodeWithText("简繁", substring = false).fetchSemanticsNode().boundsInRoot
        val spacing = compose.onNodeWithText("行距", substring = false).fetchSemanticsNode().boundsInRoot
        assertTrue("Chinese conversion must immediately follow fonts and precede spacing", choices.first().bottom <= conversion.top && conversion.bottom <= spacing.top)
        compose.onNodeWithText("宋体").performClick()
        compose.onNodeWithText("宋体").assertIsSelected()
        compose.runOnIdle { assertEquals("系统宋体", settings.fontName); assertNull(settings.fontPath) }
        compose.onNodeWithText("加粗").performClick()
        compose.onNodeWithText("加粗").assertIsOn()
        compose.onNodeWithText("黑体").performClick()
        compose.runOnIdle { assertEquals("系统黑体", settings.fontName); assertTrue(settings.bold) }
        compose.onNodeWithText("系统", substring = false).performClick()
        compose.runOnIdle { assertEquals("跟随系统", settings.fontName); assertTrue(settings.bold) }
        compose.onNodeWithTag("reader_more_fonts").performClick()
        compose.runOnIdle { assertTrue(moreFonts); assertEquals(0, imports) }
        compose.runOnIdle { fontScale = 2f }
        compose.onNodeWithTag("reader_more_fonts").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("更多", useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { action ->
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            assertTrue(action(layouts))
            assertEquals(1, layouts.single().lineCount)
            val layout = layouts.single()
            assertEquals(layout.layoutInput.text.length, layout.getLineEnd(0))
            // The scroll row has unbounded width. Compare the actual glyphs with the
            // displayed text bounds, rather than MultiParagraph's infinite line box.
            for (index in layout.layoutInput.text.indices) {
                val glyph = layout.getBoundingBox(index)
                assertTrue("Large-font label glyph $index must fit horizontally: $glyph / ${layout.size}",
                    glyph.left >= -1f && glyph.right <= layout.size.width + 1f)
                assertTrue("Large-font label glyph $index must fit vertically: $glyph / ${layout.size}",
                    glyph.top >= -1f && glyph.bottom <= layout.size.height + 1f)
            }
        }
    }

    @Test fun readingModesAreImmediatelyAvailableForComicsWithoutOpeningMoreSettings() {
        var settings by mutableStateOf(ReaderSettings())
        compose.setContent { MaterialTheme { ReaderCompactSettings(settings, true, { settings = it }, {}, {}) } }
        compose.onNodeWithTag("reader_mode_paged").assertDoesNotExist()
        compose.onNodeWithContentDescription("阅读亮度").assertDoesNotExist()
        expand("翻页方式")
        paging("reader_mode_paged").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(ReadingMode.PAGED, settings.readingMode) }
        paging("reader_mode_vertical_paged").performClick()
        compose.runOnIdle { assertEquals(ReadingMode.VERTICAL_PAGED, settings.readingMode) }
        paging("reader_turn_curl").assertDoesNotExist()
        paging("reader_turn_cover").assertDoesNotExist()
        paging("reader_mode_scroll").performClick()
        compose.runOnIdle { assertEquals(ReadingMode.SCROLL, settings.readingMode) }
    }

    @Test fun pageMarginsHaveIndependentFineAdjustmentAndAutosaveTheSelectedTheme() {
        var settings by mutableStateOf(ReaderSettings().saveAppearanceProfile())
        compose.setContent { MaterialTheme { ReaderCompactSettings(settings, false, { settings = it }, {}, {}) } }
        setting("阅读左边距 增大").performScrollTo().performClick()
        setting("阅读右边距 减小").performScrollTo().performClick()
        setting("阅读上边距 增大").performScrollTo().performClick()
        setting("阅读下边距 减小").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(25f, settings.marginLeft)
            assertEquals(23f, settings.marginRight)
            assertEquals(19f, settings.marginTop)
            assertEquals(17f, settings.marginBottom)
            val appearance = settings.appearanceProfiles.single().appearance
            assertEquals(settings.marginLeft, appearance.marginLeft)
            assertEquals(settings.marginRight, appearance.marginRight)
            assertEquals(settings.marginTop, appearance.marginTop)
            assertEquals(settings.marginBottom, appearance.marginBottom)
        }
    }

    @Test fun additionalFontDialogListsImportedFontsAndOwnsTheImportAction() {
        var settings by mutableStateOf(ReaderSettings(fonts = listOf(ImportedFont("自选字体", "/font.ttf"))))
        var imports = 0
        compose.setContent { MaterialTheme { ReaderFontSettings(settings, { settings = it }, { imports++ }, {}) } }
        compose.onNodeWithText("自选字体").performClick()
        compose.runOnIdle { assertEquals("/font.ttf", settings.fontPath) }
        compose.onNodeWithText("等宽字体").performClick()
        compose.runOnIdle { assertNull(settings.fontPath); assertEquals("等宽字体", settings.fontName) }
        compose.onNodeWithText("导入 TTF / OTF 字体").performClick()
        compose.runOnIdle { assertEquals(1, imports) }
        compose.onNodeWithText("跟随系统").assertDoesNotExist()
    }

    @Test fun resetDefaultsNeedsConfirmationAndCancelKeepsCurrentSettings() {
        var resets = 0
        compose.setContent { MaterialTheme { ReaderMoreSettings(ReaderSettings(fontSize = 28f), {}, {}, {}, {},
            onResetDefaults = { resets++ }) } }
        compose.onNodeWithTag("reader_reset_defaults").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, resets) }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(0, resets) }
        compose.onNodeWithTag("reader_reset_defaults").performScrollTo().performClick()
        compose.onNodeWithTag("reader_reset_defaults_confirm").performClick()
        compose.runOnIdle { assertEquals(1, resets) }
        compose.onNodeWithTag("reader_reset_defaults_confirm").assertDoesNotExist()
    }

    @Test fun preferencesHaveTheirOwnPanelAndResetKeepsItsConfirmation() {
        var resets = 0
        compose.setContent { MaterialTheme { ReaderPreferencesPanel(ReaderSettings(), {},
            onResetDefaults = { resets++ }) } }
        compose.onNodeWithText("更多设置").assertDoesNotExist()
        compose.onNodeWithTag("reader_settings_tab_preferences").assertDoesNotExist()
        compose.onNodeWithTag("reader_save_theme").assertDoesNotExist()
        compose.onNodeWithText("时间", substring = false).assertIsDisplayed()
        compose.onNodeWithText("音量键与实体键翻页").assertIsDisplayed()
        compose.onNodeWithTag("reader_reset_defaults").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, resets) }
        compose.onNodeWithTag("reader_reset_defaults_confirm").performClick()
        compose.runOnIdle { assertEquals(1, resets) }
    }

    @Test fun repeatedDragsRetainLiveStateAcrossCommittedValues() {
        var value by mutableStateOf(19f)
        var commits = 0
        compose.setContent { MaterialTheme { ReaderAdjustableSlider("字号", "阅读字号", value, 12f..36f, 1f,
            { value = it; commits++ }, { it.toInt().toString() }) } }
        val slider = compose.onNodeWithContentDescription("阅读字号")
        repeat(3) {
            val before = value
            slider.performTouchInput { down(center); moveBy(Offset(width * .12f, 0f)) }
            compose.runOnIdle { assertEquals(before, value); assertEquals(it, commits) }
            slider.performTouchInput { moveBy(Offset(width * .06f, 0f)); up() }
            compose.runOnIdle { assertTrue(value > before); assertEquals(it + 1, commits) }
        }
        val before = value
        slider.performTouchInput { down(center); moveBy(Offset(-width * .25f, 0f)); up() }
        compose.runOnIdle { assertTrue(value < before); assertEquals(4, commits) }
    }

    @Test fun tappingTheTrackDoesNothingAndButtonsProvideOneStepAdjustments() {
        var size by mutableStateOf(19f)
        var commits = 0
        compose.setContent { MaterialTheme { ReaderAdjustableSlider("字号", "阅读字号", size, 12f..36f, 1f,
            { commits++; size = it }, { it.toInt().toString() }) } }
        compose.onNodeWithContentDescription("阅读字号").performTouchInput { click(Offset(width * .9f, center.y)) }
        compose.runOnIdle { assertEquals(0, commits); assertEquals(19f, size) }
        compose.onNodeWithContentDescription("阅读字号 增大").performClick()
        compose.runOnIdle { assertEquals(1, commits); assertEquals(20f, size) }
        compose.onNodeWithContentDescription("阅读字号 减小").performClick()
        compose.runOnIdle { assertEquals(2, commits); assertEquals(19f, size) }
    }

    @Test fun draggingOnlyPreviewsUntilReleaseAndSubmitsOneFinalValue() {
        var size by mutableStateOf(19f)
        var commits = 0
        compose.setContent { MaterialTheme { ReaderAdjustableSlider("字号", "阅读字号", size, 12f..36f, 1f,
            { commits++; size = it }, { it.toInt().toString() }) } }
        val slider = compose.onNodeWithContentDescription("阅读字号")
        slider.performTouchInput { down(Offset(width * .3f, center.y)); moveTo(Offset(width * .8f, center.y)) }
        compose.runOnIdle { assertEquals(0, commits); assertEquals(19f, size) }
        assertTrue(slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current > 19f)
        slider.performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, commits); assertTrue(size > 19f) }
    }

    @Test fun draggingLeftFromTheFarRightOnlyDecreasesInsteadOfJumpingToTheTouchPoint() {
        var size by mutableStateOf(19f)
        var commits = 0
        compose.setContent { MaterialTheme { ReaderAdjustableSlider("字号", "阅读字号", size, 12f..36f, 1f,
            { commits++; size = it }, { it.toInt().toString() }) } }
        compose.onNodeWithContentDescription("阅读字号").performTouchInput {
            swipe(Offset(width * .95f, center.y), Offset(width * .70f, center.y))
        }
        compose.runOnIdle { assertEquals(1, commits); assertTrue(size < 19f) }
    }

    @Test fun namedThemeThumbnailsAutosaveAndRestoreAppearanceWithoutChangingNarrationOrPaging() {
        var settings by mutableStateOf(ReaderSettings(ttsSpeed = 1.5f, readingMode = ReadingMode.PAGED, pageTurnStyle = PageTurnStyle.CURL))
        compose.setContent { MaterialTheme { ReaderCompactSettings(settings, false, { settings = it }, {}, {}) } }
        compose.onNodeWithText("配置1").assertIsDisplayed()
        val firstId = settings.appearanceProfiles.first().id
        compose.onNodeWithTag("reader_new_theme").performScrollTo().performClick()
        setting("阅读字号 增大").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(20f, settings.fontSize) }
        compose.onNodeWithText("配置", substring = false).performScrollTo()
        compose.onNodeWithText("配置2").assertIsDisplayed()
        compose.onNodeWithTag("reader_theme_$firstId").performClick()
        compose.runOnIdle {
            assertEquals(19f, settings.fontSize)
            assertEquals(1.5f, settings.ttsSpeed)
            assertEquals(ReadingMode.PAGED, settings.readingMode)
            assertEquals(PageTurnStyle.CURL, settings.pageTurnStyle)
            assertEquals(2, settings.appearanceProfiles.size)
        }
    }

    @Test fun creatingAThemeImmediatelySelectsItAndEditsAutosaveWithoutButtons() {
        var settings by mutableStateOf(ReaderSettings().saveAppearanceProfile())
        val first = settings.appearanceProfiles.single()
        compose.setContent { MaterialTheme { ReaderCompactSettings(settings, false, { settings = it }, {}, {}) } }
        compose.onNodeWithTag("reader_save_theme").assertDoesNotExist()
        compose.onNodeWithTag("reader_new_theme").performClick()
        compose.onNodeWithTag("reader_save_theme").assertDoesNotExist()
        compose.onNodeWithTag("reader_finish_theme_edit").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(2, settings.appearanceProfiles.size)
            assertEquals(settings.appearanceProfiles.last().id, settings.activeAppearanceProfileId)
        }
        compose.onNodeWithTag("reader_theme_${settings.activeAppearanceProfileId}").assertIsSelected()
        setting("阅读字号 增大").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(2, settings.appearanceProfiles.size)
            assertEquals(first, settings.appearanceProfiles.first())
            assertEquals(20f, settings.appearanceProfiles.last().appearance.fontSize)
        }
        compose.onNodeWithTag("reader_theme_${first.id}").performScrollTo().performClick()
        compose.onNodeWithTag("reader_theme_${first.id}").assertIsSelected()
        compose.onNodeWithTag("reader_save_theme").assertDoesNotExist()
        setting("阅读字号 增大").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(2, settings.appearanceProfiles.size)
            assertEquals(first.id, settings.activeAppearanceProfileId)
            assertEquals(20f, settings.appearanceProfiles.first().appearance.fontSize)
        }
        compose.onNodeWithTag("reader_save_theme").assertDoesNotExist()
    }

    @Test fun switchingThemesAfterAnEditKeepsItsAutomaticSaveAndSelectsTheOtherTheme() {
        var settings by mutableStateOf(ReaderSettings().saveAppearanceProfile().copy(fontSize = 24f).saveAppearanceProfile())
        val profiles = settings.appearanceProfiles
        compose.setContent { MaterialTheme { ReaderCompactSettings(settings, false, { settings = it }, {}, {}) } }
        compose.onNodeWithTag("reader_theme_${profiles.first().id}").performTouchInput { longClick() }
        setting("阅读字号 增大").performScrollTo().performClick()
        compose.onNodeWithTag("reader_theme_${profiles.last().id}").performScrollTo().performClick()
        compose.onNodeWithTag("reader_save_theme").assertDoesNotExist()
        compose.onNodeWithTag("reader_finish_theme_edit").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(profiles.first().copy(appearance = profiles.first().appearance.copy(fontSize = 20f)), settings.appearanceProfiles.first())
            assertEquals(profiles.last(), settings.appearanceProfiles.last())
            assertEquals(24f, settings.fontSize)
            assertEquals(profiles.last().id, settings.activeAppearanceProfileId)
        }
    }

    @Test fun longPressEditsTheSelectedThemeAndAutosavingDoesNotCreateAnotherProfile() {
        var settings by mutableStateOf(ReaderSettings().saveAppearanceProfile().copy(fontSize = 24f).saveAppearanceProfile())
        val firstId = settings.appearanceProfiles.first().id
        val second = settings.appearanceProfiles.last()
        compose.setContent { MaterialTheme { ReaderCompactSettings(settings, false, { settings = it }, {}, {}) } }
        compose.onNodeWithTag("reader_delete_theme_$firstId").assertDoesNotExist()
        compose.onNodeWithTag("reader_theme_$firstId").performTouchInput { longClick() }
        compose.onNodeWithText("界面 · 配置1").assertIsDisplayed()
        compose.onNodeWithTag("reader_delete_theme_$firstId").assertIsDisplayed()
        compose.onNodeWithTag("reader_delete_theme_${second.id}").assertIsDisplayed()
        setting("阅读字号 增大").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(2, settings.appearanceProfiles.size)
            assertEquals(firstId, settings.activeAppearanceProfileId)
            assertEquals(20f, settings.appearanceProfiles.first().appearance.fontSize)
            assertEquals(second, settings.appearanceProfiles.last())
        }
        compose.onNodeWithTag("reader_finish_theme_edit").assertDoesNotExist()
    }

    @Test fun deletingTheLastThemeRequiresConfirmationAndKeepsASelectedCurrentAppearance() {
        var settings by mutableStateOf(ReaderSettings().saveAppearanceProfile())
        val saved = settings.appearanceProfiles.single()
        compose.setContent { MaterialTheme { ReaderCompactSettings(settings, false, { settings = it }, {}, {}) } }
        compose.onNodeWithTag("reader_theme_${saved.id}").performTouchInput { longClick() }
        setting("阅读字号 增大").performScrollTo().performClick()
        val updated = saved.copy(appearance = saved.appearance.copy(fontSize = 20f))
        compose.runOnIdle { assertEquals(updated, settings.appearanceProfiles.single()); assertEquals(20f, settings.fontSize) }
        compose.onNodeWithTag("reader_theme_${saved.id}").performScrollTo().performTouchInput { longClick() }
        compose.onNodeWithTag("reader_delete_theme_${saved.id}").performClick()
        compose.runOnIdle { assertEquals(1, settings.appearanceProfiles.size) }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(updated, settings.appearanceProfiles.single()) }
        compose.onNodeWithTag("reader_delete_theme_${saved.id}").performClick()
        compose.onNodeWithTag("reader_delete_theme_confirm").performClick()
        compose.runOnIdle {
            val replacement = settings.appearanceProfiles.single()
            assertNotEquals(saved.id, replacement.id)
            assertEquals(replacement.id, settings.activeAppearanceProfileId)
            assertEquals(updated.appearance, replacement.appearance)
        }
        compose.onNodeWithTag("reader_theme_${settings.activeAppearanceProfileId}").assertIsSelected()
        compose.onNodeWithTag("reader_save_theme").assertDoesNotExist()
    }
}
