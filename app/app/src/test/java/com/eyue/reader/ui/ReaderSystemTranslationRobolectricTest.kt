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
        openReaderTranslation(context, "原文")
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
    @Test fun unavailableTranslationShowsAnExplanationRatherThanCrashing() {
        openReaderTranslation(RuntimeEnvironment.getApplication(), "文字")
        assertEquals("此设备未提供可调用的系统翻译", ShadowToast.getTextOfLatestToast())
    }

    @Test fun multipleTranslatorsUseTheNativeResolverAndTheNativePickerRemembersTheSelectedApp() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("reader_translation", 0).edit().clear().commit()
        for (name in listOf("first", "second")) {
            val resolver = ResolveInfo().apply { activityInfo = ActivityInfo().apply {
                packageName = "test.$name"; this.name = "Translator"; exported = true
            } }
            shadowOf(context.packageManager).addResolveInfoForIntent(readerTranslationIntent("文字"), resolver)
        }
        openReaderTranslation(context, "文字")
        val nativeResolver = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_TRANSLATE, nativeResolver.action)
        assertNull(nativeResolver.component)
        assertEquals("文字", nativeResolver.getCharSequenceExtra(Intent.EXTRA_TEXT).toString())
        val picker = readerTranslationPickerIntent(context)!!
        assertEquals(Intent.ACTION_PICK_ACTIVITY, picker.action)
        val query = picker.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_TRANSLATE, query.action)
        assertNull(query.component)
        val selected = Intent(query).setComponent(android.content.ComponentName("test.second", "Translator"))
        rememberReaderTranslator(context, android.app.Activity.RESULT_CANCELED, selected)
        assertNull(context.getSharedPreferences("reader_translation", 0).getString("component", null))
        rememberReaderTranslator(context, android.app.Activity.RESULT_OK, selected)
        openReaderTranslation(context, "再次选字")
        assertEquals("test.second", shadowOf(context).nextStartedActivity.component!!.packageName)
    }

    @Test fun processTextNativeChooserExcludesOtherProcessorsAndKeepsTheReadOnlyText() {
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
        openReaderTranslation(context, "文字")
        val chooser = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val target = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_PROCESS_TEXT, target.action)
        assertEquals("文字", target.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT).toString())
        assertTrue(target.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false))
        assertEquals(listOf("test.Browser"), chooser.getParcelableArrayExtra(Intent.EXTRA_EXCLUDE_COMPONENTS)!!
            .map { (it as android.content.ComponentName).packageName })
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
