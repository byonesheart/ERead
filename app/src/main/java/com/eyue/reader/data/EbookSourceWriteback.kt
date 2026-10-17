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

    fun commit() {
        require(documentHash(context, source) == fileHash(backup)) { "源文件在编辑期间发生变化，修改未保存，请重新导入" }
        try {
            replace(prepared)
            check(documentHash(context, source) == fileHash(prepared)) { "源文件写入校验失败" }
            committed = true
        } catch (error: Exception) {
            restoreBackup()
            throw error
        }
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

    private fun replace(file: File) {
        documentFile(source)?.let { target ->
            val staging = File.createTempFile(".eyue-edit-", ".tmp", target.absoluteFile.parentFile)
            try {
                file.inputStream().use { input -> staging.outputStream().use { input.copyTo(it) } }
                try { Files.move(staging.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
                catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(staging.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally { staging.delete() }
            return
        }
        context.contentResolver.openOutputStream(Uri.parse(source), "rwt")?.use { output ->
            file.inputStream().use { it.copyTo(output) }
        } ?: throw IOException("无法打开源文件进行写入")
    }

    companion object {
        fun source(book: Book): String = book.sourceUri ?: book.sourcePath ?: throw IOException("原文件不存在，请重新选择")

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

        fun prepare(context: Context, before: Book, edited: Book, directory: File): EbookSourceWriteback {
            val source = source(before)
            requireWritable(context, source)
            val backup = File(directory, "original.${before.format.name.lowercase()}")
            openDocumentStream(context, source).use { input -> backup.outputStream().use { input.copyTo(it) } }
            val prepared = File(directory, "prepared.${before.format.name.lowercase()}")
            when (before.format) {
                BookFormat.EPUB -> epub(backup, prepared, before, edited)
                BookFormat.MOBI -> mobi(backup, prepared, before, edited)
                else -> error("不支持覆盖此格式")
            }
            return EbookSourceWriteback(context, source, backup, prepared)
        }

        private fun epub(source: File, destination: File, before: Book, edited: Book) {
            val parsed = EpubArchive.read(source)
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
                    val old = SourceHtmlEdit.plain(section.html, image(section.path))
                    val cached = File(requireNotNull(before.chapters[index].textPath)).readText()
                    require(before.hasTextEdits || old == cached) { "源文件正文已变化，修改未保存，请重新导入" }
                    val result = SourceHtmlEdit.replace(section.html, expected[index], image(section.path))
                    if (result.changes.isNotEmpty()) {
                        val raw = zip.getInputStream(zip.getEntry(section.path)).use { decodeMarkup(it.readBytes()) }
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
                    zip.entries().asSequence().forEach { entry ->
                        val path = EpubArchive.safePath(entry.name)
                        val edits = changes[path]
                        val bytes = if (edits == null) null else zip.getInputStream(entry).use {
                            val html = decodeMarkup(it.readBytes())
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
            val verified = EpubArchive.read(destination)
            check(verified.title == parsed.title && verified.author == parsed.author && verified.sections.size == parsed.sections.size &&
                verified.imagePaths == parsed.imagePaths) { "EPUB 写入校验失败，原文件未修改" }
            verified.sections.forEachIndexed { index, section ->
                check(SourceHtmlEdit.plain(section.html, image(section.path)) == expected[index]) { "EPUB 正文校验失败，原文件未修改" }
            }
        }

        private fun mobi(source: File, destination: File, before: Book, edited: Book) {
            var html = ""
            val parsed = MobiArchive.read(source, captureHtml = { html = it })
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
            val old = SourceHtmlEdit.plain(html, image)
            require(before.hasTextEdits || old == canonical(before)) { "源文件正文与书架记录不一致，修改未保存，请重新导入" }
            val target = canonical(edited)
            val result = SourceHtmlEdit.replace(html, target, image)
            MobiSourceWriter.write(source, destination, html, result, parsed.sections)
            var verifiedHtml = ""
            val verified = MobiArchive.read(destination, captureHtml = { verifiedHtml = it })
            check(verified.title == parsed.title && verified.author == parsed.author && verified.images.size == parsed.images.size &&
                verified.sections.map { it.title } == parsed.sections.map { it.title } &&
                SourceHtmlEdit.plain(verifiedHtml, image) == target) { "MOBI 写入校验失败，原文件未修改" }
            parsed.images.zip(verified.images).forEach { (oldImage, newImage) ->
                check(resourceHash(source, oldImage) == resourceHash(destination, newImage)) { "MOBI 插图校验失败，原文件未修改" }
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
        private fun fileHash(file: File): String = file.inputStream().use(::hash)
        private fun documentHash(context: Context, source: String): String = openDocumentStream(context, source).use(::hash)
        private fun hash(input: java.io.InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
