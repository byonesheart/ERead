package com.eyue.reader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.eyue.reader.data.Book
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.Chapter
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.AiSpeechCache
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookshelfSpeechInfoRobolectricTest {
    @get:Rule val compose = createComposeRule()
    private val files = mutableListOf<File>()

    @After fun tearDown() { files.forEach { it.delete() }; AiSpeechCache(RuntimeEnvironment.getApplication()).clear("local") }

    private fun novel(): Book {
        val text = File(RuntimeEnvironment.getApplication().cacheDir, "book-info-${UUID.randomUUID()}.txt")
            .apply { writeText("正文 😀 A\n\u3000\uFFFC"); files += this }
        return Book(id = "local", title = "本地小说", format = BookFormat.MOBI, mode = LibraryMode.NOVEL,
            chapters = listOf(Chapter(title = "正文", textPath = text.absolutePath, characterCount = 9)))
    }

    @Test fun bookInformationLoadsItsUnicodeCharacterCount() {
        val book = novel()
        compose.setContent { MaterialTheme { Bookshelf(listOf(book), "", {}, {}, {}, {}) } }
        compose.onNodeWithTag("shelf_book_more_local").performClick()
        compose.onNodeWithText("书籍信息").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag("book_character_count") and hasText("总字数：4 字")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("book_character_count").assertTextEquals("总字数：4 字")
    }

    @Test fun generateSpeechDispatchesTheBookIdAndIsAbsentForComicsAndUndownloadedBooks() {
        var book by mutableStateOf(novel())
        var generated: String? = null
        compose.setContent { MaterialTheme { Bookshelf(listOf(book), "", {}, {}, {}, {}, onGenerateSpeech = { generated = it }) } }
        compose.onNodeWithTag("shelf_book_more_local").performClick()
        compose.onNodeWithTag("shelf_book_generate_speech_local").performClick()
        compose.runOnIdle { assertEquals("local", generated); book = book.copy(mode = LibraryMode.COMIC) }
        compose.onNodeWithTag("shelf_book_more_local").performClick()
        compose.onNodeWithTag("shelf_book_generate_speech_local").assertDoesNotExist()
        compose.onNodeWithText("书籍信息").performClick()
        compose.onNodeWithText("阅读").performClick()
        compose.runOnIdle { book = book.copy(mode = LibraryMode.NOVEL, cloudOnly = true) }
        compose.onNodeWithTag("shelf_book_more_local").performClick()
        compose.onNodeWithTag("shelf_book_generate_speech_local").assertDoesNotExist()
    }

    @Test fun bookInformationShowsOnlyItsOwnCompletedAudioSpaceAndHidesItForComics() {
        val context = RuntimeEnvironment.getApplication()
        val cache = AiSpeechCache(context)
        cache.clear("local")
        val audio = File.createTempFile("book-audio", ".mp3", context.cacheDir).apply { writeBytes(ByteArray(4096)); files += this }
        cache.commit("local", "completed", "mp3", audio, cache.generation("local"))
        var book by mutableStateOf(novel())
        var revision by mutableStateOf(0)
        compose.setContent { MaterialTheme { Bookshelf(listOf(book), "", {}, {}, {}, {}, speechCacheRevision = revision) } }
        compose.onNodeWithTag("shelf_book_more_local").performClick()
        compose.onNodeWithText("书籍信息").performClick()
        val label = "AI 语音缓存：${android.text.format.Formatter.formatShortFileSize(context, 4096)}"
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag("book_ai_speech_cache_size") and hasText(label)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("book_ai_speech_cache_size").assertTextEquals(label)
        compose.runOnIdle { cache.clear("local"); revision++ }
        val clearedLabel = "AI 语音缓存：${android.text.format.Formatter.formatShortFileSize(context, 0)}"
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag("book_ai_speech_cache_size") and hasText(clearedLabel)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("book_ai_speech_cache_size").assertTextEquals(clearedLabel)
        compose.onNodeWithText("阅读", substring = false).performClick()
        compose.runOnIdle { book = book.copy(mode = LibraryMode.COMIC) }
        compose.onNodeWithTag("shelf_book_more_local").performClick()
        compose.onNodeWithText("书籍信息").performClick()
        compose.onNodeWithTag("book_ai_speech_cache_size").assertDoesNotExist()
    }

    @Test fun rolesAreAvailableForNovelMetadataAndDispatchTheBookId() {
        var book by mutableStateOf(novel())
        var opened: String? = null
        compose.setContent { MaterialTheme { Bookshelf(listOf(book), "", {}, {}, {}, {}, onVoiceRoles = { opened = it }) } }
        compose.onNodeWithTag("shelf_book_more_local").performClick()
        compose.onNodeWithText("AI 音色提取").assertDoesNotExist()
        compose.onNodeWithTag("shelf_book_voice_roles_local").performClick()
        compose.runOnIdle { assertEquals("local", opened); book = book.copy(mode = LibraryMode.COMIC) }
        compose.onNodeWithTag("shelf_book_more_local").performClick()
        compose.onNodeWithTag("shelf_book_voice_roles_local").assertDoesNotExist()
        compose.onNodeWithText("书籍信息").performClick()
        compose.onNodeWithText("阅读", substring = false).performClick()
        compose.runOnIdle { book = book.copy(mode = LibraryMode.NOVEL, cloudOnly = true); opened = null }
        compose.onNodeWithTag("shelf_book_more_local").performClick()
        compose.onNodeWithTag("shelf_book_voice_roles_local").performClick()
        compose.runOnIdle { assertEquals("local", opened) }
    }
}
