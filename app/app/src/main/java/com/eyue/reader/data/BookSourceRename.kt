package com.eyue.reader.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Keep the shelf unchanged until the file and all private source references have been renamed. */
internal class BookSourceRename(private val context: Context, private val original: Book) {
    private var previous: String? = null
    private var renamed: String? = null
    private var previousName: String? = null
    private val references = linkedMapOf<File, ByteArray>()

    fun apply(title: String): Book {
        require(title.isNotBlank() && title !in setOf(".", "..") && title.none { it in "/\\\u0000\r\n" }) {
            "书籍名称不能包含斜杠或换行"
        }
        if (original.cloudOnly || original.isDemo) return original.copy(title = title)
        val source = EbookSourceWriteback.source(original)
        val info = documentSourceFileInfo(context, source) ?: throw IOException("无法读取原文件，请重新选择")
        require(!info.directory) { "漫画文件夹暂不支持重命名源文件夹" }
        val oldName = info.name ?: original.sourceFileName ?: throw IOException("无法读取原文件名")
        val extension = oldName.substringAfterLast('.', "")
        val name = if (extension.isEmpty() || title.endsWith(".$extension", ignoreCase = true)) title else "$title.$extension"
        if (oldName == name) return original.copy(title = title, sourceFileName = name)
        previous = source
        previousName = oldName
        val target = move(source, name)
        renamed = target
        try {
            // Deferred chapter/resource descriptors can still refer to an internally downloaded source.
            val privateRoot = File(context.filesDir, "books/${original.id}").canonicalFile
            if (documentFile(source)?.canonicalFile?.parentFile == privateRoot) {
                privateRoot.walkTopDown().filter { it.isFile && it.extension == "json" }.forEach { file ->
                    val bytes = AtomicFile(file).readFully()
                    val row = JSONObject(bytes.toString(Charsets.UTF_8))
                    if (row.optString("source") == source) {
                        references[file] = bytes
                        row.put("source", target)
                        write(file, row.toString().toByteArray(Charsets.UTF_8))
                    }
                }
            }
            fun path(value: String?): String? = when {
                value == source -> target
                value != null && documentFile(value)?.absolutePath == documentFile(source)?.absolutePath && documentFile(source) != null ->
                    documentFile(target)?.absolutePath ?: target
                else -> value
            }
            return original.copy(title = title, sourceFileName = name, sourceUri = path(original.sourceUri),
                sourcePath = path(original.sourcePath), coverPath = path(original.coverPath), positionImagePath = path(original.positionImagePath),
                chapters = original.chapters.map { chapter -> chapter.copy(textPath = path(chapter.textPath),
                    pages = chapter.pages.map { it.copy(path = requireNotNull(path(it.path)), textPath = path(it.textPath)) },
                    inlineImages = chapter.inlineImages.map { it.copy(path = requireNotNull(path(it.path))) },
                    sourceFootnotes = chapter.sourceFootnotes.map { it.copy(imagePath = path(it.imagePath)) }) })
        } catch (error: Exception) { rollback(); throw error }
    }

    fun rollback() {
        val before = previous ?: return
        val after = renamed ?: return
        val name = previousName
            ?: throw IOException("无法恢复原文件名：$after")
        val restored = move(after, name)
        check(restored == before || documentFile(restored) == documentFile(before)) { "原文件重命名恢复失败：$restored" }
        references.forEach { (file, bytes) -> write(file, bytes) }
        renamed = null
    }

    private fun move(source: String, name: String): String {
        documentFile(source)?.let { file ->
            val target = File(file.absoluteFile.parentFile, name)
            require(!target.exists()) { "目标位置已有同名文件，未重命名" }
            Files.move(file.toPath(), target.toPath())
            return if (Uri.parse(source).scheme == "file") Uri.fromFile(target).toString() else target.absolutePath
        }
        val uri = Uri.parse(source)
        require(DocumentsContract.isDocumentUri(context, uri)) { "当前文件来源不支持重命名，请从系统文件选择器重新导入" }
        return DocumentsContract.renameDocument(context.contentResolver, uri, name)?.toString()
            ?: throw IOException("源文件重命名失败，请检查写入权限")
    }

    private fun write(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) }
        catch (error: Exception) { atomic.failWrite(output); throw error }
    }
}
