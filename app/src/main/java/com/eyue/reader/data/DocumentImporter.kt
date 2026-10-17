package com.eyue.reader.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID
import java.security.MessageDigest

class DocumentImporter(private val context: Context) {
    private val currentProgress = MutableStateFlow<ImportProgress?>(null)
    private var progressActive = false
    val progress = currentProgress.asStateFlow()

    private fun report(stage: String, item: String = "", done: Int = 0, total: Int = 0,
                       fraction: Float? = if (total > 0) done.toFloat() / total else null) {
        if (!progressActive) return
        currentProgress.value = ImportProgress(stage, item, done, total, fraction?.coerceIn(0f, 1f))
    }

    private inline fun <T> importTask(block: () -> T): T {
        progressActive = true
        return try { block() } finally { progressActive = false; currentProgress.value = null }
    }

    var lastImportErrors: List<String> = emptyList()
        private set
    var lastImportWarnings: List<String> = emptyList()
        private set

    suspend fun importFiles(uris: List<Uri>, mode: LibraryMode, rule: String, mimeType: String? = null,
                            onBookImported: suspend (Book) -> Unit = {}): List<Book> = withContext(Dispatchers.IO) {
        val work = currentCoroutineContext()
        importTask {
            if (mode == LibraryMode.NOVEL) TextParser.validateRule(rule)
            report("检查所选文件", total = uris.size)
            lastImportErrors = emptyList()
            lastImportWarnings = emptyList()
            val books = mutableListOf<Book>()
            val failures = mutableListOf<String>()
            val sources = mutableListOf<SourceItem>()
            uris.forEachIndexed { index, uri ->
                report("检查所选文件", uri.lastPathSegment.orEmpty(), index, uris.size)
                runCatching { sourceInfo(uri) }.onSuccess {
                    sources += if (!it.isDirectory && (it.mime.isBlank() || it.mime == "application/octet-stream") &&
                        mimeType != null && supportedMimeHint(mimeType)) it.copy(mime = mimeType) else it
                }
                    .onFailure { rethrowCancellation(it); failures += "${uri.lastPathSegment.orEmpty()}：${it.message ?: "无法读取文件信息"}" }
                report("检查所选文件", uri.lastPathSegment.orEmpty(), index + 1, uris.size)
            }
            val imageSources = sources.filter { !it.isDirectory && kind(it) == BookFormat.IMAGES }
            val imageUris = imageSources.map { it.uri }
            if (imageUris.isNotEmpty()) {
                val imported = runCatching { importImages(imageUris, LibraryMode.COMIC) }
                    .onFailure { rethrowCancellation(it); failures += "图片：${it.message ?: "导入失败"}" }.getOrNull()
                if (imported != null) {
                    onBookImported(imported)
                    books += imported
                }
            }
            sources.filterNot { it in imageSources }.forEach { item ->
                work.ensureActive()
                val imported = runCatching {
                    if (item.isDirectory) return@runCatching importComicFolder(item)
                    val format = kind(item) ?: throw IllegalArgumentException("不支持的文件格式")
                    val id = UUID.randomUUID().toString()
                    val directory = File(context.filesDir, "books/$id").apply { mkdirs() }
                    try {
                        report("读取源文件", item.name)
                        val reference = reference(item, directory, "source.${format.name.lowercase()}", retainFallback = format == BookFormat.PDF)
                        val imported = withLocalDocument(context, reference.path) { source ->
                            when (format) {
                                BookFormat.TXT -> importText(source, directory, id, item.name.substringBeforeLast('.'), LibraryMode.NOVEL, rule)
                                BookFormat.EPUB -> importEpub(source, directory, id, item.name.substringBeforeLast('.'), mode)
                                BookFormat.MOBI -> importMobi(source, directory, id, item.name.substringBeforeLast('.'), rule, { work.ensureActive() })
                                BookFormat.PDF -> importPdf(source, reference.path, directory, id, item.name.substringBeforeLast('.'), mode)
                                BookFormat.IMAGES -> error("图片应合并导入")
                            }
                        }
                        imported.copy(sourcePath = documentFile(reference.path)?.absolutePath,
                            sourceUri = item.permissionUri.toString(), sourceLinked = reference.linked)
                    } catch (error: Throwable) {
                        directory.deleteRecursively()
                        throw error
                    }
                }.onFailure { rethrowCancellation(it); failures += "${item.name}：${it.message ?: "导入失败"}" }.getOrNull()
                if (imported != null) {
                    onBookImported(imported)
                    books += imported
                }
            }
            lastImportErrors = failures
            if (books.isEmpty() && failures.isNotEmpty()) throw IllegalArgumentException(failures.joinToString("\n"))
            books
        }
    }

    suspend fun importFolder(uri: Uri, rule: String): Book = withContext(Dispatchers.IO) {
        importTask {
            report("扫描漫画文件夹")
            lastImportErrors = emptyList()
            lastImportWarnings = emptyList()
            importComicFolder(sourceInfo(uri))
        }
    }

