package com.eyue.reader

import android.app.Activity
import android.view.Display
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], manifest = Config.NONE)
class ReaderRefreshRateRobolectricTest {
    @Test fun supported120HzIsRequestedWithoutForcingADisplayModeOrChangingWindowFlags() {
        withWindow { activity, display ->
            val current = display.mode
            ShadowDisplayManager.setSupportedModes(display.displayId, current,
                mode(2, current.physicalWidth, current.physicalHeight, 120f),
                mode(3, current.physicalWidth, current.physicalHeight, 144f))
            val flags = activity.window.attributes.flags
            requestReaderRefreshRate(activity.window, display, einkMode = false)
            assertEquals(120f, activity.window.attributes.preferredRefreshRate, 0f)
            assertEquals(0, activity.window.attributes.preferredDisplayModeId)
            assertEquals(flags, activity.window.attributes.flags)
        }
    }

    @Test fun higherRefreshAtAnotherResolutionIsNotRequested() {
        withWindow { activity, display ->
            val current = display.mode
            ShadowDisplayManager.setSupportedModes(display.displayId,
                mode(current.modeId, current.physicalWidth, current.physicalHeight, 60f),
                mode(2, current.physicalWidth * 2, current.physicalHeight * 2, 120f),
                mode(3, current.physicalWidth, current.physicalHeight, 90f))
            requestReaderRefreshRate(activity.window, display, einkMode = false)
            assertEquals(90f, activity.window.attributes.preferredRefreshRate, 0f)
        }
    }

    @Test fun a60And144HzPanelUsesItsSupportedHighRefreshTier() {
        withWindow { activity, display ->
            val current = display.mode
            ShadowDisplayManager.setSupportedModes(display.displayId,
                mode(current.modeId, current.physicalWidth, current.physicalHeight, 60f),
                mode(2, current.physicalWidth, current.physicalHeight, 144f))
            requestReaderRefreshRate(activity.window, display, einkMode = false)
            assertEquals(144f, activity.window.attributes.preferredRefreshRate, 0f)
        }
    }

    @Test fun nominal120HzTierIsPreferredOverHigherTiersWithoutInventingAnUnsupportedRate() {
        withWindow { activity, display ->
            val current = display.mode
            ShadowDisplayManager.setSupportedModes(display.displayId,
                mode(current.modeId, current.physicalWidth, current.physicalHeight, 60f),
                mode(2, current.physicalWidth, current.physicalHeight, 119.88f),
                mode(3, current.physicalWidth, current.physicalHeight, 144f))
            requestReaderRefreshRate(activity.window, display, einkMode = false)
            assertEquals(119.88f, activity.window.attributes.preferredRefreshRate, 0f)
        }
    }

    @Test fun a60HzPanelKeepsItsOnlySupportedRate() {
        withWindow { activity, display ->
            val current = display.mode
            ShadowDisplayManager.setSupportedModes(display.displayId,
                mode(current.modeId, current.physicalWidth, current.physicalHeight, 60f))
            requestReaderRefreshRate(activity.window, display, einkMode = false)
            assertEquals(60f, activity.window.attributes.preferredRefreshRate, 0f)
        }
    }

    @Test fun einkAndMissingDisplayClearTheHighRefreshPreference() {
        withWindow { activity, display ->
            val current = display.mode
            ShadowDisplayManager.setSupportedModes(display.displayId, current,
                mode(2, current.physicalWidth, current.physicalHeight, 120f))
            requestReaderRefreshRate(activity.window, display, einkMode = false)
            requestReaderRefreshRate(activity.window, display, einkMode = true)
            assertEquals(0f, activity.window.attributes.preferredRefreshRate, 0f)
            requestReaderRefreshRate(activity.window, display, einkMode = false)
            requestReaderRefreshRate(activity.window, null, einkMode = false)
            assertEquals(0f, activity.window.attributes.preferredRefreshRate, 0f)
        }
    }

    private fun withWindow(block: (Activity, Display) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            @Suppress("DEPRECATION")
            val display = activity.windowManager.defaultDisplay
            block(activity, display)
        } finally { controller.pause().stop().destroy() }
    }

    private fun mode(id: Int, width: Int, height: Int, rate: Float): Display.Mode =
        ReflectionHelpers.callConstructor(Display.Mode::class.java,
            ClassParameter.from(Int::class.javaPrimitiveType!!, id),
            ClassParameter.from(Int::class.javaPrimitiveType!!, width),
            ClassParameter.from(Int::class.javaPrimitiveType!!, height),
            ClassParameter.from(Float::class.javaPrimitiveType!!, rate))
}
