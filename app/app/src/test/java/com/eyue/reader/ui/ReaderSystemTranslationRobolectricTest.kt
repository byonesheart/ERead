package com.eyue.reader.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import org.robolectric.Shadows.shadowOf
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReaderSystemTranslationRobolectricTest {
    @Test fun availableTranslatorReceivesTextThroughAnExplicitSystemTranslationIntent() {
        val context = RuntimeEnvironment.getApplication()
        val resolver = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = "test.translate"; name = "Translator"; exported = true }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(readerTranslationIntent("原文"), resolver)
        rememberReaderTranslator(context, android.app.Activity.RESULT_OK,
            Intent().setClassName("test.translate", "Translator"))
        assertTrue(launchSelectedReaderTranslator(context, "原文"))
        val request = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_TRANSLATE, request.action)
        assertEquals("test.translate", request.component!!.packageName)
        assertEquals("原文", request.getCharSequenceExtra(Intent.EXTRA_TEXT).toString())
    }
    @Test fun selectionUsesTheSystemReadOnlyTextProcessingProtocol() {
        val text = "选中的原文，含 punctuation。"
        val intent = readerTranslationIntent(text)
        assertEquals(Intent.ACTION_TRANSLATE, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals(text, intent.getCharSequenceExtra(Intent.EXTRA_TEXT).toString())
    }
    @Test fun unavailableTranslationAppPickerShowsAnExplanation() {
        assertNull(readerTranslationPickerIntent(RuntimeEnvironment.getApplication()))
        assertEquals("此设备未提供可调用的系统翻译", ShadowToast.getTextOfLatestToast())
    }

    @Test fun dictionaryIsTheDefaultAndTheNativePickerRemembersTheSelectedApp() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("reader_translation", 0).edit().clear().commit()
        for (name in listOf("first", "second")) {
            val resolver = ResolveInfo().apply { activityInfo = ActivityInfo().apply {
                packageName = "test.$name"; this.name = "Translator"; exported = true
                nonLocalizedLabel = "翻译-$name"
            } }
            shadowOf(context.packageManager).addOrUpdateActivity(resolver.activityInfo)
            shadowOf(context.packageManager).addResolveInfoForIntent(readerTranslationIntent("文字"), resolver)
        }
        assertEquals("海词在线词典", selectedReaderTranslatorLabel(context))
        assertFalse(launchSelectedReaderTranslator(context, "中 文/#"))
        assertNull(shadowOf(context).nextStartedActivity)
        assertEquals("https://dict.cn/%E4%B8%AD%20%E6%96%87%2F%23", readerDictionaryUrl("中 文/#").toString())
        val picker = readerTranslationPickerIntent(context)!!
        assertEquals(Intent.ACTION_PICK_ACTIVITY, picker.action)
        val query = picker.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals("com.eyue.reader.PICK_TRANSLATOR", query.action)
        assertNull(query.component)
        val selected = Intent(query).setComponent(android.content.ComponentName("test.second", "Translator"))
        rememberReaderTranslator(context, android.app.Activity.RESULT_CANCELED, selected)
        assertNull(context.getSharedPreferences("reader_translation", 0).getString("component", null))
        rememberReaderTranslator(context, android.app.Activity.RESULT_OK, Intent().putExtra(Intent.EXTRA_SHORTCUT_NAME, "翻译-second"))
        assertEquals("翻译-second", selectedReaderTranslatorLabel(context))
        assertTrue(launchSelectedReaderTranslator(context, "再次选字"))
        assertEquals("test.second", shadowOf(context).nextStartedActivity.component!!.packageName)
        useReaderOnlineDictionary(context)
        assertEquals("海词在线词典", selectedReaderTranslatorLabel(context))
    }

    @Test fun selectedProcessTextTranslatorKeepsTheReadOnlyText() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("reader_translation", 0).edit().clear().commit()
        val action = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
        for (name in listOf("SystemTranslatorA", "SystemTranslatorB", "Browser")) {
            shadowOf(context.packageManager).addResolveInfoForIntent(action, ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = "test.$name"; this.name = name; exported = true
                    applicationInfo = ApplicationInfo().apply { flags = ApplicationInfo.FLAG_SYSTEM }
                }
            })
        }
        rememberReaderTranslator(context, android.app.Activity.RESULT_OK,
            Intent().setClassName("test.SystemTranslatorB", "SystemTranslatorB"))
        assertTrue(launchSelectedReaderTranslator(context, "文字"))
        val target = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_PROCESS_TEXT, target.action)
        assertEquals("test.SystemTranslatorB", target.component!!.packageName)
        assertEquals("文字", target.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT).toString())
        assertTrue(target.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false))
    }

    @Test fun dictionaryExtractsBothDefinitionFormatsAndIgnoresEmptyResults() {
        assertEquals("n. 书 & 卷", readerDictionaryEntry("book", """
            <ul class="dict-basic-ul"><li><span>n.</span> <strong>书 &amp; 卷</strong></li>
            <li><script>advertising()</script></li></ul>
        """.trimIndent()).definition)
        assertEquals("hello\nhi", readerDictionaryEntry("你好", """
            <div class="layout cn"><ul><li><a href="/hello">hello</a></li><li><a href="/hi">hi</a></li></ul></div>
        """.trimIndent()).definition)
        assertEquals("早上好", readerDictionaryEntry("good morning", """
            <div class="basic clearfix"><ul><li><strong>早上好</strong></li></ul></div>
        """.trimIndent()).definition)
        assertEquals(ReaderDictionaryEntry(""), readerDictionaryEntry("无", "<title>该词条未找到</title>"))
    }

    @Test fun singleHanCharacterShowsEveryPronunciationFromTheHeadword() {
        val phonetics = """<div class="phonetic"><span>[hàng, xíng, háng, héng]</span></div>"""
        assertEquals("hàng、xíng、háng、héng", readerDictionaryEntry(" 行 ", phonetics).pronunciation)
        assertEquals("", readerDictionaryEntry("行走", phonetics).pronunciation)
        assertEquals("", readerDictionaryEntry("a", phonetics).pronunciation)
        assertEquals("shēng", readerDictionaryEntry("生", """<div class="phonetic"><span>[shēng]</span></div>""").pronunciation)
    }

    @Test fun nativePickerContainsOnlyCallableTranslatorsWithMatchingIcons() {
        val context = RuntimeEnvironment.getApplication()
        val process = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
        for (name in listOf("SystemTranslator", "Browser")) {
            val info = ResolveInfo().apply { activityInfo = ActivityInfo().apply {
                packageName = "eligible.$name"; this.name = name; exported = true
                nonLocalizedLabel = name
                applicationInfo = ApplicationInfo().apply { flags = ApplicationInfo.FLAG_SYSTEM }
            } }
            shadowOf(context.packageManager).addOrUpdateActivity(info.activityInfo)
            shadowOf(context.packageManager).addResolveInfoForIntent(process, info)
        }
        val picker = readerTranslationPickerIntent(context)!!
        assertEquals(listOf("SystemTranslator"), picker.getStringArrayListExtra(Intent.EXTRA_SHORTCUT_NAME))
        assertEquals(1, picker.getParcelableArrayListExtra<Intent.ShortcutIconResource>(Intent.EXTRA_SHORTCUT_ICON_RESOURCE)!!.size)
    }
    @Test fun translatorNamesUseTheApplicationLabelWithoutWebAddressesOrComponentSuffixes() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("reader_translation", 0).edit().clear().commit()
        for (name in listOf("First", "Second")) {
            val resolver = ResolveInfo().apply { activityInfo = ActivityInfo().apply {
                packageName = "translator.$name"; this.name = "Translate"; exported = true
                nonLocalizedLabel = "翻译 https://example.invalid/translate"
                applicationInfo = ApplicationInfo().apply { nonLocalizedLabel = "系统翻译" }
            } }
            shadowOf(context.packageManager).addOrUpdateActivity(resolver.activityInfo)
            shadowOf(context.packageManager).addResolveInfoForIntent(readerTranslationIntent(""), resolver)
        }
        val labels = readerTranslationPickerIntent(context)!!.getStringArrayListExtra(Intent.EXTRA_SHORTCUT_NAME)!!
        assertEquals(listOf("系统翻译（1）", "系统翻译（2）"), labels)
        rememberReaderTranslator(context, android.app.Activity.RESULT_OK, Intent().putExtra(Intent.EXTRA_SHORTCUT_NAME, labels[1]))
        assertEquals("系统翻译", selectedReaderTranslatorLabel(context))
    }

    @Test fun processTextFallbackExcludesBrowsersAndUsesOnlyTheSystemTranslator() {
        val context = RuntimeEnvironment.getApplication()
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
        val browser = ResolveInfo().apply { activityInfo = ActivityInfo().apply {
            packageName = "test.browser"; name = "Browser"; exported = true
            applicationInfo = ApplicationInfo().apply { flags = ApplicationInfo.FLAG_SYSTEM }
        } }
        val translator = ResolveInfo().apply { activityInfo = ActivityInfo().apply {
            packageName = "test.system.translate"; name = "SystemTranslator"; exported = true
            applicationInfo = ApplicationInfo().apply { flags = ApplicationInfo.FLAG_SYSTEM }
        } }
        shadowOf(context.packageManager).addResolveInfoForIntent(intent, browser)
        shadowOf(context.packageManager).addResolveInfoForIntent(intent, translator)
        val requests = readerTranslationTargets(context, "原文")
        assertEquals(1, requests.size)
        assertEquals("test.system.translate", requests.single().component!!.packageName)
        assertEquals("原文", requests.single().getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT).toString())
    }
}
