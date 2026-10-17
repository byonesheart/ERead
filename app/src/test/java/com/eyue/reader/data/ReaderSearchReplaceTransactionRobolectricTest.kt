package com.eyue.reader.data

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.work.Configuration
import androidx.work.WorkManager
import com.eyue.reader.ReaderViewModel
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class ReaderSearchReplaceTransactionRobolectricTest {
    private lateinit var root: File
    private lateinit var app: Application
    private lateinit var vm: ReaderViewModel
    private val viewModels = ViewModelStore()

    @Before fun setup() {
        val base = RuntimeEnvironment.getApplication()
        runCatching { WorkManager.getInstance(base) }.getOrElse {
            WorkManager.initialize(base, Configuration.Builder().build())
        }
        val prefix = "search-replace-vm-${UUID.randomUUID()}-"
        root = File(base.cacheDir, prefix).apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefix + name, mode)
        }
        app = object : Application() { init { attachBaseContext(context) } }
        val initialized = WorkManager.getInstance(base).pruneWork().result
        await { initialized.isDone }
        initialized.get()
    }

    @After fun cleanup() {
        if (::vm.isInitialized) {
            val job = requireNotNull(vm.viewModelScope.coroutineContext[Job])
            viewModels.clear()
            await { job.isCompleted }
        }
        check(root.canonicalFile.parentFile == RuntimeEnvironment.getApplication().cacheDir.canonicalFile)
        root.deleteRecursively()
    }

    @Test fun txtWholeBookReplaceIncludesMoreThanOneHundredMatchesAndRelocatesPositionAndNotes() {
        val texts = listOf("第一章 开篇", "第二章 远行", "第三章 归途").mapIndexed { index, title ->
            title + "\n" + (0 until 60).joinToString("\n") { "旧词 ${index}_$it" } + "\n唯一标注${index}，阅读锚点${index}。"
        }
        val source = File(root, "original.txt").apply { writeText(texts.joinToString("\n\n")) }
        val readingOffset = texts[2].indexOf("阅读锚点2")
        val noteStart = texts[1].indexOf("唯一标注1")
        val firstMatch = texts[0].indexOf("旧词")
        val book = Book(title = "全文替换", format = BookFormat.TXT, mode = LibraryMode.NOVEL,
            chapters = texts.mapIndexed { index, text -> Chapter(title = text.lineSequence().first(),
                textPath = File(root, "txt-$index.txt").apply { writeText(text) }.absolutePath) },
            sourcePath = source.absolutePath, chapterIndex = 2, characterOffset = readingOffset, contentVersion = 7)
        val note = Annotation(bookId = book.id, chapterIndex = 1, pageIndex = 0, characterOffset = noteStart,
            endOffset = noteStart + "唯一标注1".length, quote = "唯一标注1", note = "留在第二章")
        val mark = Annotation(bookId = book.id, chapterIndex = 0, pageIndex = 0, characterOffset = firstMatch,
            endOffset = firstMatch + 2, quote = "旧词", kind = AnnotationKind.HIGHLIGHT)
        val repository = open(LibraryState(books = listOf(book), annotations = listOf(note, mark)))

        vm.replaceAllBookMatches("旧词", "新名称")
        await { !vm.importing }

        val saved = repository.load()
        val updated = saved.books.single()
        assertEquals(3, updated.chapters.size)
        assertEquals(texts.map { it.replace("旧词", "新名称") }, chapterTexts(updated))
        assertEquals(texts.joinToString("\n\n").replace("旧词", "新名称"), File(updated.sourcePath!!).readText())
        assertEquals(180, Regex("新名称").findAll(chapterTexts(updated).joinToString("\n\n")).count())
        assertTrue(vm.message.orEmpty().contains("180"))
        assertEquals(8, updated.contentVersion)
        assertEquals(2, updated.chapterIndex)
        assertEquals(readingOffset + 60, updated.characterOffset)
        val movedNote = saved.annotations.single { it.id == note.id }
        assertEquals(1, movedNote.chapterIndex)
        assertEquals(noteStart + 60, movedNote.characterOffset)
        assertEquals(note.endOffset + 60, movedNote.endOffset)
        assertEquals(note.quote, movedNote.quote)
        assertEquals(note.note, movedNote.note)
        val movedMark = saved.annotations.single { it.id == mark.id }
        assertEquals(firstMatch, movedMark.characterOffset)
        assertEquals(firstMatch + 3, movedMark.endOffset)
        assertEquals("新名称", movedMark.quote)
        assertEquals(chapterTexts(updated), chapterTexts(vm.state.books.single()))
    }

    @Test fun singleReplacementWithAStaleExpectedMatchCannotMutateSourceOrCaches() {
        val text = "第一章\n当前正文，保留标注。"
        val source = File(root, "original.txt").apply { writeText(text) }
        val cache = File(root, "cached.txt").apply { writeText(text) }
        val book = Book(title = "过期搜索结果", format = BookFormat.TXT, mode = LibraryMode.NOVEL,
            chapters = listOf(Chapter(title = "第一章", textPath = cache.absolutePath)),
            sourcePath = source.absolutePath, contentVersion = 4)
        val note = Annotation(bookId = book.id, chapterIndex = 0, pageIndex = 0,
            characterOffset = text.indexOf("保留标注"), quote = "保留标注", note = "不能移动")
        val repository = open(LibraryState(books = listOf(book), annotations = listOf(note)))
        val originalSource = source.readBytes()
        val originalCache = cache.readBytes()

        vm.replaceBookMatch(0, text.indexOf("当前正文"), "旧的正文", "替换内容")
        await { !vm.importing }

        assertTrue(vm.message.orEmpty().contains("搜索结果已变化"))
        assertArrayEquals(originalSource, source.readBytes())
        assertArrayEquals(originalCache, cache.readBytes())
        val saved = repository.load()
        assertEquals(4, saved.books.single().contentVersion)
        assertEquals(book.sourcePath, saved.books.single().sourcePath)
        assertEquals(listOf(note), saved.annotations)
        assertEquals(listOf(text), chapterTexts(saved.books.single()))
    }

    @Test fun epubWholeBookReplaceOverwritesEveryChapterWhilePreservingArchiveResourcesAndAnchors() {
        val source = epubFixture()
        val book = epubBook(source)
        assertEquals(2, book.chapters.size)
        val texts = chapterTexts(book)
        val originalResources = resourceBytes(source)
        val anchor = texts[1].indexOf("保留标注")
        val positioned = book.copy(chapterIndex = 1, characterOffset = anchor, contentVersion = 9)
        val note = Annotation(bookId = book.id, chapterIndex = 1, pageIndex = 0, characterOffset = anchor,
            endOffset = anchor + 4, quote = "保留标注", note = "归档和缓存必须一致")
        val repository = open(LibraryState(books = listOf(positioned), annotations = listOf(note)))

        vm.replaceAllBookMatches("旧词", "新的名称")
        await { !vm.importing }

        val saved = repository.load()
        val updated = saved.books.single()
        val reopened = EpubArchive.read(source)
        val expected = texts.map { it.replace("旧词", "新的名称") }
        assertEquals(expected, reopened.sections.map { SourceHtmlEdit.plain(it.html) { null } })
        assertEquals(expected, chapterTexts(updated))
        assertEquals(book.title, reopened.title)
        assertEquals(book.author, reopened.author)
        assertEquals(book.chapters.map { it.title }, updated.chapters.map { it.title })
        assertEquals(10, updated.contentVersion)
        assertEquals(1, updated.chapterIndex)
        assertEquals(anchor + 4, updated.characterOffset)
        assertEquals(anchor + 4, saved.annotations.single().characterOffset)
        assertEquals(note.endOffset + 4, saved.annotations.single().endOffset)
        assertEquals(note.note, saved.annotations.single().note)
        assertTrue(vm.message.orEmpty().contains("4"))
        val afterResources = resourceBytes(source)
        assertEquals(originalResources.keys, afterResources.keys)
        originalResources.forEach { (path, bytes) -> assertArrayEquals("Archive resource $path must be preserved", bytes, afterResources[path]) }
        assertFalse(cloudOperationMutex.isLocked)
    }

    @Test fun readonlySourcePermissionRetryRetainsWholeBookQueryAndReplacement() {
        val source = epubFixture()
        val originalBytes = source.readBytes()
        val writable = AtomicBoolean(false)
        val authority = "search-replace-${UUID.randomUUID()}"
        val uri = Uri.parse("content://$authority/original.epub")
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "application/epub+zip"
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                if (mode.contains('w') && !writable.get()) throw SecurityException("read only")
                return ParcelFileDescriptor.open(source, ParcelFileDescriptor.parseMode(mode))
            }
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        }
        provider.attachInfo(app, ProviderInfo().apply { this.authority = authority; exported = true })
        ShadowContentResolver.registerProviderInternal(authority, provider)
        val book = epubBook(source).copy(sourcePath = null, sourceUri = uri.toString(), sourceLinked = true)
        val texts = chapterTexts(book)
        val repository = open(LibraryState(books = listOf(book)))

        vm.replaceAllBookMatches("旧词", "获权后的新词")
        await { !vm.importing }
        assertEquals(uri.toString(), vm.pendingSourceWriteUri)
        assertArrayEquals(originalBytes, source.readBytes())
        assertEquals(texts, chapterTexts(repository.load().books.single()))

        writable.set(true)
        vm.resumeSourceWrite(uri)
        await { !vm.importing }

        assertNull(vm.pendingSourceWriteUri)
        val expected = texts.map { it.replace("旧词", "获权后的新词") }
        assertEquals(expected, EpubArchive.read(source).sections.map { SourceHtmlEdit.plain(it.html) { null } })
        assertEquals(expected, chapterTexts(repository.load().books.single()))
        assertTrue(vm.message.orEmpty().contains("4"))
    }

    private fun open(state: LibraryState): LibraryRepository {
        val repository = LibraryRepository(app)
        repository.save(state)
        vm = ReaderViewModel(app)
        viewModels.put("reader", vm)
        await { !vm.loading }
        vm.open(vm.state.books.single())
        await { vm.activeBookId == state.books.single().id && repository.load().books.single().lastReadAt > 0 }
        return repository
    }

    private fun chapterTexts(book: Book) = book.chapters.map { File(requireNotNull(it.textPath)).readText() }

    private fun epubBook(source: File): Book {
        val parsed = EpubArchive.read(source)
        return Book(title = parsed.title, author = parsed.author, format = BookFormat.EPUB, mode = LibraryMode.NOVEL,
            sourcePath = source.absolutePath, chapters = parsed.sections.mapIndexed { index, section ->
                val text = SourceHtmlEdit.plain(section.html) { null }
                Chapter(title = section.title, textPath = File(root, "epub-$index.txt").apply { writeText(text) }.absolutePath,
                    characterCount = text.length)
            })
    }

    private fun epubFixture(): File = File(root, "original.epub").apply {
        val files = linkedMapOf(
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to "<container><rootfile full-path='book.opf'/></container>".toByteArray(),
            "book.opf" to ("<package><metadata><title>多章替换事务</title><creator>测试作者</creator><meta name='cover' content='cover'/></metadata>" +
                "<manifest><item id='one' href='one.xhtml'/><item id='two' href='two.xhtml'/>" +
                "<item id='cover' href='cover.png' media-type='image/png'/><item id='css' href='styles.css' media-type='text/css'/>" +
                "</manifest><spine><itemref idref='one'/><itemref idref='two'/></spine></package>").toByteArray(),
            "one.xhtml" to "<html><head><link rel='stylesheet' href='styles.css'/></head><body><h1>第一章</h1><p class='body'>旧词甲，<b>旧词乙</b>。</p><blockquote>原样保留。</blockquote></body></html>".toByteArray(),
            "two.xhtml" to "<html><body><h1>第二章</h1><p>旧词丙，旧词丁。</p><p>保留标注，仍在第二章。</p></body></html>".toByteArray(),
            "styles.css" to "p.body{margin:1em} blockquote{font-style:italic}".toByteArray(),
            "cover.png" to byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 0)
        )
        ZipOutputStream(outputStream()).use { zip -> files.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
    }

    private fun resourceBytes(source: File): Map<String, ByteArray> = ZipFile(source).use { zip ->
        zip.entries().asSequence().filter { !it.name.endsWith(".xhtml") }
            .associate { it.name to zip.getInputStream(it).use { input -> input.readBytes() } }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Timed out waiting for search/replace transaction")
    }
}
