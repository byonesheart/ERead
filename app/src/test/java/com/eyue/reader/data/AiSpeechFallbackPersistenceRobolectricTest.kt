package com.eyue.reader.data

import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AiSpeechFallbackPersistenceRobolectricTest {
    @Test fun oldLibrariesDefaultToNoSystemReplacementAndTheChoiceRoundTrips() {
        val base = RuntimeEnvironment.getApplication()
        val directory = File(base.cacheDir, "speech-fallback-${UUID.randomUUID()}").apply { mkdirs() }
        val repository = LibraryRepository(object : ContextWrapper(base) { override fun getFilesDir() = directory })
        try {
            assertFalse(repository.load().aiSpeechFallback)
            File(directory, "library.json").writeText("""{"version":11,"speechEngine":"AI"}""")
            val original = repository.load()
            assertFalse(original.aiSpeechFallback)
            repository.save(original.copy(aiSpeechFallback = true))
            assertTrue(repository.load().aiSpeechFallback)
            assertEquals(SpeechEngine.AI, repository.load().speechEngine)
            repository.save(repository.load().copy(aiSpeechFallback = false))
            assertFalse(repository.load().aiSpeechFallback)
        } finally { directory.deleteRecursively() }
    }
}
