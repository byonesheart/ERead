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

    @Test fun multipleTranslatorsOfferARememberedDefaultAndSettingsCanChangeIt() {
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        activity.getSharedPreferences("reader_translation", 0).edit().clear().commit()
        for (name in listOf("first", "second")) {
            val resolver = ResolveInfo().apply { activityInfo = ActivityInfo().apply {
                packageName = "test.$name"; this.name = "Translator"; exported = true
            } }
            shadowOf(activity.packageManager).addResolveInfoForIntent(readerTranslationIntent("文字"), resolver)
        }
        openReaderTranslation(activity, "文字")
        val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.isShowing)
        dialog.listView.performItemClick(null, 1, 1)
        fun checkbox(view: android.view.View): android.widget.CheckBox? = when (view) {
            is android.widget.CheckBox -> view
            is android.view.ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { checkbox(view.getChildAt(it)) }
            else -> null
        }
        checkbox(dialog.window!!.decorView)!!.isChecked = true
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("test.second", shadowOf(activity).nextStartedActivity.component!!.packageName)
        openReaderTranslation(activity, "再次选字")
        assertEquals("test.second", shadowOf(activity).nextStartedActivity.component!!.packageName)
        openReaderTranslationSettings(activity)
        assertTrue(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().isShowing)
        activity.finish()
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
