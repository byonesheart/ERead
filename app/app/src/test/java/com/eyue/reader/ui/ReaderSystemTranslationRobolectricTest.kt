package com.eyue.reader.ui

import android.content.Intent
import android.content.pm.ActivityInfo
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
    @Test fun availableSystemProcessorReceivesTheSelectedTextThroughAChooser() {
        val context = RuntimeEnvironment.getApplication()
        val resolver = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = "test.translate"; name = "Translator"; exported = true }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(readerTranslationIntent("原文"), resolver)
        openReaderTranslation(context, "原文")
        val chooser = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val request = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals("原文", request.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT).toString())
        assertTrue(request.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false))
    }
    @Test fun selectionUsesTheSystemReadOnlyTextProcessingProtocol() {
        val text = "选中的原文，含 punctuation。"
        val intent = readerTranslationIntent(text)
        assertEquals(Intent.ACTION_PROCESS_TEXT, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals(text, intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT).toString())
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false))
    }
    @Test fun unavailableTranslationShowsAnExplanationRatherThanCrashing() {
        openReaderTranslation(RuntimeEnvironment.getApplication(), "文字")
        assertEquals("未找到支持选中文字的翻译应用", ShadowToast.getTextOfLatestToast())
    }
}
