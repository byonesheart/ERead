package com.eyue.reader.ui

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.webkit.ValueCallback
import android.webkit.WebView
import android.os.Looper
import android.os.Handler
import com.eyue.reader.data.PageTurnStyle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class EbookPageTurnRobolectricTest {
    private class Browser : EbookPageTurnWebView(RuntimeEnvironment.getApplication()) {
        var color = Color.RED
        val commits = mutableListOf<WebView.VisualStateCallback>()
        fun sizeForTest() { layout(0, 0, 120, 180); right = 120; bottom = 180; assertEquals(120, width); assertEquals(180, height) }
        override fun drawWebContent(canvas: Canvas) { canvas.drawColor(color) }
        override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
            color = Color.BLUE
            resultCallback?.onReceiveValue("null")
        }
        override fun postVisualStateCallback(requestId: Long, callback: WebView.VisualStateCallback) { commits += callback }
        // These isolated pixel tests have no window; drive the attached-view frame queue explicitly.
        override fun postOnAnimation(action: Runnable) { Handler(Looper.getMainLooper()).postDelayed(action, 16) }
        fun commit() = commits.removeAt(0).onComplete(0)
        fun advance(fraction: Float) {
            val animator = EbookPageTurnWebView::class.java.getDeclaredField("turn").apply { isAccessible = true }.get(this) as ValueAnimator
            animator.setCurrentFraction(fraction)
            if (fraction >= 1f) animator.end()
        }
        fun frame(): Bitmap = Bitmap.createBitmap(120, 180, Bitmap.Config.ARGB_8888).also { draw(Canvas(it)) }
    }

    @Test fun coverAndCurlNeverDrawAStaleChromiumPageDuringTheTransition() {
        for (style in listOf(PageTurnStyle.COVER, PageTurnStyle.CURL)) for (direction in listOf(1, -1)) {
            val browser = Browser().apply { sizeForTest() }
            try {
                browser.turnPage(1, direction, style)
                browser.frame().also { assertEquals(Color.RED, it.getPixel(60, 90)); it.recycle() }
                browser.commit()
                // Chromium may hand back its previous hardware frame. Animation must use the captured target.
                browser.color = Color.GREEN
                browser.advance(.5f)
                browser.frame().also { frame ->
                    val colors = IntArray(120 * 180).also { frame.getPixels(it, 0, 120, 0, 0, 120, 180) }.toSet()
                    assertFalse("$style/$direction exposed a live stale page", Color.GREEN in colors)
                    assertTrue("Target page must be revealed", Color.BLUE in colors)
                    frame.recycle()
                }
                browser.advance(1f)
                browser.frame().also { assertEquals(Color.BLUE, it.getPixel(60, 90)); it.recycle() }
                browser.color = Color.BLUE
                browser.commit()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
                browser.frame().also { assertEquals(Color.BLUE, it.getPixel(60, 90)); it.recycle() }
                assertNull(EbookPageTurnWebView::class.java.getDeclaredField("targetSnapshot").apply { isAccessible = true }.get(browser))
            } finally { browser.finishTurn(); browser.destroy() }
        }
    }

    @Test fun cancellingATurnRejectsItsLateCommitAndReleasesBothSnapshots() {
        val browser = Browser().apply { sizeForTest() }
        try {
            browser.turnPage(1, 1, PageTurnStyle.COVER)
            browser.finishTurn()
            browser.color = Color.YELLOW
            browser.commit()
            browser.frame().also { assertEquals(Color.YELLOW, it.getPixel(60, 90)); it.recycle() }
            for (name in listOf("snapshot", "targetSnapshot"))
                assertNull(EbookPageTurnWebView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(browser))
            browser.turnPage(2, 1, PageTurnStyle.CURL)
            browser.commit()
            val bitmaps = listOf("snapshot", "targetSnapshot").map { name ->
                EbookPageTurnWebView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(browser) as Bitmap
            }
            browser.finishTurn()
            assertTrue(bitmaps.all { it.isRecycled })
        } finally { browser.finishTurn(); browser.destroy() }
    }
}
