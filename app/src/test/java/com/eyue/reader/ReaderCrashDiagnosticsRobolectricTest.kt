package com.eyue.reader

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.DialogInterface
import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class ReaderCrashDiagnosticsRobolectricTest {
    private lateinit var root: File
    private lateinit var context: Context
    private var controller: ActivityController<DiagnosticsActivity>? = null

    @Before fun setUp() {
        val base = RuntimeEnvironment.getApplication()
        root = File(base.cacheDir, "crash-diagnostics-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
    }

    @After fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        root.deleteRecursively()
    }

    @Test fun savesDeviceThreadCauseAndFramesAndDelegatesTheOriginalObjects() {
        val thread = Thread("reader-failure-test")
        val cause = IllegalArgumentException("source failure").apply {
            stackTrace = arrayOf(StackTraceElement("reader.Source", "load", "Source.kt", 17))
        }
        val error = IllegalStateException("reader failure", cause).apply {
            stackTrace = arrayOf(StackTraceElement("reader.Screen", "open", "Screen.kt", 29))
        }
        val log = File(context.filesDir, "diagnostics/last-crash.txt")
        var delegated = 0
        val previous = Thread.UncaughtExceptionHandler { receivedThread, receivedError ->
            delegated++
            assertSame(thread, receivedThread)
            assertSame(error, receivedError)
        }
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        ReaderCrashHandler(log, "E阅 test | Test Device | Android 9 / API 28", previous)
            .uncaughtException(thread, error)
        val report = log.readText()
        assertTrue(report.startsWith("E阅 test | Test Device | Android 9 / API 28\n"))
        assertTrue(report.contains("Thread: reader-failure-test"))
        assertTrue(report.contains("java.lang.IllegalStateException: reader failure"))
        assertTrue(report.contains("reader.Screen.open(Screen.kt:29)"))
        assertTrue(report.contains("Caused by:\njava.lang.IllegalArgumentException: source failure"))
        assertTrue(report.contains("reader.Source.load(Source.kt:17)"))
        assertEquals(1, delegated)
        assertSame(defaultHandler, Thread.getDefaultUncaughtExceptionHandler())
    }

    @Test fun failedLogWriteStillDelegatesWithoutChangingTheBlockingFile() {
        val blocker = File(context.filesDir, "not-a-directory").apply { writeText("keep this file") }
        val thread = Thread("failed-diagnostic-write")
        val error = IllegalStateException("original failure")
        var delegated = 0
        val previous = Thread.UncaughtExceptionHandler { receivedThread, receivedError ->
            delegated++
            assertSame(thread, receivedThread)
            assertSame(error, receivedError)
        }
        ReaderCrashHandler(File(blocker, "crash.txt"), "device", previous).uncaughtException(thread, error)
        assertEquals(1, delegated)
        assertEquals("keep this file", blocker.readText())
    }

    @Test fun longReportsAreCappedAtThirtyTwoKCharacters() {
        val log = File(context.filesDir, "diagnostics/long-crash.txt")
        val error = IllegalStateException("failure").apply {
            stackTrace = Array(64) { index ->
                StackTraceElement("VeryLongClass".repeat(150), "method$index", "Screen.kt", index + 1)
            }
        }
        var delegated = false
        ReaderCrashHandler(log, "device", Thread.UncaughtExceptionHandler { _, _ -> delegated = true })
            .uncaughtException(Thread("long-report"), error)
        assertTrue(delegated)
        assertEquals(32_768, log.readText().length)
        assertTrue(log.readText().startsWith("device\nThread: long-report\n"))
    }

    @Test fun preservesVerifierDetailFollowingLongRepeatedMethodSignatures() {
        val log = File(context.filesDir, "diagnostics/verifier-crash.txt")
        val detail = "register v330 has type Reference but expected Float"
        val message = "Verifier rejected ReaderScreen: " + "ReaderScreen(Book, ReaderSettings, Composer) ".repeat(40) + detail
        ReaderCrashHandler(log, "device", Thread.UncaughtExceptionHandler { _, _ -> })
            .uncaughtException(Thread("main"), VerifyError(message))
        assertTrue(log.readText().contains(detail))
    }

    @Test fun pendingDialogCopiesTheReportAndClearsItAfterDismissal() {
        val activityController = Robolectric.buildActivity(DiagnosticsActivity::class.java)
        controller = activityController
        val activity = activityController.get().apply { isolatedFiles = context.filesDir }
        activityController.setup()
        val log = File(activity.filesDir, "diagnostics/last-crash.txt").apply {
            parentFile!!.mkdirs()
            writeText("device information\njava.lang.IllegalStateException: reader failure")
        }
        val report = log.readText()
        ReaderCrashDiagnostics.showPending(activity)
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.isShowing)
        assertEquals("上次异常退出", Shadows.shadowOf(dialog).title.toString())
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val clipboard = activity.getSystemService(ClipboardManager::class.java)
        assertEquals(report, clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertFalse(dialog.isShowing)
        assertFalse(log.exists())
        ReaderCrashDiagnostics.showPending(activity)
        assertSame(dialog, ShadowAlertDialog.getLatestAlertDialog())
    }

    class DiagnosticsActivity : Activity() {
        var isolatedFiles: File? = null
        override fun getFilesDir(): File = isolatedFiles ?: super.getFilesDir()
    }
}
