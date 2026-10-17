package com.eyue.reader.data

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AnnotationUnderlinePersistenceRobolectricTest {
    private fun underline(style: UnderlineStyle) = Annotation(id = "underline", bookId = "book", chapterIndex = 1,
        pageIndex = 2, characterOffset = 10, endOffset = 28, quote = "段落内划线", kind = AnnotationKind.UNDERLINE,
        color = 0xFF44AA99, underlineStyle = style)

    @Test fun underlineStyleRoundTripsThroughLocalAndCloudSharedAnnotationJson() {
        val repository = LibraryRepository(RuntimeEnvironment.getApplication())
        UnderlineStyle.entries.forEach { style ->
            val annotation = underline(style)
            val json = repository.writeAnnotation(annotation)
            assertEquals(style.name, json.getString("underlineStyle"))
            assertEquals(annotation, repository.readAnnotation(json))
        }
    }

    @Test fun oldOrUnknownUnderlineStylesRetainTheExistingStraightLineBehavior() {
        val repository = LibraryRepository(RuntimeEnvironment.getApplication())
        val json = repository.writeAnnotation(underline(UnderlineStyle.WAVY))
        json.remove("underlineStyle")
        assertEquals(UnderlineStyle.STRAIGHT, repository.readAnnotation(json).underlineStyle)
        json.put("underlineStyle", "future-style")
        assertEquals(UnderlineStyle.STRAIGHT, repository.readAnnotation(json).underlineStyle)
    }

    @Test fun savedLibraryKeepsEachAnnotationsStyleAndColorAfterReopening() {
        val context: Context = RuntimeEnvironment.getApplication()
        val directory = File.createTempFile("underline-library", "", context.cacheDir).apply { delete(); mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getFilesDir(): File = directory }
        val straight = underline(UnderlineStyle.STRAIGHT)
        val wave = underline(UnderlineStyle.WAVY).copy(id = "wave", color = 0xFF88AAFF)
        try {
            LibraryRepository(isolated).save(LibraryState(annotations = listOf(straight, wave)))
            assertEquals(listOf(straight, wave), LibraryRepository(isolated).load().annotations)
        } finally { directory.deleteRecursively() }
    }
}
