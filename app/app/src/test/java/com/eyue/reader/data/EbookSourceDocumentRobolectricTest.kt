package com.eyue.reader.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [MobiBitmapRegionDecoderShadow::class])
class EbookSourceDocumentRobolectricTest {
    private lateinit var root: File
    private lateinit var context: Context
    private val stores = mutableListOf<EbookSourceStore>()
    private val originalDigests = mutableMapOf<File, ByteArray>()

    @Before fun setUp() {
        root = File("build/source-dom-${UUID.randomUUID()}").absoluteFile.apply { mkdirs() }
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
    }

    @After fun tearDown() {
        stores.forEach(EbookSourceStore::close)
        originalDigests.forEach { (file, digest) -> assertArrayEquals("Read-only original changed: ${file.name}", digest, sha256(file)) }
        check(root.canonicalFile.parentFile == File("build").canonicalFile)
        repeat(10) {
            if (root.deleteRecursively()) return
            Thread.sleep(100)
        }
        check(!root.exists())
    }

    @Test fun verticalTxtUsesOnlyTheRequestedCanonicalChapterAndEscapesMarkup() = runBlocking {
        val text = "第一段 <script> & 内容。\n第二段原文。"
        val chapter = Chapter(title = "第一章", characterCount = text.length)
        val book = Book(title = "竖排 TXT", format = BookFormat.TXT, mode = LibraryMode.NOVEL, chapters = listOf(chapter))
        val store = EbookSourceStore(context, book).also(stores::add)
        val first = store.load(0, text)
        assertTrue(first.html.contains("&lt;script&gt; &amp; 内容。"))
        assertEquals(text, first.canonicalText)
        assertSame(first, store.load(0, text))
        val updated = store.load(0, text + "编辑")
        assertNotEquals(first.key, updated.key)
        assertTrue(updated.html.contains("编辑"))
        assertNull(store.openResource(first.baseUrl))
    }

    @Test fun newEpubImportOpensOnlyTheRequestedSavedDomWithoutRescanningTheOriginal() = runBlocking {
        val source = epubFixture(includeImage = false)
        val directory = File(context.filesDir, "books/fast-import").apply { mkdirs() }
        val book = EpubTestImporter(context).import(source, directory, "fast-import", "fast-import")
        assertEquals(10, book.contentVersion)
        assertTrue(book.chapters.all { File(requireNotNull(it.textPath)).isFile })
        readChapterText(book, 1)
        val firstManifest = JSONObject(File(requireNotNull(book.chapters[1].lazySourcePath)).readText())
        assertEquals("OPS/text/body.xhtml", firstManifest.getString("archivePath"))
        assertTrue(firstManifest.getString("html").contains("href='../styles/main.css'"))
        // The other chapter remains a valid file but cannot be decoded. Opening
        // this chapter must not parse its neighbor or reopen the entire package.
        File(requireNotNull(book.chapters[0].lazySourcePath)).writeText("invalid unrequested manifest")
        val isolated = book.copy(sourcePath = File(directory, "unavailable.epub").absolutePath, sourceUri = null)
        val document = store(isolated).load(1, readChapterText(book, 1))
        assertTrue(document.baseUrl.endsWith("/archive/OPS/text/body.xhtml"))
        assertTrue(document.html.contains("<h2 id='two'>第二章</h2>"))
        assertTrue(document.noteTargets.values.any { it.contains("原书注解") })
        assertEquals(0, context.cacheDir.walkTopDown().count { it.isFile && it.name.matches(Regex("chapter-\\d+\\.html")) })
        val resourceStore = store(book)
        val available = resourceStore.load(1, readChapterText(book, 1))
        val font = requireNotNull(resourceStore.openResource(URI(available.baseUrl).resolve("../fonts/reader.woff2").toString()))
        assertArrayEquals(fontBytes, font.stream.use { it.readBytes() })
        exportProvided("cached-epub", available, book.chapters[1])
    }