    private fun importComicFolder(root: SourceItem): Book {
        require(root.isDirectory) { "请选择包含图片或 PDF 的漫画文件夹" }
        val id = UUID.randomUUID().toString()
        val directory = File(context.filesDir, "books/$id").apply { mkdirs() }
        try {
            val linkedRoot = retainDocumentReadPermission(context, root.permissionUri)
            if (!linkedRoot) warn("${root.name}：来源未提供持久读取授权，漫画页面使用离线副本")
            val title = root.name
            val tree = mutableMapOf<String, List<SourceItem>>()
            var discovered = 0
            fun scan(folder: SourceItem) {
                if (folder.uri.toString() in tree) return
                val entries = children(folder)
                tree[folder.uri.toString()] = entries
                entries.forEach { item ->
                    if (item.isDirectory) scan(item) else if (isImage(item.name, item.mime) || isPdf(item.name, item.mime)) {
                        discovered++
                        report("扫描漫画文件夹", item.name, discovered)
                    }
                }
            }
            report("扫描漫画文件夹", root.name)
            scan(root)
            val visited = mutableSetOf<String>()
            var fileIndex = 0
            var completed = 0
            fun importImage(item: SourceItem): PageSource {
                report("整理漫画页面", item.name, completed, discovered)
                val source = reference(item, directory, "image-${fileIndex++}.${imageExtension(item.name)}", inheritedGrant = linkedRoot)
                validateImage(source.path)
                report("整理漫画页面", item.name, ++completed, discovered)
                return PageSource(source.path)
            }
            fun importPdfPages(item: SourceItem): List<PageSource> {
                report("统计漫画 PDF 页数", item.name)
                val source = reference(item, directory, "document-${fileIndex++}.pdf", inheritedGrant = linkedRoot)
                val pages = comicPdfPages(source.path) { done, total ->
                    report("整理漫画页面", item.name, completed, discovered,
                        (completed + done.toFloat() / total) / discovered.coerceAtLeast(1))
                }
                report("整理漫画页面", item.name, ++completed, discovered)
                return pages
            }
            fun collect(folder: SourceItem): ComicHierarchyNode {
                if (!visited.add(folder.uri.toString())) return ComicHierarchyNode(folder.name)
                val entries = tree[folder.uri.toString()].orEmpty()
                val pages = entries.filter { !it.isDirectory && isImage(it.name, it.mime) }.map(::importImage)
                val branches = entries.filter { it.isDirectory || isPdf(it.name, it.mime) }.map { item ->
                    if (item.isDirectory) collect(item)
                    else ComicHierarchyNode(item.name.substringBeforeLast('.', item.name), importPdfPages(item))
                }
                return ComicHierarchyNode(folder.name, pages, branches)
            }
            val chapters = ComicHierarchyPlanner.chapters(collect(root))
            require(chapters.isNotEmpty()) { "文件夹中没有可阅读的图片或 PDF" }
            val first = chapters.first().pages.first()
            report("生成第一页封面", root.name)
            val cover = cover(first, directory)
            report("漫画整理完成", root.name, completed, discovered, 1f)
            return Book(id = id, title = title, format = BookFormat.IMAGES, mode = LibraryMode.COMIC,
                coverPath = cover, chapters = chapters, sourcePath = documentFile(root.uri.toString())?.absolutePath,
                sourceUri = root.permissionUri.toString(), sourceLinked = linkedRoot)
        } catch (error: Throwable) {
            directory.deleteRecursively()
            throw error
        }
    }

    /** Upgrades parsed EPUB/MOBI content; existing private originals are retained for compatibility. */
    suspend fun upgradeInlineBooks(state: LibraryState): LibraryState = withContext(Dispatchers.IO) {
        val work = currentCoroutineContext()
        lastImportErrors = emptyList()
        val errors = mutableListOf<String>()
        var annotations = state.annotations
        val books = state.books.map { storedBook ->
            var book = storedBook
            currentCoroutineContext().ensureActive()
            val requiredVersion = if (book.format == BookFormat.MOBI) 7 else 6
            if (!book.cloudOnly && book.format in setOf(BookFormat.EPUB, BookFormat.MOBI)) {
                val cached = runCatching { classifyCachedBook(book, annotations, state.groups) }
                cached.exceptionOrNull()?.let { error ->
                    rethrowCancellation(error)
                    errors += "《${book.title}》图片缓存归类未完成：${error.message ?: error.javaClass.simpleName}"
                    return@map book
                }
                cached.getOrNull()?.let { (classified, migrated) ->
                    annotations = migrated
                    if (classified.mode == LibraryMode.COMIC) return@map classified
                    book = classified
                }
            }
            if (book.cloudOnly || book.hasTextEdits || book.format !in setOf(BookFormat.EPUB, BookFormat.MOBI) || book.contentVersion >= requiredVersion) book else {
                runCatching {
                    val directory = File(context.filesDir, "books/${book.id}").apply { mkdirs() }
                    val parsed = withLocalDocument(context, bookSource(book)) { source ->
                        if (book.format == BookFormat.EPUB) epubTextChapters(source, EpubArchive.read(source, book.title), directory)
                        else mobiTextChapters(source, MobiArchive.read(source, book.title, checkActive = { work.ensureActive() }), directory, state.settings.chapterRule, { work.ensureActive() })
                    }
                    val textDirectory = File(directory, "chapters-inline-${UUID.randomUUID()}").apply { mkdirs() }
                    try {
                        val oldText = book.chapters.map { it.textPath?.let { path -> File(path).readText() }.orEmpty() }
                        val reusable = book.chapters.mapIndexedNotNull { index, chapter ->
                            chapter.textPath?.takeIf { oldText.getOrNull(index) == parsed.getOrNull(index)?.text }
                                ?.let { index to it }
                        }.toMap()
                        val chapters = writeTextChapters(parsed, textDirectory, reusable)
                        val normalized = oldText.map { it.replace("\uFFFC", "").trim() }
                        fun oldOffset(chapter: Int, offset: Int): Int {
                            val text = oldText.getOrNull(chapter).orEmpty()
                            val before = text.take(offset.coerceIn(0, text.length)).replace("\uFFFC", "").length
                            val cleaned = text.replace("\uFFFC", "")
                            val leading = cleaned.length - cleaned.trimStart().length
                            return (before - leading).coerceIn(0, normalized.getOrNull(chapter).orEmpty().length)
                        }
                        fun position(chapter: Int, offset: Int, quote: String = "") = TextParser.relocatePosition(normalized,
                            parsed.map { it.text }, chapter, oldOffset(chapter, offset), quote.replace("\uFFFC", ""))
                        fun legacyImage(chapter: Int, page: Int): Pair<Int, InlineImage>? {
                            if (book.chapters.getOrNull(chapter)?.pages.isNullOrEmpty()) return null
                            val newChapter = chapter.coerceIn(0, chapters.lastIndex)
                            return chapters[newChapter].inlineImages.distinctBy { it.path }.getOrNull(page)?.let { newChapter to it }
                        }
                        val textPosition = position(book.chapterIndex, book.characterOffset)
                        val imagePosition = legacyImage(book.chapterIndex, book.pageIndex) ?: TextParser.relocateImage(parsed,
                            book.positionImagePath, parsed.take(textPosition.first).sumOf { it.text.length.toLong() } + textPosition.second)
                        val updatedAnnotations = annotations.map { annotation ->
                            if (annotation.bookId != book.id) annotation else {
                                val textPosition = position(annotation.chapterIndex, annotation.characterOffset, annotation.quote)
                                val image = legacyImage(annotation.chapterIndex, annotation.pageIndex) ?: TextParser.relocateImage(parsed,
                                    annotation.imagePath, parsed.take(textPosition.first).sumOf { it.text.length.toLong() } + textPosition.second)
                                if (image != null) annotation.copy(chapterIndex = image.first, pageIndex = 0,
                                    characterOffset = image.second.offset, endOffset = image.second.offset, imagePath = image.second.path)
                                else {
                                    val (chapter, offset) = textPosition
                                    val end = if (annotation.endOffset > annotation.characterOffset) position(annotation.chapterIndex, annotation.endOffset) else null
                                    annotation.copy(chapterIndex = chapter, pageIndex = 0, characterOffset = offset,
                                        endOffset = end?.let { if (it.first == chapter) it.second.coerceAtLeast(offset) else parsed[chapter].text.length } ?: 0,
                                        quote = annotation.quote.replace("\uFFFC", ""))
                                }
                            }
                        }
                        val cover = book.coverPath?.let(::File)?.takeIf(::isValidImage)
                            ?.let { BitmapLoader.createThumbnail(it, directory) } ?: book.coverPath
                        val upgraded = book.copy(chapters = chapters, chapterIndex = imagePosition?.first ?: textPosition.first,
                            pageIndex = 0, characterOffset = imagePosition?.second?.offset ?: textPosition.second,
                            positionImagePath = imagePosition?.second?.path ?: book.positionImagePath, coverPath = cover, contentVersion = requiredVersion)
                        val classified = classifyCachedBook(upgraded, updatedAnnotations, state.groups)
                        annotations = classified?.second ?: updatedAnnotations
                        classified?.first ?: upgraded
                    } catch (error: Throwable) {
                        textDirectory.deleteRecursively()
                        throw error
                    }
                }.getOrElse { error ->
                    rethrowCancellation(error)
                    errors += "《${book.title}》目录与正文格式升级未完成：${error.message ?: error.javaClass.simpleName}"
                    if (book.contentVersion == 3) {
                        val cached = runCatching { compactCachedBook(book, annotations) }
                        cached.exceptionOrNull()?.let(::rethrowCancellation)
                        cached.getOrNull()?.let { (compacted, migrated) ->
                            annotations = migrated
                            return@getOrElse compacted
                        }
                    }
                    book
                }
            }
        }
        lastImportErrors = errors
        state.copy(books = books, annotations = annotations)
    }

