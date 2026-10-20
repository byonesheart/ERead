package com.eyue.reader.ui

import android.content.Context
import android.view.KeyEvent
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.eyue.reader.MainActivity
import com.eyue.reader.data.ConsentStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConsentGateRobolectricTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before fun clearConsent() {
        context.getSharedPreferences("legal-consent", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun policyReadingDoesNotInitializeReaderAndAcceptRequiresExplicitCheck() {
        var enters = 0
        var exits = 0
        compose.setContent { ConsentGate({ exits++ }) { SideEffect { enters++ }; Text("书架内容") } }
        compose.onNodeWithTag("consent_accept").assertIsNotEnabled()
        compose.onNodeWithText("书架内容").assertDoesNotExist()
        compose.onNodeWithTag("consent_privacy").performClick()
        compose.onNodeWithTag("legal_document_privacy").assertExists()
        compose.runOnIdle { assertEquals(0, enters); assertFalse(ConsentStore(context).isAccepted()) }
        compose.onNodeWithTag("consent_document_back").performClick()
        compose.onNodeWithTag("consent_terms").performClick()
        compose.onNodeWithTag("legal_document_terms").assertExists()
        compose.onNodeWithTag("consent_document_back").performClick()
        compose.onNodeWithTag("consent_checkbox").performClick()
        compose.onNodeWithTag("consent_accept").performClick()
        compose.onNodeWithText("书架内容").assertIsDisplayed()
        compose.runOnIdle { assertTrue(enters > 0); assertEquals(0, exits); assertTrue(ConsentStore(context).isAccepted()); assertTrue(ConsentStore(context).acceptedAt() > 0) }
    }

    @Test fun decliningDoesNotSaveConsentOrEnterLibrary() {
        var enters = 0
        var exits = 0
        compose.setContent { ConsentGate({ exits++ }) { SideEffect { enters++ } } }
        compose.onNodeWithTag("consent_exit").performClick()
        compose.runOnIdle { assertEquals(1, exits); assertEquals(0, enters); assertFalse(ConsentStore(context).isAccepted()) }
    }

    @Test fun acceptedVersionsAreRequiredAndTheTimestampPersists() {
        val store = ConsentStore(context)
        assertTrue(store.accept(12345L))
        assertTrue(ConsentStore(context).isAccepted())
        assertEquals(12345L, store.acceptedAt())
        context.getSharedPreferences("legal-consent", Context.MODE_PRIVATE).edit().putString("privacy_version", "old-version").commit()
        assertFalse(ConsentStore(context).isAccepted())
        assertTrue(store.accept(54321L))
        context.getSharedPreferences("legal-consent", Context.MODE_PRIVATE).edit().putString("terms_version", "old-version").commit()
        assertFalse(ConsentStore(context).isAccepted())
    }

    @Test fun activityLifecycleAndVolumeKeysDoNotCreateViewModelBeforeConsent() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val field = MainActivity::class.java.getDeclaredField("reader\$delegate").apply { isAccessible = true }
            val lazyReader = field.get(activity) as Lazy<*>
            assertFalse(lazyReader.isInitialized())
            activity.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN))
            activity.onKeyUp(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_DOWN))
            controller.pause().stop()
            assertFalse(lazyReader.isInitialized())
        } finally { controller.destroy() }
    }
}
