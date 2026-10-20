package com.eyue.reader

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.eyue.reader.data.ConsentStore
import org.junit.rules.ExternalResource

/** Reader integration tests grant consent before ActivityScenario launches; the production gate is unchanged. */
internal class ConsentGrantRule : ExternalResource() {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var previous = emptyMap<String, Any?>()

    override fun before() {
        previous = context.getSharedPreferences("legal-consent", Context.MODE_PRIVATE).all.toMap()
        check(ConsentStore(context).accept())
    }

    override fun after() {
        val editor = context.getSharedPreferences("legal-consent", Context.MODE_PRIVATE).edit().clear()
        previous.forEach { (key, value) -> when (value) {
            is String -> editor.putString(key, value)
            is Long -> editor.putLong(key, value)
        } }
        check(editor.commit())
    }
}
