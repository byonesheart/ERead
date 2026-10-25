package com.eyue.reader.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class SourceWritePermissionRequiredException(val source: String) : IOException("请重新选择这本书的原文件并授予写入权限，修改尚未保存")

/** A short-lived transaction. The source is replaced only after the rebuilt archive has passed a full read. */
internal class EbookSourceWriteback private constructor(
    private val context: Context,
    private val source: String,
    private val backup: File,
    private val prepared: File
) {
    private var committed = false
    var recoveryRequired = false
        private set

    fun commit(onProgress: (String, Float) -> Unit = { _, _ -> }) {
        val progress = Progress(onProgress)
        progress.report("检查源文件", 0f)
        val originalHash = fileHash(backup)
        require(openDocumentStream(context, source).use { input ->
            hash(input) { copied -> progress.report("检查源文件", .15f * copied / backup.length().coerceAtLeast(1)) }
        } == originalHash) { "源文件在编辑期间发生变化，修改未保存，请重新导入" }
        try {
            progress.report("写入源文件", .15f)
            replace(prepared) { copied -> progress.report("写入源文件", .15f + .65f * copied / prepared.length().coerceAtLeast(1)) }
            progress.report("校验保存结果", .8f)
            val expectedHash = fileHash(prepared)
            check(openDocumentStream(context, source).use { input ->
                hash(input) { copied -> progress.report("校验保存结果", .8f + .19f * copied / prepared.length().coerceAtLeast(1)) }
            } == expectedHash) { "源文件写入校验失败" }
            committed = true
        } catch (error: Exception) {
            restoreBackup()
            throw error
        }
        progress.report("保存完成", 1f)
    }

    fun rollback() {
        if (committed) {
            restoreBackup()
            committed = false
        }
    }

    private fun restoreBackup() {
        try {
            replace(backup)
            check(documentHash(context, source) == fileHash(backup)) { "原文件恢复校验失败" }
        } catch (error: Exception) {
            recoveryRequired = true
            throw IOException("无法恢复原文件，原文件暂存于 ${backup.absolutePath}", error)
        }
    }

    fun finish() { backup.delete(); prepared.delete() }

    private fun replace(file: File, onCopied: (Long) -> Unit = {}) {
        documentFile(source)?.let { target ->
            val staging = File.createTempFile(".eyue-edit-", ".tmp", target.absoluteFile.parentFile)
            try {
                file.inputStream().use { input -> staging.outputStream().use { copy(input, it, onCopied = onCopied) } }
                try { Files.move(staging.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
                catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(staging.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally { staging.delete() }
            return
        }
        context.contentResolver.openOutputStream(Uri.parse(source), "rwt")?.use { output ->
            file.inputStream().use { copy(it, output, onCopied = onCopied) }
        } ?: throw IOException("无法打开源文件进行写入")
    }

    companion object {
        fun source(book: Book): String =
            (if (book.format == BookFormat.EPUB && !book.sourceLinked && book.sourcePath != null &&
                File(File(book.sourcePath).parentFile, "readium-publication.json").let { it.isFile || File(it.path + ".bak").isFile })
                book.sourcePath else book.sourceUri ?: book.sourcePath)
                ?: throw IOException("原文件不存在，请重新选择")

        fun requireWritable(context: Context, source: String) {
            try {
                documentFile(source)?.let { file ->
                    if (!file.isFile || !file.canWrite() || file.absoluteFile.parentFile?.canWrite() != true)
                        throw SourceWritePermissionRequiredException(source)
                    return
                }
                context.contentResolver.openFileDescriptor(Uri.parse(source), "rw")?.use { }
                    ?: throw SourceWritePermissionRequiredException(source)
            } catch (_: SecurityException) { throw SourceWritePermissionRequiredException(source) }
              catch (_: java.io.FileNotFoundException) { throw SourceWritePermissionRequiredException(source) }
        }

        fun sameDocument(context: Context, first: String, second: String): Boolean =
            documentHash(context, first) == documentHash(context, second)

        fun prepare(context: Context, before: Book, edited: Book, directory: File,
                    onProgress: (String, Float) -> Unit = { _, _ -> },
                    checkActive: () -> Unit = {}): EbookSourceWriteback {
            checkActive()
            val progress = Progress(onProgress)
            progress.report("读取原文件", 0f)
            val source = source(before)
            requireWritable(context, source)
            val backup = File(directory, "original.${before.format.name.lowercase()}")
            val sourceBytes = documentFile(source)?.length() ?: 0L
            openDocumentStream(context, source).use { input -> backup.outputStream().use { output ->
                copy(input, output, checkActive) { copied ->
                    if (sourceBytes > 0) progress.report("读取原文件", .1f * copied / sourceBytes)
                }
            } }
            progress.report("读取原文件", .1f)
            val prepared = File(directory, "prepared.${before.format.name.lowercase()}")
            when (before.format) {
                BookFormat.TXT -> txt(prepared, edited, progress, checkActive)
                BookFormat.EPUB -> epub(context, backup, prepared, before, edited, progress, checkActive)
                BookFormat.MOBI -> mobi(backup, prepared, before, edited, progress, checkActive)
                else -> error("不支持覆盖此格式")
            }
            progress.report("文件准备完成", 1f)
            return EbookSourceWriteback(context, source, backup, prepared)
        }

        private fun txt(destination: File, edited: Book, progress: Progress, checkActive: () -> Unit) {
            val text = File(requireNotNull(edited.sourcePath) { "修改后的 TXT 正文不存在" })
            require(text.isFile) { "修改后的 TXT 正文不存在" }
            progress.report("准备 TXT 正文", .1f)
            text.inputStream().use { input -> destination.outputStream().use { output ->
                copy(input, output, checkActive) { copied ->
                    progress.report("准备 TXT 正文", .1f + .7f * copied / text.length().coerceAtLeast(1))
                }
            } }
            checkActive()
            progress.report("校验 TXT 正文", .8f)
            val expected = text.readText(Charsets.UTF_8)
            checkActive()
            check(destination.readText(Charsets.UTF_8) == expected && fileHash(destination) == fileHash(text)) {
                "TXT 正文校验失败，原文件未修改"
            }
        }

        private fun epub(context: Context, source: File, destination: File, before: Book, edited: Book, progress: Progress, checkActive: () -> Unit) {
            val legacyIndex = LegacyEpubIndex.read(context, before)
            val parsed = legacyIndex?.snapshot(source, before, checkActive) ?: EpubArchive.read(source, onProgress = {
                checkActive(); progress.report("读取 EPUB 正文", .1f + .15f * it)
            })
            require(parsed.sections.size == edited.chapters.size) { "源文件目录与书架记录不一致，修改未保存，请重新导入" }
            val validImages = parsed.imagePaths.toSet()
            fun image(path: String): (String) -> String? = { attributes ->
                val reference = HtmlChapterParser.attribute(attributes, "src") ?: HtmlChapterParser.attribute(attributes, "xlink:href")
                    ?: HtmlChapterParser.attribute(attributes, "href")
                if (reference != null && runCatching { EpubArchive.resolve(path, reference) in validImages }.getOrDefault(false)) "image" else null
            }
            val changes = mutableMapOf<String, MutableList<SourceHtmlEdit.Change>>()
            val expected = edited.chapters.map { File(requireNotNull(it.textPath)).readText() }
            ZipFile(source).use { zip ->
                parsed.sections.forEachIndexed { index, section ->
                    checkActive()
                    progress.report("处理 EPUB 第 ${index + 1} 章", .25f + .4f * index / parsed.sections.size.coerceAtLeast(1))
                    val old = SourceHtmlEdit.plain(section.html, checkActive, image(section.path))
                    val cached = File(requireNotNull(before.chapters[index].textPath)).readText()
                    require(before.hasTextEdits || old == cached) { "源文件正文已变化，修改未保存，请重新导入" }
                    val result = SourceHtmlEdit.replace(section.html, expected[index], checkActive, image(section.path))
                    if (result.changes.isNotEmpty()) {
                        val raw = zip.getInputStream(zip.getEntry(section.path)).use {
                            if (legacyIndex != null) decodeEpubMarkup(it.readBytes()) else decodeMarkup(it.readBytes())
                        }
                        val piece = raw.substring(section.sourceStart, section.sourceEnd)
                        val prefix = section.html.indexOf(piece)
                        require(prefix >= 0) { "EPUB 章节定位失败，修改未保存" }
                        changes.getOrPut(section.path) { mutableListOf() } += result.changes.map { change ->
                            require(change.start >= prefix && change.end <= prefix + piece.length) { "EPUB 章节边界无效，修改未保存" }
                            change.copy(start = change.start - prefix + section.sourceStart, end = change.end - prefix + section.sourceStart)
                        }
                    }
                }
                ZipOutputStream(destination.outputStream().buffered()).use { output ->
                    zip.entries().asSequence().forEachIndexed { index, entry ->
                        checkActive()
                        progress.report("重新打包 EPUB", .65f + .15f * index / zip.size().coerceAtLeast(1))
                        val path = EpubArchive.safePath(entry.name)
                        val edits = changes[path]
                        val bytes = if (edits == null) null else zip.getInputStream(entry).use {
                            val bytes = it.readBytes()
                            val html = if (legacyIndex != null) decodeEpubMarkup(bytes) else decodeMarkup(bytes)
                            SourceHtmlEdit.apply(html, edits).html.replace(Regex("(?i)(encoding\\s*=\\s*['\"])[^'\"]+"), "$1UTF-8")
                                .replace(Regex("(?i)(charset\\s*=\\s*['\"]?)[A-Za-z0-9_-]+"), "$1UTF-8").toByteArray(Charsets.UTF_8)
                        }
                        val next = if (bytes == null) ZipEntry(entry) else ZipEntry(entry.name).apply { time = entry.time; method = ZipEntry.DEFLATED }
                        // Compressed sizes belong to the old compressor, not this output stream.
                        if (next.method == ZipEntry.DEFLATED) next.compressedSize = -1
                        output.putNextEntry(next)
                        if (bytes == null) zip.getInputStream(entry).use { it.copyTo(output) } else output.write(bytes)
                        output.closeEntry()
                    }
                }
            }
            val verified = legacyIndex?.snapshot(destination, before, checkActive) ?: EpubArchive.read(destination, onProgress = {
                checkActive(); progress.report("校验 EPUB 文件", .8f + .1f * it)
            })
            check(verified.title == parsed.title && verified.author == parsed.author && verified.sections.size == parsed.sections.size &&
                verified.imagePaths == parsed.imagePaths) { "EPUB 写入校验失败，原文件未修改" }
            verified.sections.forEachIndexed { index, section ->
                checkActive()
                check(SourceHtmlEdit.plain(section.html, checkActive, image(section.path)) == expected[index]) { "EPUB 正文校验失败，原文件未修改" }
                progress.report("校验 EPUB 第 ${index + 1} 章", .9f + .1f * (index + 1) / verified.sections.size.coerceAtLeast(1))
            }
        }

        private fun mobi(source: File, destination: File, before: Book, edited: Book, progress: Progress, checkActive: () -> Unit) {
            var html = ""
            val parsed = MobiArchive.read(source, checkActive = checkActive, captureHtml = { html = it },
                onProgress = { progress.report("读取 MOBI 正文", .1f + .25f * it) })
            val image: (String) -> String? = { attributes ->
                val reference = HtmlChapterParser.attribute(attributes, "recindex")?.toIntOrNull()
                    ?: (HtmlChapterParser.attribute(attributes, "src") ?: HtmlChapterParser.attribute(attributes, "xlink:href")
                        ?: HtmlChapterParser.attribute(attributes, "href"))?.let { value ->
                        Regex("kindle:embed:([0-9a-v]+)", RegexOption.IGNORE_CASE).find(value)?.groupValues?.get(1)?.toIntOrNull(32)
                    }
                if (reference != null && parsed.images.any { it.recIndex == reference }) "image" else null
            }
            fun canonical(book: Book) = normalizeHtmlText(book.chapters.joinToString("\n\n") {
                File(requireNotNull(it.textPath)).readText()
            }, intArrayOf()).text
            val bySection = parsed.sections.size == before.chapters.size && before.chapters.size == edited.chapters.size &&
                parsed.sections.map { it.title } == before.chapters.map { it.title }
            val targets = if (bySection) edited.chapters.map { File(requireNotNull(it.textPath)).readText() } else emptyList()
            val target = if (bySection) null else canonical(edited)
            val result = if (bySection) {
                // Imported chapters have independent trimmed boundaries. Joining them adds line breaks
                // that need not exist in MOBI (notably pagebreaks and inline TOC anchors).
                val changes = parsed.sections.flatMapIndexed { index, section ->
                    checkActive()
                    progress.report("处理 MOBI 第 ${index + 1} 章", .35f + .3f * index / parsed.sections.size.coerceAtLeast(1))
                    val old = SourceHtmlEdit.plain(section.html, checkActive, image)
                    val cached = File(requireNotNull(before.chapters[index].textPath)).readText()
                    require(before.hasTextEdits || old == cached) { "源文件正文与书架记录不一致，修改未保存，请重新导入" }
                    if (old == targets[index]) emptyList() else {
                        val piece = html.substring(section.sourceStart, section.sourceEnd)
                        val prefix = section.html.indexOf(piece)
                        require(prefix >= 0) { "MOBI 章节定位失败，修改未保存" }
                        SourceHtmlEdit.replace(section.html, targets[index], checkActive, image).changes.map { change ->
                            require(change.start >= prefix && change.end <= prefix + piece.length) { "MOBI 章节边界无效，修改未保存" }
                            change.copy(start = change.start - prefix + section.sourceStart, end = change.end - prefix + section.sourceStart)
                        }
                    }
                }
                SourceHtmlEdit.apply(html, changes)
            } else {
                progress.report("处理 MOBI 正文", .35f)
                val old = SourceHtmlEdit.plain(html, checkActive, image)
                require(before.hasTextEdits || old == canonical(before)) { "源文件正文与书架记录不一致，修改未保存，请重新导入" }
                SourceHtmlEdit.replace(html, requireNotNull(target), checkActive, image)
            }
            checkActive()
            progress.report("重新打包 MOBI", .65f)
            MobiSourceWriter.write(source, destination, html, result, parsed.sections)
            var verifiedHtml = ""
            val verified = MobiArchive.read(destination, checkActive = checkActive, captureHtml = { verifiedHtml = it },
                onProgress = { progress.report("校验 MOBI 文件", .75f + .15f * it) })
            check(verified.title == parsed.title && verified.author == parsed.author && verified.images.size == parsed.images.size &&
                verified.sections.map { it.title } == parsed.sections.map { it.title }) { "MOBI 写入校验失败，原文件未修改" }
            if (bySection) verified.sections.forEachIndexed { index, section ->
                check(SourceHtmlEdit.plain(section.html, checkActive, image) == targets[index]) { "MOBI 正文校验失败，原文件未修改" }
                progress.report("校验 MOBI 第 ${index + 1} 章", .9f + .07f * (index + 1) / verified.sections.size.coerceAtLeast(1))
            } else check(SourceHtmlEdit.plain(verifiedHtml, checkActive, image) == target) { "MOBI 正文校验失败，原文件未修改" }
            parsed.images.zip(verified.images).forEachIndexed { index, (oldImage, newImage) ->
                checkActive()
                check(resourceHash(source, oldImage) == resourceHash(destination, newImage)) { "MOBI 插图校验失败，原文件未修改" }
                progress.report("校验 MOBI 插图", .97f + .03f * (index + 1) / parsed.images.size.coerceAtLeast(1))
            }
        }

        private fun decodeMarkup(bytes: ByteArray): String {
            val prefix = bytes.take(500).toByteArray().toString(Charsets.ISO_8859_1)
            val encoding = Regex("(?:encoding\\s*=\\s*|charset\\s*=\\s*)['\"]?([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
                .find(prefix)?.groupValues?.get(1)
            return if (encoding == null) TextParser.decode(bytes) else bytes.toString(java.nio.charset.Charset.forName(encoding))
        }

        private fun resourceHash(file: File, image: MobiImageResource): String {
            val digest = MessageDigest.getInstance("SHA-256")
            java.io.RandomAccessFile(file, "r").use { input ->
                input.seek(image.offset)
                val bytes = ByteArray(32 * 1024)
                var remaining = image.length
                while (remaining > 0) {
                    val size = minOf(bytes.size.toLong(), remaining).toInt()
                    input.readFully(bytes, 0, size); digest.update(bytes, 0, size); remaining -= size
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        private fun fileHash(file: File): String = file.inputStream().use { hash(it) }
        private fun documentHash(context: Context, source: String): String = openDocumentStream(context, source).use { hash(it) }
        private fun hash(input: java.io.InputStream, onRead: (Long) -> Unit = {}): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var copied = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count); copied += count; onRead(copied)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        private fun copy(input: java.io.InputStream, output: java.io.OutputStream,
                         checkActive: () -> Unit = {}, onCopied: (Long) -> Unit = {}) {
            val buffer = ByteArray(64 * 1024)
            var copied = 0L
            while (true) {
                checkActive()
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count); copied += count; onCopied(copied)
            }
        }
        private class Progress(private val callback: (String, Float) -> Unit) {
            private var fraction = -1f
            private var stage = ""
            fun report(nextStage: String, nextFraction: Float) {
                val value = nextFraction.coerceIn(0f, 1f).coerceAtLeast(fraction)
                if (nextStage != stage || value - fraction >= .005f || value == 1f) {
                    stage = nextStage; fraction = value; callback(nextStage, value)
                }
            }
        }
    }
}