    private fun classifyCachedBook(book: Book, annotations: List<Annotation>, groups: List<ShelfGroup>): Pair<Book, List<Annotation>>? {
        if (book.chapters.isEmpty()) return null
        if (book.mode == LibraryMode.COMIC && book.chapters.all { it.textPath == null && it.inlineImages.isEmpty() } &&
            book.chapters.any { it.pages.isNotEmpty() }) return book to annotations
        if (book.mode == LibraryMode.NOVEL && book.chapters.none { it.inlineImages.isNotEmpty() || it.pages.isNotEmpty() }) return null
        if (book.chapters.any { it.textPath?.let { path -> !File(path).isFile } == true }) return null
        val hasText = book.chapters.any { chapter -> chapter.textPath?.let { path ->
                File(path).bufferedReader().use { reader ->
                    generateSequence { reader.read().takeIf { it >= 0 } }.any { it != 0xfffc && !it.toChar().isWhitespace() }
                }
            } == true }
        if (hasText) return if (book.mode == LibraryMode.COMIC) book.copy(mode = LibraryMode.NOVEL,
            groupId = book.groupId?.takeIf { id -> groups.any { it.id == id && it.mode == LibraryMode.NOVEL } }) to annotations else null
        if (book.chapters.none { it.inlineImages.isNotEmpty() || it.pages.isNotEmpty() }) return null
        val indexed = book.chapters.mapIndexedNotNull { index, chapter ->
            val pages = chapter.pages.ifEmpty { chapter.inlineImages.map { PageSource(it.path) } }
            pages.takeIf { it.isNotEmpty() }?.let { index to chapter.copy(textPath = null, pages = pages,
                inlineImages = emptyList(), sourceStyles = emptyList()) }
        }
        fun position(chapter: Int, page: Int, offset: Int, imagePath: String?): Pair<Int, Int> {
            val target = indexed.indexOfFirst { it.first >= chapter }.takeIf { it >= 0 } ?: indexed.lastIndex
            val (sourceIndex, source) = indexed[target]
            val matches = source.pages.indices.filter { source.pages[it].path == imagePath }
            val selected = if (sourceIndex != chapter) 0 else matches.firstOrNull { it == page }
                ?: matches.minByOrNull { candidate -> kotlin.math.abs(book.chapters[chapter].inlineImages.getOrNull(candidate)?.offset?.minus(offset) ?: 0) }
                ?: page.coerceIn(0, source.pages.lastIndex)
            return target to selected
        }
        val (chapter, page) = position(book.chapterIndex, book.pageIndex, book.characterOffset, book.positionImagePath)
        val directory = File(context.filesDir, "books/${book.id}").apply { mkdirs() }
        val comic = book.copy(mode = LibraryMode.COMIC, chapters = indexed.map { it.second },
            chapterIndex = chapter, pageIndex = page, characterOffset = 0, positionImagePath = null,
            coverPath = cover(indexed.first().second.pages.first(), directory),
            groupId = book.groupId?.takeIf { id -> groups.any { it.id == id && it.mode == LibraryMode.COMIC } })
        val migrated = annotations.map { note ->
            if (note.bookId != book.id) note else {
                val (noteChapter, notePage) = position(note.chapterIndex, note.pageIndex, note.characterOffset, note.imagePath)
                note.copy(chapterIndex = noteChapter, pageIndex = notePage, characterOffset = 0, endOffset = 0, imagePath = null)
            }
        }
        return comic to migrated
    }

