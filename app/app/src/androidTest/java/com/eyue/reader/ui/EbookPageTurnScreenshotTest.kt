package com.eyue.reader.ui

import android.animation.ValueAnimator
import android.graphics.Color
import android.os.SystemClock
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eyue.reader.data.PageTurnStyle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Uses Chromium and the displayed Android surface, rather than a substitute Canvas renderer. */
@RunWith(AndroidJUnit4::class)
class EbookPageTurnScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var web: EbookPageTurnWebView
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @After fun cleanup() {
        if (::web.isInitialized) instrumentation.runOnMainSync { web.finishTurn() }
    }

    @Test fun everyHorizontalAnimationRetainsTheScrolledViewportUntilTheNewPageIsRevealed() {
        val loaded = AtomicBoolean()
        compose.setContent {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                EbookPageTurnWebView(context).also { view ->
                    web = view
                    view.settings.javaScriptEnabled = true
                    view.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) { loaded.set(true) }
                    }
                    view.loadDataWithBaseURL("https://eread.local/page-turn-test/", HTML, "text/html", "UTF-8", null)
                }
            }, onRelease = { it.finishTurn(); it.destroy() })
        }
        compose.waitUntil(20_000) { loaded.get() }
        for (style in PageTurnStyle.entries) {
            javascript("ERead.command({page:1}); window.scrollX")
            compose.waitUntil(10_000) { javascript("window.scrollX").toFloatOrNull()?.let { it > 0 } == true }
            assertViewportColor("$style starts from the second, green page", Color.GREEN, .25f)
            instrumentation.runOnMainSync { web.turnPage(2, 1, style) }
            val animator = AtomicReference<ValueAnimator?>()
            compose.waitUntil(10_000) {
                instrumentation.runOnMainSync {
                    (TURN.get(web) as? ValueAnimator)?.let { running ->
                        running.pause()
                        running.setCurrentFraction(0f)
                        animator.set(running)
                    }
                }
                animator.get() != null
            }
            assertViewportColor("$style snapshot must contain the scrolled green page", Color.GREEN, .25f)
            instrumentation.runOnMainSync { requireNotNull(animator.get()).setCurrentFraction(.35f) }
            assertViewportColor("$style retains the left portion of that same viewport", Color.GREEN, .15f)
            instrumentation.runOnMainSync { web.finishTurn() }
            assertViewportColor("$style finishes on the third, blue page", Color.BLUE, .5f)
        }
    }

    private fun javascript(script: String): String {
        val result = AtomicReference<String?>()
        instrumentation.runOnMainSync { web.evaluateJavascript(script) { result.set(it) } }
        compose.waitUntil(10_000) { result.get() != null }
        return requireNotNull(result.get())
    }

    private fun assertViewportColor(message: String, expected: Int, relativeX: Float) {
        // Allow Chromium's compositor to publish the requested frame before reading the real display.
        instrumentation.waitForIdleSync()
        SystemClock.sleep(100)
        val coordinates = IntArray(2)
        var width = 0
        var height = 0
        instrumentation.runOnMainSync {
            web.getLocationOnScreen(coordinates)
            width = web.width
            height = web.height
        }
        assertTrue("A real WebView viewport must be attached", width > 0 && height > 0)
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val pixel = screenshot.getPixel(coordinates[0] + (width * relativeX).toInt(), coordinates[1] + height / 2)
            assertEquals(message, expected, pixel)
        } finally { screenshot.recycle() }
    }

    companion object {
        // Freezes a real running animation for deterministic screenshot sampling; no renderer is replaced.
        private val TURN = EbookPageTurnWebView::class.java.getDeclaredField("turn").apply { isAccessible = true }
        private val HTML = """<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1"/>
            <style>html,body{margin:0;height:100%;overflow-y:hidden}body{display:flex;width:300vw}
            div{width:100vw;height:100vh;flex-shrink:0}#first{background:#f00}#second{background:#0f0}#third{background:#00f}</style>
            </head><body><div id="first"></div><div id="second"></div><div id="third"></div>
            <script>window.ERead={command:function(command){window.scrollTo(command.page*window.innerWidth,0)}};</script></body></html>"""
    }
}
