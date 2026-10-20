package com.eyue.reader.ui

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.eyue.reader.data.Book
import com.eyue.reader.data.BookFormat
import com.eyue.reader.data.Chapter
import com.eyue.reader.data.LibraryMode
import com.eyue.reader.data.ReaderBackground
import com.eyue.reader.data.ReaderSettings
import com.eyue.reader.data.ReadingMode
import com.eyue.reader.data.ChineseConversion
import com.eyue.reader.data.SourceTextStyle
import com.eyue.reader.data.SourceTextAlignment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReaderPageCacheKeyTest {
    @get:Rule val files = TemporaryFolder()
    private val settings = ReaderSettings()
    private fun book(path: String? = null) = Book(id = "cache-book", title = "缓存小说", format = BookFormat.MOBI,
        mode = LibraryMode.NOVEL, chapters = listOf(Chapter(title = "第一章", textPath = path)))
    private fun key(book: Book = this.book(), settings: ReaderSettings = this.settings,
        density: Density = Density(2f, 1f), direction: LayoutDirection = LayoutDirection.Ltr,
        width: Int = 600, height: Int = 900, text: List<String> = listOf("同一章正文"),
        images: List<List<String>> = listOf(emptyList()), platform: String = "platform-a") =
        readerLayoutCacheKey(book, settings, density, direction, width, height, text, images, platform)

    @Test fun equivalentInputsSurviveNewObjectsAndPresentationChanges() {
        val original = book()
        val expected = key(original)
        assertEquals(expected, key(original.copy(progress = .5f, lastReadAt = 1234, pageIndex = 4, title = "新名字")))
        assertEquals(expected, key(settings = settings.copy(nightMode = true, background = ReaderBackground.GREEN,
            brightness = .3f, readingMode = ReadingMode.PAGED, showTime = false, showBattery = false,
            showChapter = false, volumePaging = true, keepScreenOn = false, ttsSpeed = 1.5f)))
    }

    @Test fun everyTypographyAndViewportInputInvalidatesLayout() {
        val expected = key()
        listOf(settings.copy(fontSize = 22f), settings.copy(fontName = "系统宋体"), settings.copy(bold = true),
            settings.copy(lineHeight = 1.6f), settings.copy(letterSpacing = 1.2f), settings.copy(paragraphSpacing = 1.2f),
            settings.copy(chineseConversion = ChineseConversion.TRADITIONAL), settings.copy(chineseConversion = ChineseConversion.SIMPLIFIED)).forEach {
            assertNotEquals(expected, key(settings = it))
        }
        assertNotEquals(expected, key(width = 601))
        assertNotEquals(expected, key(height = 899))
        assertNotEquals(expected, key(density = Density(2.1f, 1f)))
        assertNotEquals(expected, key(density = Density(2f, 1.1f)))
        assertNotEquals(expected, key(direction = LayoutDirection.Rtl))
        assertNotEquals(expected, key(platform = "platform-b"))
    }

    @Test fun contentAndChapterFileIdentityBothInvalidate() {
        val source = files.newFile("chapter.txt").apply { writeText("原始正文") }
        val target = book(source.absolutePath)
        val expected = key(target)
        assertNotEquals(expected, key(target, text = listOf("不同正文")))
        assertNotEquals(expected, key(book(files.newFile("other.txt").absolutePath)))
        source.appendText("更多文字")
        assertNotEquals(expected, key(target))
        val changed = key(target)
        source.setLastModified(source.lastModified() + 5000)
        assertNotEquals(changed, key(target))
    }

    @Test fun allFourPhysicalMarginsInvalidateEvenWhenContentDimensionsStayEqual() {
        val expected = key()
        listOf(settings.copy(marginLeft = 25f), settings.copy(marginRight = 25f),
            settings.copy(marginTop = 19f), settings.copy(marginBottom = 19f),
            settings.copy(marginLeft = 10f, marginRight = 38f),
            settings.copy(marginTop = 8f, marginBottom = 28f)).forEach {
            assertNotEquals(expected, key(settings = it))
        }
    }

    @Test fun importedFontFileMetadataInvalidatesWithoutChangingLabel() {
        val font = files.newFile("font.ttf").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val custom = settings.copy(fontName = "自定义字体", fontPath = font.absolutePath)
        val expected = key(settings = custom)
        font.setLastModified(font.lastModified() + 5000)
        assertNotEquals(expected, key(settings = custom))
        val changed = key(settings = custom)
        font.appendBytes(byteArrayOf(4))
        assertNotEquals(changed, key(settings = custom))
    }

    @Test fun inlineImageOffsetOrderPathAndFileMetadataInvalidate() {
        val first = files.newFile("first.png").apply { writeBytes(byteArrayOf(1)) }
        val second = files.newFile("second.png").apply { writeBytes(byteArrayOf(2)) }
        val images = listOf(listOf("2:${first.absolutePath}", "2:${second.absolutePath}"))
        val expected = key(images = images)
        assertNotEquals(expected, key(images = listOf(listOf("3:${first.absolutePath}", "2:${second.absolutePath}"))))
        assertNotEquals(expected, key(images = listOf(images.single().reversed())))
        assertNotEquals(expected, key(images = listOf(listOf("2:${second.absolutePath}"))))
        first.setLastModified(first.lastModified() + 5000)
        assertNotEquals(expected, key(images = images))
        val changed = key(images = images)
        first.appendBytes(byteArrayOf(3))
        assertNotEquals(changed, key(images = images))
    }

    @Test fun chapterBoundariesAndCharactersBeyondDigestBufferArePartOfSignature() {
        assertNotEquals(key(text = listOf("ab", "c"), images = listOf(emptyList(), emptyList())),
            key(text = listOf("a", "bc"), images = listOf(emptyList(), emptyList())))
        val original = "山".repeat(4096) + "😀" + "海".repeat(4096)
        assertNotEquals(key(text = listOf(original)), key(text = listOf(original.dropLast(1) + "川")))
        assertEquals(key(text = listOf(original)), key(text = listOf(original)))
    }

    @Test fun everySourceStyleAndItsPrecedenceInvalidateTheLayout() {
        val original = book()
        val heading = SourceTextStyle(0, 4, fontScale = 1.5f, bold = true,
            alignment = SourceTextAlignment.CENTER, paragraph = true)
        fun withStyles(styles: List<SourceTextStyle>) = original.copy(chapters = listOf(original.chapters.single().copy(sourceStyles = styles)))
        val expected = key(withStyles(listOf(heading)))
        assertEquals(expected, key(withStyles(listOf(heading.copy()))))
        assertNotEquals(expected, key(original))
        listOf(heading.copy(start = 1), heading.copy(end = 3), heading.copy(fontScale = 1.6f),
            heading.copy(bold = false), heading.copy(italic = true), heading.copy(alignment = SourceTextAlignment.START),
            heading.copy(firstLineIndentEm = 1f), heading.copy(restLineIndentEm = 2f), heading.copy(paragraph = false)).forEach {
            assertNotEquals(expected, key(withStyles(listOf(it))))
        }
        val inner = SourceTextStyle(1, 3, bold = false)
        assertNotEquals(key(withStyles(listOf(heading, inner))), key(withStyles(listOf(inner, heading))))
    }
}