    @Test fun nestedChapterDisplaysOriginalHeadCssAndDomRatherThanFlattenedParagraphs() = runBlocking {
        val source = epubFixture()
        val book = epubBook(source)
        assertEquals(2, book.chapters.size)
        val document = store(book).load(1, text(book, 1))
        assertTrue(document.html.contains("<html lang='zh'>"))
        assertTrue(document.html.contains("<body class='paper' style='padding:1em'>"))
        assertTrue(document.html.contains("<link rel='stylesheet' href='../styles/main.css'/>"))
        assertTrue(document.html.contains("<style>.outer blockquote p{letter-spacing:.2em}</style>"))
        assertTrue(document.html.contains("<div class='outer'><blockquote cite='original'><h2 id='two'>第二章</h2>"))
        assertTrue(document.html.contains("<p class='body'>第二章<b>旧🙂正文</b>结束</p>"))
        assertFalse(document.html.contains("第一章正文"))
        assertEquals(text(book, 1), SourceHtmlEdit.plain(document.html) { "image" })
        assertTrue(document.noteTargets.getValue("#fn2").contains("原书注解"))
    }

    @Test fun draftParagraphChangesRemainVisibleWithoutWritingTheOriginalArchive() = runBlocking {
        val source = epubFixture(includeImage = false)
        val original = source.readBytes()
        val book = epubBook(source).copy(hasTextEdits = true)
        val edited = "预览正文\n新的第二行\n\n新的段落与 <文字>"
        val document = EbookSourceStore(context, book, preview = true).also(stores::add).load(0, edited)
        assertEquals(edited, document.canonicalText)
        assertEquals(edited, SourceHtmlEdit.plain(document.html) { null })
        assertArrayEquals(original, source.readBytes())
    }

    @Test fun editedChapterKeepsOriginalDomCssAndResourcesWithoutRescanningOtherChapters() = runBlocking {
        val source = epubFixture(includeImage = false)
        val bytes = source.readBytes()
        val directory = File(context.filesDir, "books/draft-styles").apply { mkdirs() }
        val book = EpubTestImporter(context).import(source, directory, "draft-styles", "draft-styles")
        val prepared = loadChapterContent(context, book, 1)
        val folder = File(root, "edit-preview").apply { mkdirs() }
        val start = prepared.text.indexOf("旧🙂正文")
        val edit = EbookTextEdit(1, prepared.text, start, start + "旧🙂正文".length, "新🙂修改正文")
        val textFile = File(folder, "chapter.txt").apply { writeText(edit.text) }
        val chapter = retainEditedChapterSource(prepared.chapter, edit.chapter(prepared.chapter, textFile.path))
        val draft = edit.book(book, chapter, book.chapters.map { readChapterText(it).length })
        File(requireNotNull(book.chapters[0].lazySourcePath)).writeText("unrequested manifest must not be parsed")
        EbookSourceStore(context, draft, preview = true).use { store ->
            val document = store.load(1, edit.text)
            assertTrue(document.html.contains("<p class='body'>第二章<b>新🙂修改正文</b>结束</p>"))
            assertTrue(document.html.contains("<blockquote cite='original'>"))
            assertTrue(document.html.contains("href='../styles/main.css'"))
            assertTrue(document.html.contains("<body class='paper' style='padding:1em'>"))
            assertEquals(edit.text, SourceHtmlEdit.plain(document.html) { null })
            val css = URI(document.baseUrl).resolve("../styles/main.css").toString()
            assertEquals(mainCss, resource(store, css, "text/css").toString(Charsets.UTF_8))
        }
        assertEquals(prepared.text, readChapterText(book.chapters[1]))
        assertArrayEquals(bytes, source.readBytes())
    }

