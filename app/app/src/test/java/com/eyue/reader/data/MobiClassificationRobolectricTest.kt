package com.eyue.reader.data

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Color
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [MobiBitmapRegionDecoderShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MobiClassificationRobolectricTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var fixtures: File
    private lateinit var importer: DocumentImporter

    @Before fun setUp() {
        root = File("build/robolectric-mobi-${UUID.randomUUID()}").absoluteFile.apply { mkdirs() }
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        }
        fixtures = File(context.cacheDir, "fixtures").apply { mkdirs() }
        importer = DocumentImporter(context)
    }

    @After fun tearDown() {
        check(root.canonicalFile.parentFile == File("build").canonicalFile)
        check(root.deleteRecursively())
    }

    @Test fun imageOnlyMobiUsesReadingOrderRepeatedPagesAndFirstPageCover() = runBlocking {
        val source = File(fixtures, "image-only.mobi").apply {
            writeBytes(mobiFixture("<body><img recindex='3'/><img recindex='2'/><img recindex='3'/></body>",
                listOf(imageBytes(Color.RED), imageBytes(Color.GREEN))))
        }
        val book = import(source)
        assertEquals(BookFormat.MOBI, book.format)
        assertEquals(LibraryMode.COMIC, book.mode)
        assertEquals(7, book.contentVersion)
        assertEquals(listOf(Color.GREEN, Color.RED, Color.GREEN), book.chapters.flatMap { it.pages }.map { pixel(it.path) })
        assertTrue(book.chapters.all { it.textPath == null && it.inlineImages.isEmpty() })
        val cover = pixel(book.coverPath!!)
        assertTrue("Unexpected first-page cover pixel: ${Integer.toHexString(cover)}",
            Color.green(cover) >= 240 && Color.red(cover) <= 15 && Color.blue(cover) <= 15)
        assertEquals(book.chapters.single().pages.first().path, book.chapters.single().pages.last().path)
        assertTrue(importer.lastImportErrors.isEmpty())
    }

    @Test fun readableMobiAlwaysUsesNovelMode() = runBlocking {
        val source = File(fixtures, "body.mobi").apply {
            writeBytes(mobiFixture("<body><p>Readable body.</p><img recindex='2'/></body>", listOf(imageBytes(Color.RED))))
        }
        val book = import(source)
        assertEquals(LibraryMode.NOVEL, book.mode)
        assertEquals("Readable body.", File(book.chapters.single().textPath!!).readText())
        assertTrue(book.chapters.single().pages.isEmpty())
        assertEquals(Color.RED, pixel(book.chapters.single().inlineImages.single().path))
        val cover = pixel(book.coverPath!!)
        assertTrue("Unexpected embedded-cover pixel: ${Integer.toHexString(cover)}",
            Color.blue(cover) >= 240 && Color.red(cover) <= 15 && Color.green(cover) <= 15)
    }

    @Test fun unusedCoverDoesNotTurnAnEmptyMobiIntoAReadableComic() = runBlocking {
        val source = File(fixtures, "empty.mobi").apply { writeBytes(mobiFixture("<body> \n </body>")) }
        val result = runCatching { import(source) }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertTrue(result.exceptionOrNull()!!.message.orEmpty().contains("MOBI"))
    }

    @Test fun mobiHtmlChaptersDeferStylesAndCanOpenAfterOriginalIsRemoved() = runBlocking {
        val source = File(fixtures, "chapters.mobi").apply {
            writeBytes(mobiFixture("<body><h1>第一章 开端</h1><p>甲<strong>🙂乙</strong></p>" +
                "<h1>第二章 继续</h1><p>丙<img recindex='2'/>丁</p></body>", listOf(imageBytes(Color.RED))))
        }
        val book = import(source)
        assertEquals(2, book.chapters.size)
        assertTrue(book.chapters.all { it.lazySourcePath != null && it.sourceStyles.isEmpty() })
        assertTrue(book.chapters.all { it.characterCount == File(it.textPath!!).readText().length })
        assertTrue(source.delete())
        val prepared = loadChapterContent(context, book, 0)
        assertEquals("🙂乙", prepared.chapter.sourceStyles.first { !it.paragraph && it.bold == true }
            .let { prepared.text.substring(it.start, it.end) })
        assertFalse(File("${book.chapters[1].lazySourcePath}.prepared.json").exists())
    }

    @Test fun currentImageOnlyCacheMigratesWithoutOriginalAndPreservesHistoryCloudAndImageNotes() = runBlocking {
        val id = UUID.randomUUID().toString()
        val directory = File(context.filesDir, "books/$id").apply { mkdirs() }
        val red = File(directory, "red.png").apply { writeBytes(imageBytes(Color.RED)) }
        val green = File(directory, "green.png").apply { writeBytes(imageBytes(Color.GREEN)) }
        val firstText = File(directory, "first.txt").apply { writeText(" \n ") }
        val secondText = File(directory, "second.txt").apply { writeText("") }
        val chapters = listOf(
            Chapter(id = "first-chapter", title = "第一章", textPath = firstText.absolutePath,
                inlineImages = listOf(InlineImage(0, red.absolutePath))),
            Chapter(id = "second-chapter", title = "第二章", textPath = secondText.absolutePath,
                inlineImages = listOf(InlineImage(0, green.absolutePath), InlineImage(0, red.absolutePath), InlineImage(0, green.absolutePath))))
        val group = ShelfGroup("小说收藏", LibraryMode.NOVEL)
        val missing = File(fixtures, "removed-original.mobi")
        val old = Book(id = id, title = "旧图片 MOBI", format = BookFormat.MOBI, mode = LibraryMode.NOVEL,
            chapters = chapters, coverPath = red.absolutePath, chapterIndex = 1, pageIndex = 2,
            positionImagePath = green.absolutePath, contentVersion = 7, sourceUri = missing.toURI().toString(), sourceLinked = true,
            lastReadAt = 123456L, addedAt = 654321L, progress = .45f, groupId = group.id,
            cloudEnabled = true, cloudBackupAt = 234567L)
        val note = Annotation(id = "stable-note", bookId = id, chapterIndex = 1, pageIndex = 2,
            imagePath = green.absolutePath, quote = "正文插图", note = "重复图片的笔记", kind = AnnotationKind.NOTE, color = 0xffdfc981L)
        val bookmark = Annotation(id = "stable-bookmark", bookId = id, chapterIndex = 1, pageIndex = 1, imagePath = red.absolutePath)
        val state = LibraryState(books = listOf(old), annotations = listOf(note, bookmark), groups = listOf(group),
            readingSeconds = 91L, cloudIdentity = "https://example.org/dav/\nreader")
        val migrated = importer.upgradeInlineBooks(state)
        val book = migrated.books.single()
        assertEquals(LibraryMode.COMIC, book.mode)
        assertEquals(old.id, book.id)
        assertEquals(chapters.map { it.id }, book.chapters.map { it.id })
        assertEquals(chapters.map { it.title }, book.chapters.map { it.title })
        assertEquals(listOf(Color.RED, Color.GREEN, Color.RED, Color.GREEN), book.chapters.flatMap { it.pages }.map { pixel(it.path) })
        assertEquals(1, book.chapterIndex)
        assertEquals(2, book.pageIndex)
        assertEquals(0, book.characterOffset)
        assertNull(book.positionImagePath)
        assertNull(book.groupId)
        assertEquals(old.lastReadAt, book.lastReadAt)
        assertEquals(old.addedAt, book.addedAt)
        assertEquals(old.progress, book.progress, 0f)
        assertEquals(old.sourceUri, book.sourceUri)
        assertTrue(book.sourceLinked)
        assertTrue(book.cloudEnabled)
        assertEquals(old.cloudBackupAt, book.cloudBackupAt)
        assertEquals(state.cloudIdentity, migrated.cloudIdentity)
        assertEquals(state.groups, migrated.groups)
        assertEquals(state.readingSeconds, migrated.readingSeconds)
        val mappedNote = migrated.annotations.first()
        assertEquals(note.id, mappedNote.id)
        assertEquals(note.note, mappedNote.note)
        assertEquals(note.color, mappedNote.color)
        assertEquals(1, mappedNote.chapterIndex)
        assertEquals(2, mappedNote.pageIndex)
        assertNull(mappedNote.imagePath)
        val mappedBookmark = migrated.annotations.last()
        assertEquals(bookmark.id, mappedBookmark.id)
        assertEquals(1, mappedBookmark.chapterIndex)
        assertEquals(1, mappedBookmark.pageIndex)
        assertNull(mappedBookmark.imagePath)
        assertTrue(importer.lastImportErrors.isEmpty())
        assertEquals(migrated, importer.upgradeInlineBooks(migrated))
        assertTrue(importer.lastImportErrors.isEmpty())
    }

    @Test fun readableComicModeMobiCacheIsCorrectedWithoutReparsingTheOriginal() = runBlocking {
        val id = UUID.randomUUID().toString()
        val directory = File(context.filesDir, "books/$id").apply { mkdirs() }
        val text = File(directory, "body.txt").apply { writeText("Cached readable body.") }
        val chapter = Chapter(id = "stable-body", title = "正文", textPath = text.absolutePath)
        val group = ShelfGroup("漫画收藏", LibraryMode.COMIC)
        val old = Book(id = id, title = "曾从漫画导入的小说", format = BookFormat.MOBI, mode = LibraryMode.COMIC,
            chapters = listOf(chapter), contentVersion = 7, sourceUri = File(fixtures, "missing.mobi").toURI().toString(),
            sourceLinked = true, characterOffset = 7, progress = .25f, lastReadAt = 345678L, groupId = group.id)
        val mark = Annotation(id = "stable-text-note", bookId = id, chapterIndex = 0, pageIndex = 0,
            characterOffset = 7, endOffset = 15, quote = "readable", note = "正文笔记", kind = AnnotationKind.NOTE)
        val state = LibraryState(books = listOf(old), annotations = listOf(mark), groups = listOf(group))
        val updated = importer.upgradeInlineBooks(state)
        val book = updated.books.single()
        assertEquals(LibraryMode.NOVEL, book.mode)
        assertEquals(old.id, book.id)
        assertEquals(old.chapters, book.chapters)
        assertEquals(old.characterOffset, book.characterOffset)
        assertEquals(old.progress, book.progress, 0f)
        assertEquals(old.lastReadAt, book.lastReadAt)
        assertNull(book.groupId)
        assertEquals(state.annotations, updated.annotations)
        assertEquals(updated, importer.upgradeInlineBooks(updated))
        assertTrue(importer.lastImportErrors.isEmpty())
    }

    private fun import(source: File): Book {
        val id = UUID.randomUUID().toString()
        val directory = File(context.filesDir, "books/$id").apply { mkdirs() }
        // Android's Uri.fromFile uses Unix paths; call the actual parser directly on Windows.
        val method = DocumentImporter::class.java.getDeclaredMethod("importMobi", File::class.java, File::class.java,
            String::class.java, String::class.java, String::class.java, kotlin.jvm.functions.Function0::class.java)
        method.isAccessible = true
        val checkActive: () -> Unit = {}
        return try {
            method.invoke(importer, source, directory, id, source.nameWithoutExtension, DEFAULT_CHAPTER_RULE, checkActive) as Book
        } catch (error: InvocationTargetException) { throw error.targetException }
    }

    private fun pixel(path: String): Int = BitmapFactory.decodeFile(path).let { bitmap ->
        try { bitmap.getPixel(0, 0) } finally { bitmap.recycle() }
    }

    private fun imageBytes(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return try { ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        } } finally { bitmap.recycle() }
    }

    private fun mobiFixture(html: String, bodyImages: List<ByteArray> = emptyList()): ByteArray {
        val text = html.toByteArray(Charsets.UTF_8)
        val title = "Synthetic MOBI".toByteArray(Charsets.UTF_8)
        val fields = listOf(503 to title, 201 to byteArrayOf(0, 0, 0, 0))
        val exthSize = 12 + fields.sumOf { 8 + it.second.size }
        val titleOffset = 248 + exthSize
        val header = ByteArray(titleOffset + title.size)
        fun word(bytes: ByteArray, offset: Int, value: Int) { ByteBuffer.wrap(bytes, offset, 2).putShort(value.toShort()) }
        fun number(bytes: ByteArray, offset: Int, value: Int) { ByteBuffer.wrap(bytes, offset, 4).putInt(value) }
        word(header, 0, 1)
        number(header, 4, text.size)
        word(header, 8, 1)
        word(header, 10, 4096)
        "MOBI".toByteArray().copyInto(header, 16)
        number(header, 20, 232)
        number(header, 24, 2)
        number(header, 28, 65001)
        number(header, 36, 6)
        number(header, 84, titleOffset)
        number(header, 88, title.size)
        number(header, 104, 6)
        number(header, 108, 2)
        number(header, 128, 64)
        "EXTH".toByteArray().copyInto(header, 248)
        number(header, 252, exthSize)
        number(header, 256, fields.size)
        var location = 260
        fields.forEach { (id, bytes) ->
            number(header, location, id)
            number(header, location + 4, 8 + bytes.size)
            bytes.copyInto(header, location + 8)
            location += 8 + bytes.size
        }
        title.copyInto(header, titleOffset)
        val records = listOf(header, text, imageBytes(Color.BLUE)) + bodyImages
        val tableSize = 78 + records.size * 8 + 2
        val pdb = ByteArray(tableSize + records.sumOf { it.size })
        "BOOKMOBI".toByteArray().copyInto(pdb, 60)
        word(pdb, 76, records.size)
        location = tableSize
        records.forEachIndexed { index, bytes ->
            number(pdb, 78 + index * 8, location)
            bytes.copyInto(pdb, location)
            location += bytes.size
        }
        return pdb
    }
}

/** Robolectric's Windows region decoder cannot decode native pixels; exercise the real fallback. */
@Implements(BitmapRegionDecoder::class)
class MobiBitmapRegionDecoderShadow {
    companion object {
        @JvmStatic
        @Implementation
        @Suppress("UNUSED_PARAMETER")
        fun newInstance(path: String, isShareable: Boolean): BitmapRegionDecoder? = null
    }
}
