package com.eyue.reader.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.eyue.reader.data.PageTurnStyle
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.util.concurrent.atomic.AtomicReference
import java.io.File
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderPageTurnRobolectricTest {
    @get:Rule val compose = createComposeRule()

    @Test fun curlRevealsTheNextPageWhileKeepingTheUnturnedPartOfTheOldPage() = assertSheetTurns(PageTurnStyle.CURL)
    @Test fun coverKeepsTheNextSheetStationaryAndSupportsRepeatedForwardAndBackTurns() = assertSheetTurns(PageTurnStyle.COVER)
    @Test fun slideMovesBothSheetsAndSupportsRepeatedForwardAndBackTurns() = assertSheetTurns(PageTurnStyle.SLIDE)

    private fun assertSheetTurns(style: PageTurnStyle) {
        val anchor = mutableStateOf(ReaderPageLocation(0, 0))
        val view = AtomicReference<View>()
        compose.setContent {
            view.set(LocalView.current)
            MaterialTheme { Box(Modifier.size(300.dp, 400.dp)) {
                ReaderPageTurnFrame(anchor.value, false, listOf(4), style, false, false, Color.White) { _, page, _ ->
                    Box(Modifier.fillMaxSize().background(pageColor(page)).testTag("paper_$page"))
                }
            } }
        }
        compose.waitForIdle()
        val initial = captureDrawnPage(view.get(), style).asImageBitmap().toPixelMap()
        assertTrue("Initial host drawing must contain the red page: ${initial[5, initial.height / 2]}", initial[5, initial.height / 2].red > .8f)
        compose.mainClock.autoAdvance = false
        var previous = 0
        for (target in listOf(1, 2, 1)) {
            compose.runOnIdle { anchor.value = ReaderPageLocation(0, target); Snapshot.sendApplyNotifications() }
            compose.mainClock.advanceTimeBy(if (style == PageTurnStyle.CURL) 192 else 96)
            val drawn = captureDrawnPage(view.get(), style)
            val image = drawn.asImageBitmap().toPixelMap()
            val left = image[5, image.height / 2]
            val right = image[image.width - 6, image.height / 2]
            val expectedLeft = pageColor(if (target > previous) previous else target)
            val expectedRight = pageColor(if (target > previous) target else previous)
            if (previous == 0 && style == PageTurnStyle.CURL || !sameColor(left, expectedLeft) || !sameColor(right, expectedRight))
                File("build/outputs/reader-${style.name.lowercase()}-preview.png").also { file ->
                file.parentFile?.mkdirs(); file.outputStream().use { drawn.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            assertTrue("The left paper must remain visible in $style for $previous -> $target: $left", sameColor(left, expectedLeft))
            assertTrue("The right paper must be visible in $style for $previous -> $target: $right", sameColor(right, expectedRight))
            if (target > previous && style != PageTurnStyle.CURL) {
                val enteringLeft = compose.onNodeWithTag("paper_$target").fetchSemanticsNode().boundsInRoot.left
                if (style == PageTurnStyle.SLIDE) assertTrue("Sliding must move the incoming sheet too", enteringLeft > 1f)
                else assertEquals("Covering must uncover a stationary incoming sheet", 0f, enteringLeft, .5f)
            }
            compose.mainClock.advanceTimeBy(500)
            val complete = captureDrawnPage(view.get(), style).asImageBitmap().toPixelMap()
            assertTrue("The fold must end with exactly the selected page", sameColor(complete[5, complete.height / 2], pageColor(target)))
            previous = target
        }
    }

    @Test fun dualSpreadShowsBothPagesAndNarrationFocusChangesDoNotReplaceTheSpread() {
        val anchor = mutableStateOf(ReaderPageLocation(1, 2))
        compose.setContent {
            MaterialTheme { Box(Modifier.size(600.dp, 400.dp)) {
                ReaderPageTurnFrame(anchor.value, true, listOf(3, 5), PageTurnStyle.SLIDE, false, false, Color.White) { chapter, page, _ ->
                    Text("$chapter:$page", Modifier.testTag("pane_$chapter:$page"))
                }
            } }
        }
        compose.onNodeWithTag("reader_dual_page").assertExists()
        compose.onNodeWithTag("pane_1:2").assertIsDisplayed()
        compose.onNodeWithTag("pane_1:3").assertIsDisplayed()
        compose.runOnIdle { anchor.value = ReaderPageLocation(1, readerSpreadStart(3, true)) }
        compose.onNodeWithTag("pane_1:2").assertIsDisplayed()
        compose.runOnIdle { anchor.value = ReaderPageLocation(1, 4) }
        compose.waitForIdle()
        compose.onNodeWithTag("pane_1:4").assertIsDisplayed()
        compose.onNodeWithTag("pane_1:5").assertDoesNotExist()
    }

    @Test fun einkSwitchesImmediatelyWithoutCreatingAnimatedOutgoingPages() {
        val anchor = mutableStateOf(ReaderPageLocation(0, 0))
        compose.setContent {
            MaterialTheme { ReaderPageTurnFrame(anchor.value, false, listOf(2), PageTurnStyle.CURL, false, true, Color.White) { _, page, interactive ->
                assertTrue(interactive)
                Text("page:$page")
            } }
        }
        compose.onNodeWithText("page:0").assertExists()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { anchor.value = ReaderPageLocation(0, 1); Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithText("page:1").assertExists()
        compose.onNodeWithText("page:0").assertDoesNotExist()
        compose.onNodeWithTag("reader_animation_curl").assertDoesNotExist()
    }

    @Test fun verticalPagingNeverAppliesHorizontalCoverSlideOrCurlEffects() {
        val anchor = mutableStateOf(ReaderPageLocation(0, 0))
        compose.setContent {
            MaterialTheme { ReaderPageTurnFrame(anchor.value, false, listOf(2), PageTurnStyle.CURL, true, false, Color.White) { _, page, interactive ->
                assertTrue(interactive)
                Text("vertical:$page")
            } }
        }
        compose.onNodeWithText("vertical:0").assertExists()
        compose.runOnIdle { anchor.value = ReaderPageLocation(0, 1) }
        compose.onNodeWithText("vertical:1").assertExists()
        compose.onNodeWithText("vertical:0").assertDoesNotExist()
        compose.onNodeWithTag("reader_animation_curl").assertDoesNotExist()
    }

    /** PixelCopy waits for a real vsync, which a paused Robolectric looper does not produce.
     * Drawing the actual Compose host into a native Canvas keeps the fold's clipping/gradient pixel assertions. */
    private fun captureDrawnPage(view: View, style: PageTurnStyle): Bitmap {
        val bounds = compose.onNodeWithTag("reader_animation_${style.name.lowercase()}").fetchSemanticsNode().boundsInRoot
        return compose.runOnIdle {
            val full = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(full))
            Bitmap.createBitmap(full, bounds.left.roundToInt(), bounds.top.roundToInt(), bounds.width.roundToInt(), bounds.height.roundToInt())
                .also { if (it !== full) full.recycle() }
        }
    }

    private fun pageColor(page: Int) = when (page) { 0 -> Color.Red; 1 -> Color.Blue; else -> Color.Green }
    private fun sameColor(actual: Color, expected: Color) = kotlin.math.abs(actual.red - expected.red) < .1f &&
        kotlin.math.abs(actual.green - expected.green) < .1f && kotlin.math.abs(actual.blue - expected.blue) < .1f
}