    @Test fun linkedCssImportsFontSvgAndImageResolveFromTheirOriginalVirtualDirectories() = runBlocking {
        val book = epubBook(epubFixture())
        val store = store(book)
        val document = store.load(0, text(book, 0))
        val cssUrl = URI(document.baseUrl).resolve("../styles/main.css").toString()
        val css = resource(store, cssUrl, "text/css").toString(Charsets.UTF_8)
        assertEquals(mainCss, css)
        assertEquals(nestedCss, resource(store, URI(cssUrl).resolve("nested/spacing.css").toString(), "text/css").toString(Charsets.UTF_8))
        assertArrayEquals(fontBytes, resource(store, URI(cssUrl).resolve("../fonts/reader.woff2").toString(), "font/woff2"))
        val svgUrl = URI(cssUrl).resolve("../art/paper.svg").toString()
        assertEquals(svg, resource(store, svgUrl, "image/svg+xml").toString(Charsets.UTF_8))
        assertArrayEquals(imageBytes, resource(store, URI(svgUrl).resolve("../images/cover.png").toString(), "image/png"))
        val unicode = URI(document.baseUrl).resolve("../images/%E7%BA%B9%E7%90%86%20%E5%9B%BE.png?v=1#source").toString()
        assertArrayEquals(imageBytes, resource(store, unicode, "image/png"))
    }

    @Test fun resourceBoundaryRejectsOtherBooksProvidersRemoteSchemesAndTraversal() = runBlocking {
        val source = epubFixture()
        val book = epubBook(source)
        val first = store(book)
        val document = first.load(0, text(book, 0))
        val other = store(book.copy(id = "different-book"))
        val otherDocument = other.load(0, text(book, 0))
        val origin = document.baseUrl.substringBefore("archive/")
        val invalid = listOf(
            otherDocument.baseUrl, "file://${source.absolutePath}", "content://untrusted/book", "http://eread.local/any",
            "https://example.invalid/book.css", document.baseUrl.replace("eread.local", "eread.local.evil"),
            document.baseUrl.replace("eread.local", "user@eread.local"), document.baseUrl.replace("eread.local", "eread.local:443"),
            origin + "archive/../OPS/styles/main.css", origin + "archive/%2e%2e/OPS/styles/main.css",
            origin + "archive/OPS/%2e%2e/styles/main.css", origin + "archive/OPS/%5c..%5cstyles/main.css",
            origin + "archive/%2FOPS/styles/main.css", origin + "archive/C%3A/private.txt", origin + "archive/OPS/%00styles.css",
            origin + "archive/OPS/styles/missing.css", "not a valid URL"
        )
        invalid.forEach { url -> assertNull("Must not expose $url", first.openResource(url)) }
        assertNull("A different book cannot serve the first book's URL", other.openResource(document.baseUrl))
        assertTrue(resource(first, URI(document.baseUrl).resolve("../styles/main.css").toString(), "text/css").isNotEmpty())
    }

    @Test fun canonicalEditsKeepSourceTagsLinksCssAndArchiveBytes() = runBlocking {
        val source = epubFixture()
        val original = sha256(source)
        val book = epubBook(source).copy(hasTextEdits = true, contentVersion = 2)
        val canonical = text(book, 1).replace("旧🙂正文", "新🙂修改正文")
        val document = store(book).load(1, canonical)
        assertEquals(canonical, document.canonicalText)
        assertEquals(canonical, SourceHtmlEdit.plain(document.html) { "image" })
        assertTrue(document.html.contains("<p class='body'>第二章<b>新🙂修改正文</b>结束</p>"))
        assertTrue(document.html.contains("<blockquote cite='original'>"))
        assertTrue(document.html.contains("<a role='doc-noteref' href='#fn2'>[2]</a>"))
        assertTrue(document.html.contains("<link rel='stylesheet' href='../styles/main.css'/>"))
        assertTrue(document.html.contains("<style>.outer blockquote p{letter-spacing:.2em}</style>"))
        assertArrayEquals(original, sha256(source))
    }

