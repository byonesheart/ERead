package com.eyue.reader.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/** Compatibility with publications imported before the legacy-engine return. No toolkit dependency. */
internal class LegacyEpubIndex private constructor(private val json: JSONObject) {
    companion object {
        fun file(context: Context, book: Book): File = File(context.filesDir, "books/${book.id}/readium-publication.json")

        fun find(context: Context, book: Book): File? {
            fun exists(file: File) = file.isFile || File(file.path + ".bak").isFile
            val fixed = file(context, book)
            if (exists(fixed)) return fixed
            val privateRoot = context.filesDir.canonicalFile.toPath()
            val visited = mutableSetOf(fixed.parentFile?.absolutePath)
            val paths = sequence {
                book.sourcePath?.let { yield(it) }
                book.chapters.forEach { it.textPath?.let { path -> yield(path) } }
            }
            for (path in paths) {
                val folder = File(path).absoluteFile.parentFile ?: continue
                if (!visited.add(folder.path)) continue
                val parent = folder.canonicalFile
                if (!parent.toPath().startsWith(privateRoot)) continue
                val candidate = File(parent, "readium-publication.json")
                if (exists(candidate)) return candidate
            }
            return null
        }

        fun read(context: Context, book: Book): LegacyEpubIndex? {
            val file = find(context, book) ?: return null
            val json = JSONObject(AtomicFile(file).readFully().toString(Charsets.UTF_8))
            require(json.getInt("version") == 1) { "旧 EPUB 资源索引版本不兼容，原书未修改" }
            val spine = json.getJSONArray("spine")
            require(spine.length() == book.chapters.size) { "旧 EPUB 资源索引与书库章节不一致，原书未修改" }
            for (index in book.chapters.indices) {
                val row = spine.getJSONObject(index)
                require(row.getInt("chapterIndex") == index && row.getString("chapterId") == book.chapters[index].id) {
                    "旧 EPUB 章节编号与书库不一致，原书未修改"
                }
            }
            return LegacyEpubIndex(json)
        }
    }

    /** Reads already-indexed archive members. Kept synchronous for the existing save transaction. */
    fun snapshot(archive: File, book: Book, checkActive: () -> Unit = {}): ParsedEpub = ZipFile(archive).use { zip ->
        val entries = zip.entries().asSequence().associateBy { EpubArchive.safePath(it.name) }
        fun document(path: String): String {
            checkActive()
            val entry = entries[path]?.takeUnless { it.isDirectory } ?: throw IOException("EPUB 已索引资源缺失：$path")
            return zip.getInputStream(entry).use { decodeEpubMarkup(it.readBytes()) }
        }
        val spine = json.getJSONArray("spine")
        val sections = (0 until spine.length()).map { index ->
            val path = archivePath(spine.getJSONObject(index).getString("href"))
            val html = document(path)
            val notes = mutableMapOf<String, String>()
            sourceNoteReferences(html, checkActive).groupBy { reference ->
                val href = reference.href ?: return@groupBy null
                runCatching {
                    val resolved = java.net.URI(null, null, path, null).resolve(java.net.URI(href))
                    require(!resolved.isAbsolute && resolved.rawAuthority == null)
                    EpubArchive.safePath(requireNotNull(resolved.path))
                }.getOrNull()
            }.forEach { (targetPath, references) ->
                if (targetPath != null && targetPath in entries) {
                    val targets = references.mapNotNull { reference ->
                        reference.href?.let(::sourceNoteFragment)?.let { reference.target to it }
                    }.toMap()
                    notes += sourceNoteTargets(if (targetPath == path) html else document(targetPath), targets, checkActive = checkActive)
                }
            }
            EpubSection(book.chapters[index].title, html, path, tocParentTitle = book.chapters[index].tocParentTitle,
                sourceNoteTargets = notes)
        }
        val manifest = json.getJSONObject("manifest")
        val resources = listOfNotNull(manifest.optJSONArray("resources"), manifest.optJSONArray("readingOrder"))
        val images = resources.flatMap { rows -> (0 until rows.length()).map { rows.getJSONObject(it) } }
            .filter { it.optString("type").startsWith("image/") }
            .mapNotNull { runCatching { archivePath(it.getString("href")) }.getOrNull() }.distinct().filter { it in entries }
        ParsedEpub(book.title, book.author, sections, null, images)
    }

    private fun archivePath(href: String): String {
        val url = java.net.URI(href).normalize()
        require(!url.isAbsolute && url.rawAuthority == null) { "EPUB 索引不是离线资源：$href" }
        return EpubArchive.safePath(requireNotNull(url.path) { "EPUB 索引路径无效：$href" })
    }
}

internal fun decodeEpubMarkup(bytes: ByteArray): String {
    if (bytes.size >= 2) {
        if (bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte()) return bytes.toString(Charsets.UTF_16LE).removePrefix("\uFEFF")
        if (bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte()) return bytes.toString(Charsets.UTF_16BE).removePrefix("\uFEFF")
    }
    val prefix = bytes.take(512).toByteArray().toString(Charsets.ISO_8859_1)
    val encoding = Regex("(?:encoding\\s*=\\s*|charset\\s*=\\s*)[\"']?([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
        .find(prefix)?.groupValues?.get(1)
    return bytes.toString(encoding?.let(java.nio.charset.Charset::forName) ?: Charsets.UTF_8).removePrefix("\uFEFF")
}