    private fun compactCachedBook(book: Book, annotations: List<Annotation>): Pair<Book, List<Annotation>> {
        val original = book.chapters.map { chapter -> chapter.textPath?.let { File(it).readText() }.orEmpty() }
        val compacted = book.chapters.mapIndexed { index, chapter ->
            TextParser.compactParagraphs(ParsedTextChapter(chapter.title, original[index], chapter.inlineImages, chapter.sourceStyles))
        }
        val directory = File(context.filesDir, "books/${book.id}/chapters-compact-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val chapters = book.chapters.mapIndexed { index, chapter ->
                val parsed = compacted[index].first
                val path = if (parsed.text == original[index]) chapter.textPath else
                    File(directory, "chapter-$index.txt").apply { writeText(parsed.text) }.absolutePath
                chapter.copy(textPath = path, inlineImages = parsed.inlineImages, sourceStyles = parsed.sourceStyles)
            }
            fun offset(chapter: Int, old: Int): Int {
                val map = compacted.getOrNull(chapter)?.second ?: return 0
                return map[old.coerceIn(0, map.lastIndex)]
            }
            val migrated = annotations.map { annotation ->
                if (annotation.bookId != book.id) annotation else {
                    val start = offset(annotation.chapterIndex, annotation.characterOffset)
                    val end = offset(annotation.chapterIndex, annotation.endOffset)
                    val before = original.getOrNull(annotation.chapterIndex).orEmpty()
                    val after = compacted.getOrNull(annotation.chapterIndex)?.first?.text.orEmpty()
                    val quote = if (annotation.endOffset > annotation.characterOffset && annotation.characterOffset >= 0 &&
                        annotation.endOffset <= before.length && before.substring(annotation.characterOffset, annotation.endOffset).trim() == annotation.quote.trim())
                        after.substring(start, end).trim() else annotation.quote
                    annotation.copy(characterOffset = start, endOffset = end, pageIndex = 0, quote = quote)
                }
            }
            return book.copy(chapters = chapters, characterOffset = offset(book.chapterIndex, book.characterOffset),
                pageIndex = 0, contentVersion = 4) to migrated
        } catch (error: Throwable) {
            directory.deleteRecursively()
            throw error
        }
    }

    private fun rethrowCancellation(error: Throwable) { if (error is CancellationException) throw error }

    suspend fun importFont(uri: Uri): ImportedFont = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        val extension = name.substringAfterLast('.', "").lowercase()
        require(extension in setOf("ttf", "otf", "ttc")) { "请选择 TTF、OTF 或 TTC 字体文件" }
        val destination = File(context.filesDir, "fonts/${UUID.randomUUID()}.$extension")
        try {
            copy(uri, destination)
            android.graphics.Typeface.createFromFile(destination)
            ImportedFont(name.substringBeforeLast('.'), destination.absolutePath)
        } catch (error: Throwable) {
            destination.delete()
            rethrowCancellation(error)
            throw IllegalArgumentException("字体无法加载：${error.message}", error)
        }
    }

    suspend fun rebuildChapters(book: Book, rule: String): Book = withContext(Dispatchers.IO) {
        val work = currentCoroutineContext()
        importTask {
            report("重新整理目录", book.title)
            require(book.mode == LibraryMode.NOVEL && book.format in setOf(BookFormat.TXT, BookFormat.MOBI)) { "自定义目录规则适用于 TXT / MOBI 小说" }
            TextParser.validateRule(rule)
            val directory = File(context.filesDir, "books/${book.id}").apply { mkdirs() }
            val parsed = if (book.hasTextEdits) {
                val cached = book.chapters.mapIndexed { index, chapter ->
                    val prepared = loadChapterContent(context, book, index)
                    ParsedTextChapter(chapter.title, prepared.text, prepared.chapter.inlineImages, prepared.chapter.sourceStyles)
                }
                splitEditedTextChapters(cached, rule)
            } else withLocalDocument(context, bookSource(book)) { source ->
                if (book.format == BookFormat.MOBI) mobiTextChapters(source, MobiArchive.read(source, book.title, checkActive = { work.ensureActive() }) {
                    report(if (it >= .96f) "整理 MOBI 目录" else "读取 MOBI 正文记录", book.title, fraction = it)
                }, directory, rule, { work.ensureActive() })
                else TextParser.split(TextParser.decode(source.readBytes()), rule)
            }
            val oldText = book.chapters.map { it.textPath?.let { path -> File(path).readText() }.orEmpty() }
            val newFolder = File(directory, "chapters-${UUID.randomUUID()}").apply { mkdirs() }
            val chapters = try {
                writeTextChapters(parsed, newFolder)
            } catch (error: Throwable) {
                newFolder.deleteRecursively()
                throw error
            }
            // Old chapter files stay available until the caller has atomically saved the new state.
            val (chapterIndex, offset) = TextParser.relocatePosition(oldText, parsed.map { it.text },
                book.chapterIndex, book.characterOffset)
            val image = TextParser.relocateImage(parsed, book.positionImagePath,
                parsed.take(chapterIndex).sumOf { it.text.length.toLong() } + offset)
            book.copy(chapters = chapters, chapterIndex = image?.first ?: chapterIndex, pageIndex = 0,
                characterOffset = image?.second?.offset ?: offset, positionImagePath = image?.second?.path ?: book.positionImagePath,
                contentVersion = if (book.hasTextEdits) book.contentVersion + 1 else if (book.format == BookFormat.MOBI) 7 else book.contentVersion)
        }
    }

    private fun importText(source: File, directory: File, id: String, fallbackTitle: String,
                           mode: LibraryMode, rule: String): Book {
        report("解析 TXT 正文", fallbackTitle)
        val text = TextParser.decode(source.readBytes())
        require(text.isNotBlank()) { "TXT 文件为空" }
        val metadata = TextParser.metadata(text)
        return Book(id = id, title = metadata.title ?: fallbackTitle, author = metadata.author,
            format = BookFormat.TXT, mode = mode, chapters = writeTextChapters(TextParser.split(text, rule), directory))
    }

    private fun importEpub(source: File, directory: File, id: String, fallbackTitle: String, mode: LibraryMode): Book {
        val parsed = EpubArchive.read(source, fallbackTitle) { report("读取 EPUB 章节", fallbackTitle, fraction = it) }
        var coverPath: String? = null
        val cover = parsed.coverPath ?: parsed.imagePaths.firstOrNull()
        if (cover != null) {
            val coverFile = File(directory, "cover.${imageExtension(cover)}")
            EpubArchive.copyEntry(source, cover, coverFile)
            if (isValidImage(coverFile)) coverPath = BitmapLoader.createThumbnail(coverFile, directory)
        }
        val content = epubLazyChapters(source, parsed, directory)
        val novel = content.any { it.characterCount > 0 }
        val chapters = if (novel) content else content.filter { it.inlineImages.isNotEmpty() }.map {
            it.copy(textPath = null, pages = it.inlineImages.map { image -> PageSource(image.path) },
                inlineImages = emptyList(), characterCount = 0, lazySourcePath = null)
        }
        require(chapters.isNotEmpty()) { "EPUB 没有可阅读的正文或图片" }
        return Book(id = id, title = parsed.title, author = parsed.author, format = BookFormat.EPUB,
            mode = if (novel) LibraryMode.NOVEL else LibraryMode.COMIC,
            coverPath = if (novel) coverPath else cover(chapters.first().pages.first(), directory), chapters = chapters, contentVersion = 6)
    }

    private fun importPdf(source: File, sourceReference: String, directory: File, id: String, title: String, mode: LibraryMode): Book {
        val document = pdfPages(source, sourceReference, directory, "page")
        if (document.pages.none { it.textPath?.let { path -> File(path).readText().isNotBlank() } == true }) {
            document.pages.mapNotNull { it.textPath }.forEach { File(it).delete() }
            report("漫画整理完成", title, document.pages.size, document.pages.size)
            return Book(id = id, title = title, format = BookFormat.PDF, mode = LibraryMode.COMIC,
                coverPath = pdfCover(source.absolutePath, directory), chapters = listOf(Chapter(title = title,
                    pages = document.pages.map { it.copy(textPath = null) })))
        }
        return Book(id = id, title = document.title.ifBlank { title }, author = document.author,
            format = BookFormat.PDF, mode = LibraryMode.NOVEL, coverPath = pdfCover(source.absolutePath, directory),
            chapters = listOf(Chapter(title = "正文", pages = document.pages)))
    }

    private fun importComicPdf(source: String, directory: File, id: String, name: String): Book {
        report("统计漫画 PDF 页数", name)
        val pages = comicPdfPages(source) { done, total -> report("整理漫画页面", name, done, total) }
        report("生成第一页封面", name)
        val coverPath = pdfCover(source, directory)
        report("漫画整理完成", name, pages.size, pages.size)
        return Book(id = id, title = name, format = BookFormat.PDF, mode = LibraryMode.COMIC,
            coverPath = coverPath, chapters = listOf(Chapter(title = name, pages = pages)))
    }

    private fun comicPdfPages(source: String, onPage: (Int, Int) -> Unit): List<PageSource> {
        val count = withDocumentDescriptor(context, source) { descriptor -> PdfRenderer(descriptor).use { it.pageCount } }
        require(count > 0) { "PDF 没有页面" }
        return List(count) { index ->
            if (index % 64 == 0 || index == count - 1) onPage(index + 1, count)
            PageSource(source, index)
        }
    }

    private fun importMobi(source: File, directory: File, id: String, fallbackTitle: String,
                           rule: String, checkActive: () -> Unit): Book {
        val parsed = MobiArchive.read(source, fallbackTitle, checkActive = checkActive) {
            report(if (it >= .96f) "整理 MOBI 目录" else "解压 MOBI 正文记录", fallbackTitle, fraction = it)
        }
        val coverPath = parsed.cover?.let { cover ->
            File(directory, "cover.${cover.extension}").apply {
                writeBytes(cover.bytes)
                validateImage(this)
            }.let { BitmapLoader.createThumbnail(it, directory) }
        }
        val sections = if (rule != DEFAULT_CHAPTER_RULE || parsed.sections.size == 1 && !parsed.hasEmbeddedToc)
            writeTextChapters(mobiTextChapters(source, parsed, directory, rule, checkActive), directory)
        else mobiLazyChapters(source, parsed, directory, checkActive)
        val novel = sections.any { it.characterCount > 0 }
        val chapters = if (novel) sections else sections.filter { it.inlineImages.isNotEmpty() }.map {
            it.copy(textPath = null, pages = it.inlineImages.map { image -> PageSource(image.path) },
                inlineImages = emptyList(), characterCount = 0, lazySourcePath = null)
        }
        require(chapters.isNotEmpty()) { "MOBI 没有可阅读的正文或图片" }
        return Book(id = id, title = parsed.title, author = parsed.author, format = BookFormat.MOBI,
            mode = if (novel) LibraryMode.NOVEL else LibraryMode.COMIC,
            coverPath = if (novel) coverPath else cover(chapters.first().pages.first(), directory), chapters = chapters, contentVersion = 7)
    }

    private fun epubLazyChapters(source: File, parsed: ParsedEpub, directory: File): List<Chapter> {
        val cache = mutableMapOf<String, String>()
        val available = parsed.imagePaths.toSet()
        return parsed.sections.mapIndexed { index, section ->
            report("缓存 EPUB 章节", section.title, index, parsed.sections.size)
            val images = mutableMapOf<String, String>()
            val plain = HtmlChapterParser.readPlain(section.title, section.html) { attributes ->
                val reference = HtmlChapterParser.attribute(attributes, "src") ?: HtmlChapterParser.attribute(attributes, "xlink:href")
                    ?: HtmlChapterParser.attribute(attributes, "href")
                val path = reference?.let { runCatching { EpubArchive.resolve(section.path, it) }.getOrNull() }
                if (path == null || path !in available) null else cache.getOrPut(path) {
                    val key = MessageDigest.getInstance("SHA-256").digest(path.toByteArray()).take(10).joinToString("") { "%02x".format(it) }
                    val image = File(directory, "epub-inline-$key.${imageExtension(path)}")
                    EpubArchive.copyEntry(source, path, image)
                    image.absolutePath
                }.also { images[attributes] = it }
            }
            saveLazyChapter(directory, index, plain, section.html, section.css, images)
        }
    }

    private fun mobiLazyChapters(source: File, parsed: ParsedMobi, directory: File, checkActive: () -> Unit): List<Chapter> {
        val resources = parsed.images.associateBy { it.recIndex }
        val cache = mutableMapOf<Int, String>()
        return parsed.sections.mapIndexed { index, section ->
            report("缓存 MOBI 章节", section.title, index, parsed.sections.size)
            val images = mutableMapOf<String, String>()
            val plain = HtmlChapterParser.readPlain(section.title, section.html, checkActive) { attributes ->
                val reference = HtmlChapterParser.attribute(attributes, "recindex")?.toIntOrNull()
                    ?: (HtmlChapterParser.attribute(attributes, "src") ?: HtmlChapterParser.attribute(attributes, "xlink:href")
                        ?: HtmlChapterParser.attribute(attributes, "href"))?.let { src ->
                        Regex("kindle:embed:([0-9a-v]+)", RegexOption.IGNORE_CASE).find(src)?.groupValues?.get(1)?.toIntOrNull(32)
                    }
                val resource = reference?.let(resources::get)
                if (resource == null) null else cache.getOrPut(resource.recIndex) {
                    val image = File(directory, "mobi-inline-${resource.recIndex}.${resource.extension}")
                    MobiArchive.copyImage(source, resource, image)
                    image.absolutePath
                }.also { images[attributes] = it }
            }
            saveLazyChapter(directory, index, plain, section.html, parsed.css, images)
        }.filter { it.characterCount > 0 || it.inlineImages.isNotEmpty() }
    }

    private fun epubTextChapters(source: File, parsed: ParsedEpub, directory: File): List<ParsedTextChapter> {
        val cache = mutableMapOf<String, String>()
        val available = parsed.imagePaths.toSet()
        return parsed.sections.mapIndexed { index, section ->
            report("解析 EPUB 章节", section.title, index, parsed.sections.size)
            HtmlChapterParser.read(section.title, section.html, css = section.css) { attributes ->
                val reference = HtmlChapterParser.attribute(attributes, "src") ?: HtmlChapterParser.attribute(attributes, "xlink:href")
                    ?: HtmlChapterParser.attribute(attributes, "href")
                val path = reference?.let { runCatching { EpubArchive.resolve(section.path, it) }.getOrNull() }
                if (path == null || path !in available) null else cache.getOrPut(path) {
                    val key = MessageDigest.getInstance("SHA-256").digest(path.toByteArray()).take(10).joinToString("") { "%02x".format(it) }
                    val image = File(directory, "epub-inline-$key.${imageExtension(path)}")
                    if (!image.isFile || image.length() == 0L) try {
                        EpubArchive.copyEntry(source, path, image)
                    } catch (error: Throwable) {
                        image.delete()
                        throw error
                    }
                    image.absolutePath
                }
            }
        }
    }

    private fun mobiTextChapters(source: File, parsed: ParsedMobi, directory: File, rule: String, checkActive: () -> Unit = {}): List<ParsedTextChapter> {
        val resources = parsed.images.associateBy { it.recIndex }
        val cache = mutableMapOf<Int, String>()
        val content = parsed.sections.mapIndexed { index, section ->
            report("解析 MOBI 章节", section.title, index, parsed.sections.size)
            HtmlChapterParser.read(section.title, section.html, css = parsed.css, checkActive = checkActive) { attributes ->
                val reference = HtmlChapterParser.attribute(attributes, "recindex")?.toIntOrNull()
                    ?: (HtmlChapterParser.attribute(attributes, "src") ?: HtmlChapterParser.attribute(attributes, "xlink:href")
                        ?: HtmlChapterParser.attribute(attributes, "href"))?.let { src ->
                        Regex("kindle:embed:([0-9a-v]+)", RegexOption.IGNORE_CASE).find(src)?.groupValues?.get(1)?.toIntOrNull(32)
                    }
                val resource = reference?.let(resources::get)
                if (resource == null) null else cache.getOrPut(resource.recIndex) {
                    val image = File(directory, "mobi-inline-${resource.recIndex}.${resource.extension}")
                    if (!image.isFile || image.length() == 0L) try {
                        MobiArchive.copyImage(source, resource, image)
                    } catch (error: Throwable) {
                        image.delete()
                        throw error
                    }
                    image.absolutePath
                }
            }
        }
        val sections = (if (content.none { it.text.isNotBlank() }) content
            else if (rule != DEFAULT_CHAPTER_RULE) HtmlChapterParser.split(HtmlChapterParser.merge(content), rule)
            else if (content.size == 1 && !parsed.hasEmbeddedToc) HtmlChapterParser.split(content.single(), rule) else content)
            .filter { it.text.isNotBlank() || it.inlineImages.isNotEmpty() }
        require(sections.isNotEmpty()) { "MOBI 没有可阅读的正文或图片" }
        return sections
    }

    private fun importImages(uris: List<Uri>, mode: LibraryMode): Book {
        val id = UUID.randomUUID().toString()
        val directory = File(context.filesDir, "books/$id").apply { mkdirs() }
        try {
            val ordered = uris.sortedWith { a, b -> NaturalOrder.compare(displayName(a), displayName(b)) }
            val references = ordered.mapIndexed { index, uri ->
                report("整理图片", displayName(uri), index, ordered.size)
                reference(sourceInfo(uri), directory, "image-$index.${imageExtension(displayName(uri))}")
            }
            val pages = references.mapIndexed { index, source ->
                validateImage(source.path)
                report("整理图片", displayName(ordered[index]), index + 1, ordered.size)
                PageSource(source.path)
            }
            val linkedIndex = references.indexOfFirst { it.linked }.coerceAtLeast(0)
            report("生成第一页封面", displayName(ordered.first()))
            return Book(id = id, title = displayName(ordered.first()).substringBeforeLast('.'),
                format = BookFormat.IMAGES, mode = mode, coverPath = cover(pages.first(), directory),
                chapters = listOf(Chapter(title = "正文", pages = pages)),
                sourcePath = documentFile(references[linkedIndex].path)?.absolutePath,
                sourceUri = ordered[linkedIndex].toString(), sourceLinked = references.any { it.linked })
        } catch (error: Throwable) {
            directory.deleteRecursively()
            throw error
        }
    }

    private data class PdfData(val title: String, val author: String, val pages: List<PageSource>)

    private fun pdfPages(source: File, sourceReference: String, directory: File, prefix: String): PdfData {
        val count = withDocumentDescriptor(context, source.absolutePath) { descriptor ->
            PdfRenderer(descriptor).use { it.pageCount }
        }
        require(count > 0) { "PDF 没有页面" }
        PDFBoxResourceLoader.init(context.applicationContext)
        report("准备 PDF 文字层")
        return PDDocument.load(source, MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)).use { document ->
            val stripper = PDFTextStripper().apply { sortByPosition = true }
            val pages = (0 until count).map { index ->
                report("读取 PDF 文字层", "第 ${index + 1} 页", index, count)
                stripper.startPage = index + 1
                stripper.endPage = index + 1
                val text = stripper.getText(document).trim()
                val textFile = File(directory, "$prefix-$index.txt").apply { writeText(text) }
                report("读取 PDF 文字层", "第 ${index + 1} 页", index + 1, count)
                PageSource(sourceReference, index, textFile.absolutePath)
            }
            PdfData(document.documentInformation.title?.trim().orEmpty(),
                document.documentInformation.author?.trim().orEmpty(), pages)
        }
    }

    private fun pdfCover(source: String, directory: File): String {
        val destination = File(directory, "cover-${UUID.randomUUID()}.jpg")
        withDocumentDescriptor(context, source) { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(0).use { page ->
                    val scale = minOf(480f / page.width, 720f / page.height)
                    val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1),
                        (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    try {
                        Canvas(bitmap).drawColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        destination.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                    } finally { bitmap.recycle() }
                }
            }
        }
        return destination.absolutePath
    }

    private fun writeTextChapters(chapters: List<ParsedTextChapter>, directory: File, reusable: Map<Int, String> = emptyMap()): List<Chapter> =
        chapters.mapIndexed { index, chapter ->
            report("保存阅读章节", chapter.title, index, chapters.size)
            val path = reusable[index] ?: File(directory, "chapter-$index.txt").apply { writeText(chapter.text) }.absolutePath
            report("保存阅读章节", chapter.title, index + 1, chapters.size)
            Chapter(title = chapter.title, textPath = path, inlineImages = chapter.inlineImages,
                sourceStyles = chapter.sourceStyles, characterCount = chapter.text.length)
        }

    private data class SourceReference(val path: String, val linked: Boolean)

    private fun reference(item: SourceItem, directory: File, name: String, retainFallback: Boolean = true,
                          inheritedGrant: Boolean? = null): SourceReference {
        val linked = inheritedGrant ?: retainDocumentReadPermission(context, item.permissionUri)
        val original = documentFile(item.uri.toString())?.absolutePath ?: item.uri.toString()
        if (linked) return SourceReference(original, true)
        if (!retainFallback) {
            warn("临时分享授权无法持久保存，已仅缓存解析正文；重新整理目录时可能需要重新选择原文件")
            return SourceReference(original, false)
        }
        val destination = File(directory, name)
        copy(item.uri, destination)
        if (inheritedGrant == null) warn("来源未提供持久读取授权，已为 PDF / 图片保留必要离线副本")
        return SourceReference(destination.absolutePath, false)
    }

    private fun warn(message: String) { if (message !in lastImportWarnings) lastImportWarnings += message }

    private fun bookSource(book: Book): String =
        (if (book.sourceLinked) book.sourceUri ?: book.sourcePath else book.sourcePath ?: book.sourceUri)
            ?: throw IllegalArgumentException("原始文件不存在，请重新选择")

    private fun cover(page: PageSource, directory: File): String =
        if (page.pdfPage >= 0) pdfCover(page.path, directory)
        else withLocalDocument(context, page.path) { BitmapLoader.createThumbnail(it, directory) }

    private fun copy(uri: Uri, destination: File) {
        destination.parentFile?.mkdirs()
        val item = currentProgress.value?.currentItem.orEmpty()
        val total = documentFile(uri.toString())?.length() ?: runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
            } ?: -1L
        }.getOrDefault(-1L)
        val input = openDocumentStream(context, uri.toString())
        input.use { source -> destination.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            var copied = 0L
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                copied += count
                report("复制临时来源", "$item · 已复制 ${copied / 1024} KB",
                    fraction = if (total > 0) copied.toFloat() / total else null)
            }
        } }
        require(destination.length() > 0) { "文件为空" }
    }

    private fun displayName(uri: Uri): String = sourceInfo(uri).name

    private fun kind(item: SourceItem): BookFormat? {
        val name = item.name
        val mime = item.mime
        return when {
            name.endsWith(".epub", true) || mime == "application/epub+zip" -> BookFormat.EPUB
            name.endsWith(".mobi", true) || mime in setOf("application/x-mobipocket-ebook", "application/vnd.amazon.ebook") -> BookFormat.MOBI
            name.endsWith(".txt", true) || mime == "text/plain" -> BookFormat.TXT
            isPdf(name, mime) -> BookFormat.PDF
            isImage(name, mime) -> BookFormat.IMAGES
            else -> null
        }
    }

    private fun isPdf(name: String, mime: String) = name.endsWith(".pdf", true) || mime == "application/pdf"
    private fun supportedMimeHint(mime: String) = mime in DIRECTORY_MIME_TYPES || mime.startsWith("image/") ||
        mime in setOf("text/plain", "application/pdf", "application/epub+zip", "application/x-mobipocket-ebook", "application/vnd.amazon.ebook")
    private fun isImage(name: String, mime: String) = mime.startsWith("image/") ||
        name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")
    private fun imageExtension(name: String) = name.substringAfterLast('.', "img").lowercase()
        .takeIf { it.matches(Regex("[a-z0-9]{1,5}")) } ?: "img"
    private fun isValidImage(file: File): Boolean {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return options.outWidth > 0 && options.outHeight > 0
    }
    private fun validateImage(file: File) { require(isValidImage(file)) { "图片损坏或系统不支持此格式" } }
    private fun validateImage(source: String) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openDocumentStream(context, source).use { BitmapFactory.decodeStream(it, null, options) }
        require(options.outWidth > 0 && options.outHeight > 0) { "图片损坏或系统不支持此格式" }
    }

    private data class SourceItem(val id: String?, val name: String, val mime: String, val uri: Uri,
                                  val flags: Int = 0, val treeRoot: Boolean = false, val permissionUri: Uri = uri) {
        val isDirectory: Boolean get() = treeRoot || mime in DIRECTORY_MIME_TYPES ||
            flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0
    }

    private fun sourceInfo(uri: Uri): SourceItem = sourceRead {
        if (uri.scheme == "file" || uri.scheme == null) {
            val file = File(requireNotNull(uri.path) { "文件路径无效" }).canonicalFile
            return@sourceRead SourceItem(file.path, file.name, if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "",
                Uri.fromFile(file), permissionUri = uri)
        }
        val treeRoot = DocumentsContract.isTreeUri(uri) && "document" !in uri.pathSegments
        val document = if (treeRoot) DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri)) else uri
        var name: String? = null
        var mime: String? = null
        var id: String? = null
        var flags = 0
        context.contentResolver.query(document, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                fun string(column: String): String? = cursor.getColumnIndex(column).takeIf { it >= 0 }
                    ?.let { cursor.getString(it) }?.takeIf { it.isNotBlank() }
                name = string(OpenableColumns.DISPLAY_NAME)
                mime = string(DocumentsContract.Document.COLUMN_MIME_TYPE)
                id = string(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                flags = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS).takeIf { it >= 0 }
                    ?.let { cursor.getInt(it) } ?: 0
            }
        }
        val type = mime ?: context.contentResolver.getType(document).orEmpty()
        SourceItem(id ?: runCatching { DocumentsContract.getDocumentId(document) }.getOrNull(),
            name ?: document.lastPathSegment?.substringAfterLast('/') ?: "未命名文件", type, document, flags, treeRoot, uri)
    }

    private fun children(parent: SourceItem): List<SourceItem> {
        if (parent.uri.scheme == "file") {
            val files = File(requireNotNull(parent.uri.path)).listFiles() ?: throw IllegalArgumentException("无法访问漫画文件夹")
            return files.map { sourceInfo(Uri.fromFile(it)) }.sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
        }
        val parentId = parent.id ?: throw IllegalArgumentException("此目录来源不支持列出文件")
        val tree = DocumentsContract.isTreeUri(parent.uri)
        val authority = requireNotNull(parent.uri.authority) { "目录来源无效" }
        val childUri = if (tree) DocumentsContract.buildChildDocumentsUriUsingTree(parent.uri, parentId)
            else DocumentsContract.buildChildDocumentsUri(authority, parentId)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS)
        val result = mutableListOf<SourceItem>()
        val cursor = context.contentResolver.query(childUri, columns, null, null, null)
            ?: throw IllegalArgumentException("无法访问漫画文件夹")
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getString(it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
                fun string(column: String): String = it.getColumnIndex(column).takeIf { index -> index >= 0 }
                    ?.let { index -> it.getString(index) }.orEmpty()
                val flags = it.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS).takeIf { index -> index >= 0 }
                    ?.let { index -> it.getInt(index) } ?: 0
                result += SourceItem(id, string(DocumentsContract.Document.COLUMN_DISPLAY_NAME).ifBlank { id.substringAfterLast('/') },
                    string(DocumentsContract.Document.COLUMN_MIME_TYPE),
                    if (tree) DocumentsContract.buildDocumentUriUsingTree(parent.uri, id) else DocumentsContract.buildDocumentUri(authority, id), flags)
            }
        }
        return result.sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
    }

    private companion object {
        val DIRECTORY_MIME_TYPES = setOf(DocumentsContract.Document.MIME_TYPE_DIR, "inode/directory", "resource/folder")
    }
}