    @Test fun unexplainedCanonicalMismatchFailsInsteadOfDisplayingUnrelatedSourceText() = runBlocking {
        val book = epubBook(epubFixture())
        val error = runCatching { store(book).load(0, "完全不匹配的内容") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(requireNotNull(error).message.orEmpty().contains("正文与阅读记录不一致"))
        val recovered = store(book).load(0, text(book, 0))
        assertEquals(text(book, 0), recovered.canonicalText)
    }

    @Test fun whitespaceEditsAreVisibleInTheSourceDomWithoutChangingTheArchive() = runBlocking {
        val source = epubFixture()
        val original = sha256(source)
        val book = epubBook(source).copy(hasTextEdits = true, contentVersion = 2)
        val baseline = text(book, 1)
        val store = store(book)
        for ((replacement, markup) in listOf("旧🙂 正文" to "旧🙂 正文", "旧🙂\n正文" to "旧🙂<br/>正文")) {
            val canonical = baseline.replace("旧🙂正文", replacement)
            assertNotEquals(baseline, canonical)
            val document = store.load(1, canonical)
            assertEquals(canonical, SourceHtmlEdit.plain(document.html) { "image" })
            assertTrue(document.html.contains("<b>$markup</b>"))
            assertTrue(document.html.contains("<blockquote cite='original'>"))
        }
        assertArrayEquals(original, sha256(source))
    }

    @Test fun uneditedCanonicalWhitespaceNormalizationKeepsTheOriginalDom() = runBlocking {
        val book = epubBook(epubFixture())
        val store = store(book)
        val baseline = text(book, 1)
        val original = store.load(1, baseline)
        val normalized = baseline.replace("旧🙂正文", "旧🙂 正文")
        assertNotEquals(baseline, normalized)
        val document = store.load(1, normalized)
        assertEquals(normalized, document.canonicalText)
        assertEquals(original.html, document.html)
    }

    @Test fun closeReleasesAlreadyOpenedZipResourcesAndPreventsFurtherLoads() = runBlocking {
        val book = epubBook(epubFixture())
        val store = store(book)
        val document = store.load(0, text(book, 0))
        val url = URI(document.baseUrl).resolve("../fonts/reader.woff2").toString()
        val stream = requireNotNull(store.openResource(url)).stream
        assertEquals(fontBytes[0].toInt() and 255, stream.read())
        store.close()
        assertNull(store.openResource(url))
        assertTrue("The previously returned stream must be closed", runCatching { stream.read() }.exceptionOrNull() is IOException)
        assertTrue(runCatching { store.load(0, text(book, 0)) }.exceptionOrNull() is IllegalStateException)
        store.close()
    }

    @Test fun reopeningAnUnchangedBookReusesIndexWithoutOpeningOrRewritingTheArchive() = runBlocking {
        val source = epubFixture()
        val probe = sourceProvider(source)
        val book = epubBook(source).copy(sourcePath = null, sourceUri = probe.uri.toString())
        val first = store(book)
        val firstDocument = first.load(1, text(book, 1))
        assertTrue("Cold preparation must actually read the archive", probe.opens.get() > 0)
        first.close()
        val archiveDigest = sha256(source)
        val cached = File(context.cacheDir, "ebook-source").walkTopDown().filter(File::isFile).toList()
        assertTrue(cached.any { it.name == "index.json" })
        val stamp = 1_650_000_000_000L
        cached.forEach { assertTrue(it.setLastModified(stamp)) }
        val before = cached.associate { it.relativeTo(context.cacheDir).path to (it.lastModified() to it.readBytes()) }
        val sourceOpens = probe.opens.get()
        val secondDocument = store(book).load(1, text(book, 1))
        assertEquals(firstDocument.html, secondDocument.html)
        assertEquals(firstDocument.key, secondDocument.key)
        assertEquals("A warm chapter must not reopen and scan the source", sourceOpens, probe.opens.get())
        before.forEach { (relative, snapshot) ->
            val file = File(context.cacheDir, relative)
            assertEquals("Warm open rewrote $relative", snapshot.first, file.lastModified())
            assertArrayEquals(snapshot.second, file.readBytes())
        }
        assertArrayEquals(archiveDigest, sha256(source))
    }

    @Test fun nonSeekableProviderIsCopiedOnceForConcurrentResourcesAndRemovedOnClose() = runBlocking {
        val source = epubFixture()
        val original = sha256(source)
        val probe = sourceProvider(source, nonSeekable = true)
        val book = epubBook(source).copy(sourcePath = null, sourceUri = probe.uri.toString())
        val store = store(book)
        val document = store.load(0, text(book, 0))
        val copied = File(context.cacheDir, "document-sources").listFiles().orEmpty().single()
        assertArrayEquals(source.readBytes(), copied.readBytes())
        val opens = probe.opens.get()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val jobs = (0 until 8).map { index -> executor.submit(Callable {
                if (index % 2 == 0) assertEquals(mainCss,
                    resource(store, URI(document.baseUrl).resolve("../styles/main.css").toString(), "text/css").toString(Charsets.UTF_8))
                else assertArrayEquals(fontBytes,
                    resource(store, URI(document.baseUrl).resolve("../fonts/reader.woff2").toString(), "font/woff2"))
            }) }
            jobs.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally { executor.shutdownNow() }
        assertEquals("No resource request may reopen/copy the non-seekable original", opens, probe.opens.get())
        val stream = requireNotNull(store.openResource(URI(document.baseUrl).resolve("../fonts/reader.woff2").toString())).stream
        store.close()
        assertFalse("The reader session must not retain a second original", copied.exists())
        assertTrue(runCatching { stream.read() }.exceptionOrNull() is IOException)
        assertArrayEquals(original, sha256(source))
    }

    @Test fun separateCanonicalRevisionsDoNotReturnTheFirstCachedDraft() = runBlocking {
        val book = epubBook(epubFixture()).copy(hasTextEdits = true)
        val store = store(book)
        val original = text(book, 1)
        val first = store.load(1, original.replace("旧🙂正文", "第一稿"))
        val second = store.load(1, original.replace("旧🙂正文", "第二稿"))
        assertNotEquals(first.key, second.key)
        assertTrue(second.html.contains("<b>第二稿</b>"))
        assertFalse(second.html.contains("第一稿"))
        assertEquals(original, store.load(1, original).canonicalText)
    }

    @Test fun providedEpubCurrentChapterRetainsOriginalHeadAndCanonicalPositions() = runBlocking {
        val source = provided(BookFormat.EPUB)
        val parsed = EpubArchive.read(source)
        val index = parsed.sections.indexOfFirst { it.sourceNoteTargets.values.any { body -> body.contains("西游释厄传") } }
        assertTrue(index >= 0)
        val book = epubBook(source)
        val document = store(book).load(index, text(book, index))
        assertTrue(document.html.contains("<style", true) || document.html.contains("<link", true))
        assertTrue(document.html.contains("<body", true))
        assertTrue(document.html.contains("href=", true))
        assertEquals(text(book, index), SourceHtmlEdit.plain(document.html) { "image" })
        assertTrue(document.noteTargets.values.any { it.contains("西游释厄传") })
        exportProvided("epub", document, book.chapters[index])
    }

    @Test fun providedMobiCurrentChapterKeepsOriginalMarkupAndCanonicalPositions() = runBlocking {
        val source = provided(BookFormat.MOBI)
        val parsed = MobiArchive.read(source)
        val index = parsed.sections.indexOfFirst { it.sourceNoteTargets.values.any { body -> body.contains("西游释厄传") } }
        assertTrue(index >= 0)
        val chapters = parsed.sections.mapIndexed { chapterIndex, section -> chapter(section.title, section.html, "mobi-$chapterIndex") }
        val book = Book(title = parsed.title, author = parsed.author, format = BookFormat.MOBI, mode = LibraryMode.NOVEL,
            chapters = chapters, sourcePath = source.absolutePath)
        val document = store(book).load(index, text(book, index))
        if (parsed.css.isNotBlank()) assertTrue(document.html.contains(parsed.css))
        val originalFormatting = sourceHtmlTags(parsed.sections[index].html).filter { !it.closing && it.name in setOf("p", "font") }
            .mapNotNull { tag ->
                val values = listOf("height", "width", "align", "size", "color").mapNotNull { key ->
                    sourceHtmlAttribute(tag.attributes, key)?.let { key to it }
                }.toMap()
                values.takeIf { it.isNotEmpty() }?.let { tag.name to it }
            }.distinct().toList()
        assertTrue("The sample must exercise actual legacy MOBI presentation attributes", originalFormatting.isNotEmpty())
        val rendered = sourceHtmlTags(document.html).filterNot { it.closing }.toList()
        originalFormatting.forEach { (tag, attributes) ->
            assertTrue("Lost original MOBI formatting: $tag $attributes", rendered.any { node ->
                node.name == tag && attributes.all { (key, value) -> sourceHtmlAttribute(node.attributes, key) == value }
            })
        }
        assertTrue(document.html.contains("<body", true))
        assertTrue(document.html.contains("href=", true))
        assertEquals(text(book, index), SourceHtmlEdit.plain(document.html) { "image" })
        assertTrue(document.noteTargets.values.any { it.contains("西游释厄传") })
        exportProvided("mobi", document, book.chapters[index])
        val first = store(book).load(0, text(book, 0))
        assertEquals(text(book, 0), SourceHtmlEdit.plain(first.html) { "image" })
        exportProvided("mobi-first", first, book.chapters.first())
    }

    @Test fun providedMobiUsesTheActualImporterAndOpensItsFirstChapter() = runBlocking {
        val configured = System.getenv("EREAD_EBOOK_SMOKE_SOURCE")
        assumeTrue("Set EREAD_EBOOK_SMOKE_SOURCE for an actual imported MOBI", !configured.isNullOrBlank())
        val source = File(requireNotNull(configured))
        assumeTrue(source.isFile)
        originalDigests[source] = sha256(source)
        val id = UUID.randomUUID().toString()
        val directory = File(context.filesDir, "books/$id").apply { mkdirs() }
        val importer = DocumentImporter(context)
        val method = DocumentImporter::class.java.declaredMethods.single { it.name == "importMobi" }.apply { isAccessible = true }
        val started = System.nanoTime()
        val book = (method.invoke(importer, source, directory, id, source.nameWithoutExtension, DEFAULT_CHAPTER_RULE,
            {}) as Book).copy(sourcePath = source.absolutePath)
        val imported = System.nanoTime()
        val canonical = readChapterText(book, 0)
        val textRead = System.nanoTime()
        val sourceStore = store(book.copy(sourcePath = File(directory, "not-needed-for-cached-reading.mobi").absolutePath))
        val document = sourceStore.load(0, canonical)
        val loaded = System.nanoTime()
        assertEquals("An imported MOBI must reuse its saved chapter DOM without decoding the original", 0,
            context.cacheDir.walkTopDown().count { it.isFile && it.name.matches(Regex("chapter-\\d+\\.html")) })
        assertTrue(document.html.contains("<body", true))
        assertEquals(canonical.filterNot(Char::isWhitespace), SourceHtmlEdit.plain(document.html) { "image" }.filterNot(Char::isWhitespace))
        exportProvided("actual-mobi", document, book.chapters.first())
        val largest = book.chapters.indices.maxBy { book.chapters[it].characterCount }
        for ((name, index) in listOf("middle" to book.chapters.size / 2, "last" to book.chapters.lastIndex, "largest" to largest)) {
            val chapterText = readChapterText(book, index)
            val loadedChapter = sourceStore.load(index, chapterText)
            assertEquals(chapterText.filterNot(Char::isWhitespace), SourceHtmlEdit.plain(loadedChapter.html) { "image" }.filterNot(Char::isWhitespace))
            exportProvided("actual-mobi-$name", loadedChapter, book.chapters[index])
        }
        assertEquals(0, context.cacheDir.walkTopDown().count { it.isFile && it.name.matches(Regex("chapter-\\d+\\.html")) })
        println("Actual MOBI: chapters=${book.chapters.size}, importMs=${(imported-started)/1_000_000}, textMs=${(textRead-imported)/1_000_000}, sourceMs=${(loaded-textRead)/1_000_000}")
    }

    private fun exportProvided(name: String, document: EbookSourceDocument, chapter: Chapter) {
        val artifacts = System.getenv("EREAD_TEST_ARTIFACTS")?.takeIf { it.isNotBlank() } ?: return
        val folder = File(artifacts, "ebook-source-qa").apply { mkdirs() }
        val json = org.json.JSONObject().put("key", document.key).put("html", document.html).put("baseUrl", document.baseUrl)
            .put("canonicalText", document.canonicalText).put("noteTargets", org.json.JSONObject(document.noteTargets))
            .put("images", org.json.JSONArray(chapter.inlineImages.map { org.json.JSONObject().put("offset", it.offset).put("path", it.path) }))
        val options = org.json.JSONObject().put("canonical", document.canonicalText)
            .put("initialNavigation", org.json.JSONObject().put("offset", document.canonicalText.length / 2))
            .put("decorations", org.json.JSONObject().put("annotations", org.json.JSONArray()).put("search", "").put("spokenStart", -1).put("spokenEnd", -1))
            .put("config", org.json.JSONObject().put("mode", "SCROLL").put("fontSize", 19).put("lineHeight", 35)
                .put("letterSpacing", .5).put("paragraphGap", 16).put("left", 24).put("right", 24).put("top", 18).put("bottom", 18))
            .put("noteTargets", org.json.JSONArray(document.noteTargets.keys.toList())).put("images", json.getJSONArray("images"))
        json.put("trustedHtml", com.eyue.reader.ui.ebookReaderHtml(document,
            context.assets.open("reader/ebook-reader.js").bufferedReader().use { it.readText() }, "actual-source-test", options))
        File(folder, "$name.json").writeText(json.toString())
    }

    private fun store(book: Book) = EbookSourceStore(context, book).also(stores::add)
    private fun text(book: Book, chapter: Int) = File(requireNotNull(book.chapters[chapter].textPath)).readText()
    private fun chapter(title: String, html: String, name: String): Chapter {
        val text = SourceHtmlEdit.plain(html) { "image" }
        return Chapter(title = title, textPath = File(root, "$name-${UUID.randomUUID()}.txt").apply { writeText(text) }.absolutePath,
            characterCount = text.length)
    }
    private fun epubBook(source: File): Book {
        val parsed = EpubArchive.read(source)
        return Book(title = parsed.title, author = parsed.author, format = BookFormat.EPUB, mode = LibraryMode.NOVEL,
            chapters = parsed.sections.mapIndexed { index, section -> chapter(section.title, section.html, "epub-$index") }, sourcePath = source.absolutePath)
    }
    private fun resource(store: EbookSourceStore, url: String, type: String): ByteArray {
        val resource = requireNotNull(store.openResource(url)) { "Missing original resource: $url" }
        assertEquals(type, resource.mimeType)
        return resource.stream.use { it.readBytes() }
    }
    private fun provided(format: BookFormat): File {
        val directory = System.getenv("EREAD_NOTES_SOURCE_DIR")
        assumeTrue("Set EREAD_NOTES_SOURCE_DIR for the provided private sample", !directory.isNullOrBlank())
        val source = File(requireNotNull(directory), "西游记[lunarora.com].${format.name.lowercase()}")
        assumeTrue("Private sample not installed", source.isFile)
        originalDigests[source] = sha256(source)
        return source
    }
    private fun sha256(file: File): ByteArray = MessageDigest.getInstance("SHA-256").let { digest ->
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        digest.digest()
    }

    private data class SourceProbe(val uri: Uri, val opens: AtomicInteger)
    private fun sourceProvider(file: File, nonSeekable: Boolean = false): SourceProbe {
        val authority = "source-dom-${UUID.randomUUID()}"
        val opens = AtomicInteger()
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "application/epub+zip"
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
                MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, "last_modified")).apply {
                    addRow(arrayOf(file.name, file.length(), file.lastModified()))
                }
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                check(mode == "r")
                opens.incrementAndGet()
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor =
                AssetFileDescriptor(openFile(uri, mode), 0, if (nonSeekable) file.length() + 1 else AssetFileDescriptor.UNKNOWN_LENGTH)
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        }
        provider.attachInfo(context, ProviderInfo().apply { this.authority = authority; exported = true })
        ShadowContentResolver.registerProviderInternal(authority, provider)
        return SourceProbe(Uri.Builder().scheme("content").authority(authority).appendPath(file.name).build(), opens)
    }

    private fun epubFixture(includeImage: Boolean = true): File = File(root, "source.epub").apply {
        val entries = linkedMapOf(
            "META-INF/container.xml" to "<container><rootfile full-path='OPS/book.opf'/></container>".toByteArray(),
            "OPS/book.opf" to ("<package><metadata><title>原书DOM</title><creator>作者</creator></metadata><manifest>" +
                "<item id='body' href='text/body.xhtml'/><item id='nav' href='nav.xhtml' properties='nav'/></manifest>" +
                "<spine><itemref idref='body'/></spine></package>").toByteArray(),
            "OPS/nav.xhtml" to "<html><nav><a href='text/body.xhtml#one'>第一章</a><a href='text/body.xhtml#two'>第二章</a></nav></html>".toByteArray(),
            "OPS/text/body.xhtml" to ("<html lang='zh'><head><link rel='stylesheet' href='../styles/main.css'/>" +
                "<style>.outer blockquote p{letter-spacing:.2em}</style></head><body class='paper' style='padding:1em'>" +
                "<div class='outer'><blockquote cite='original'><h2 id='one'>第一章</h2><p>第一章正文${if (includeImage) "<img src='../images/cover.png'/>" else ""}</p>" +
                "<h2 id='two'>第二章</h2><p class='body'>第二章<b>旧🙂正文</b>结束</p>" +
                "<p>注号<a role='doc-noteref' href='#fn2'>[2]</a></p><aside id='fn2' role='doc-footnote'>原书注解</aside>" +
                "</blockquote></div></body></html>").toByteArray(),
            "OPS/styles/main.css" to mainCss.toByteArray(), "OPS/styles/nested/spacing.css" to nestedCss.toByteArray(),
            "OPS/fonts/reader.woff2" to fontBytes, "OPS/art/paper.svg" to svg.toByteArray(),
            "OPS/images/cover.png" to imageBytes, "OPS/images/纹理 图.png" to imageBytes
        )
        ZipOutputStream(outputStream()).use { zip ->
            entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        }
    }

    companion object {
        private const val mainCss = "@import 'nested/spacing.css'; @font-face{font-family:book;src:url('../fonts/reader.woff2')} .paper{background:url('../art/paper.svg')}"
        private const val nestedCss = ".outer blockquote p{margin:0 1em;line-height:1.7}"
        private const val svg = "<svg xmlns='http://www.w3.org/2000/svg'><image href='../images/cover.png' width='10' height='10'/></svg>"
        private val fontBytes = ByteArray(8192) { (it % 251).toByte() }
        private val imageBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10)
    }
}
