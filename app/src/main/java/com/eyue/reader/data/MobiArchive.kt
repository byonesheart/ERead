package com.eyue.reader.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

data class MobiSection(val title: String, val html: String, val sourceStart: Int = 0, val sourceEnd: Int = html.length,
                       val tocParentTitle: String? = null, val sourceNoteTargets: Map<String, String> = emptyMap())
data class MobiCover(val bytes: ByteArray, val extension: String)
data class MobiImageResource(val recIndex: Int, val offset: Long, val length: Long, val extension: String)
data class ParsedMobi(val title: String, val author: String, val sections: List<MobiSection>, val cover: MobiCover?,
                      val images: List<MobiImageResource> = emptyList(), val css: String = "", val hasEmbeddedToc: Boolean = false)

/**
 * Independent implementation of the Palm Database/MOBI binary format. Format references:
 * https://github.com/kevinhendricks/KindleUnpack/blob/master/lib/mobi_header.py
 * https://github.com/bfabiszewski/libmobi/blob/master/src/compression.c
 * No source code from those projects is included.
 */
object MobiArchive {
    private const val MAX_TEXT = 64 * 1024 * 1024
    private const val MAX_RECORD = 64 * 1024 * 1024
    private val htmlAttributes = Regex("\\b([\\w:-]+)\\s*=\\s*(?:[\"']([^\"']*)[\"']|([^\\s>]+))", RegexOption.IGNORE_CASE)
    private val imagesInHtml = Regex("<(?:img|image)\\b", RegexOption.IGNORE_CASE)
    private val labelSpace = Regex("\\s+")
    private val numericEntities = Regex("&#(?:x([0-9a-f]+)|([0-9]+));", RegexOption.IGNORE_CASE)
    private data class TocLink(val label: String, val href: String?, val filepos: Int?, val end: Int)

    fun read(file: File, fallbackTitle: String = file.nameWithoutExtension, checkActive: () -> Unit = {},
             captureHtml: ((String) -> Unit)? = null,
             onProgress: (Float) -> Unit = {}): ParsedMobi = RandomAccessFile(file, "r").use { input ->
        var reported = -1f
        var reportedAt = 0L
        fun progress(value: Float, force: Boolean = false) {
            checkActive()
            val fraction = value.coerceIn(0f, 1f).coerceAtLeast(reported)
            val now = System.nanoTime()
            if (force || fraction - reported >= 0.01f || now - reportedAt >= 100_000_000L) {
                onProgress(fraction)
                reported = fraction
                reportedAt = now
            }
        }
        progress(0f, force = true)
        require(input.length() >= 78) { "MOBI 文件头不完整" }
        val database = ByteArray(78).also { input.readFully(it) }
        require(database.copyOfRange(60, 68).toString(Charsets.US_ASCII) == "BOOKMOBI") { "文件不是 MOBI 电子书" }
        val count = u16(database, 76)
        require(count >= 2 && 78L + count * 8L <= input.length()) { "MOBI 记录表不完整" }
        val offsets = LongArray(count + 1)
        repeat(count) { index ->
            input.seek(78L + index * 8L)
            offsets[index] = input.readInt().toLong() and 0xffffffffL
            require(offsets[index] >= 78L + count * 8L && offsets[index] <= input.length() &&
                (index == 0 || offsets[index] >= offsets[index - 1])) { "MOBI 记录位置无效" }
            progress(0.03f * (index + 1) / count)
        }
        offsets[count] = input.length()
        fun record(index: Int): ByteArray {
            require(index in 0 until count) { "MOBI 引用了不存在的记录" }
            val size = offsets[index + 1] - offsets[index]
            require(size <= MAX_RECORD) { "MOBI 单条记录过大" }
            input.seek(offsets[index])
            return ByteArray(size.toInt()).also { input.readFully(it) }
        }
        val header = record(0)
        require(header.size >= 40 && header.copyOfRange(16, 20).toString(Charsets.US_ASCII) == "MOBI") { "MOBI 内容头缺失" }
        val headerLength = integer(header, 20)
        require(headerLength >= 116 && headerLength.toLong() + 16 <= header.size) { "MOBI 内容头长度无效" }
        val headerEnd = headerLength + 16
        require(u16(header, 12) == 0 && (headerEnd < 176 || u32(header, 172) == 0L)) { "此 MOBI 受 DRM 加密保护，无法导入" }
        val version = integer(header, 36)
        require(version < 8) { "此文件只有 KF8/AZW3 正文，请先转换为 EPUB 或兼容 MOBI" }
        val encoding = when (u32(header, 28)) {
            65001L -> Charsets.UTF_8
            1252L -> Charset.forName("windows-1252")
            else -> throw IllegalArgumentException("不支持此 MOBI 的文字编码：${u32(header, 28)}")
        }
        fun text(bytes: ByteArray) = decode(bytes, encoding).trim('\u0000', ' ', '\n', '\r', '\t')
        val textLength = integer(header, 4)
        val textRecords = u16(header, 8)
        require(textLength in 1..MAX_TEXT && textRecords in 1 until count) { "MOBI 正文长度或记录数无效（正文上限 64 MB）" }
        val metadata = mutableMapOf<Int, MutableList<ByteArray>>()
        if (headerEnd >= 132 && u32(header, 128) and 0x40L != 0L) {
            require(header.size >= headerEnd + 12 && ascii(header, headerEnd, 4) == "EXTH") { "MOBI 扩展元数据头缺失" }
            val size = integer(header, headerEnd + 4)
            val entries = integer(header, headerEnd + 8)
            require(size >= 12 && headerEnd.toLong() + size <= header.size && entries <= (size - 12) / 8) { "MOBI 扩展元数据长度无效" }
            var position = headerEnd + 12
            repeat(entries) {
                val id = integer(header, position)
                val length = integer(header, position + 4)
                require(length >= 8 && position.toLong() + length <= headerEnd + size) { "MOBI 元数据记录越界" }
                metadata.getOrPut(id) { mutableListOf() } += header.copyOfRange(position + 8, position + length)
                position += length
            }
        }
        val titleLength = integer(header, 88)
        val titleOffset = integer(header, 84)
        val headerTitle = if (titleLength > 0) {
            require(titleOffset >= 0 && titleOffset.toLong() + titleLength <= header.size) { "MOBI 书名位置无效" }
            text(header.copyOfRange(titleOffset, titleOffset + titleLength))
        } else ""
        val title = metadata[503]?.firstOrNull()?.let(::text)?.ifBlank { null } ?: headerTitle.ifBlank { fallbackTitle }
        val author = metadata[100].orEmpty().map(::text).filter { it.isNotBlank() }.distinct().joinToString("、")
        val compression = u16(header, 0)
        val huffman = if (compression == 0x4448) {
            val start = integer(header, 112)
            val number = integer(header, 116)
            require(number >= 2 && start > textRecords && start.toLong() + number <= count) { "MOBI HUFF/CDIC 字典位置无效" }
            require(offsets[start + number] - offsets[start] <= MAX_TEXT) { "MOBI HUFF/CDIC 字典过大" }
            val huff = record(start)
            val dictionaries = (1 until number).map { index ->
                record(start + index).also { progress(0.04f + 0.04f * (index + 1) / number) }
            }
            HuffDictionary(huff, dictionaries)
        } else null
        require(compression in setOf(1, 2, 0x4448)) { "不支持此 MOBI 压缩方式：$compression" }
        val extraFlags = if (headerLength >= 228 && headerEnd >= 244 && u32(header, 104) >= 5) u16(header, 242) else 0
        progress(0.08f, force = true)
        val content = ByteArray(textLength)
        var expandedLength = 0L
        for (index in 1..textRecords) {
            val compressed = trimRecord(record(index), extraFlags)
            val expanded = when (compression) {
                1 -> compressed
                2 -> decompressPalmDoc(compressed)
                else -> huffman!!.expand(compressed)
            }
            require(expandedLength + expanded.size <= MAX_TEXT) { "MOBI 解压正文超过 64 MB" }
            if (expandedLength < textLength) {
                val destination = expandedLength.toInt()
                expanded.copyInto(content, destination, 0, minOf(expanded.size, textLength - destination))
            }
            expandedLength += expanded.size
            progress(0.08f + 0.72f * index / textRecords)
        }
        require(expandedLength >= textLength) { "MOBI 正文记录缺失或截断" }
        require(!ascii(content, 0, minOf(4, content.size)).startsWith("%MOP")) { "不支持 Kindle Print Replica 格式" }
        val html = decode(content, encoding)
        captureHtml?.invoke(html)
        require(html.isNotBlank()) { "MOBI 没有可阅读正文" }
        progress(0.84f, force = true)
        val coverOffset = metadata[201]?.firstOrNull()?.let { bytes ->
            require(bytes.size == 4) { "MOBI 封面元数据无效" }; u32(bytes, 0)
        }
        val cover = if (coverOffset != null && coverOffset != 0xffffffffL) {
            val firstImage = u32(header, 108).takeIf { it != 0xffffffffL } ?: (textRecords + 1L)
            val index = firstImage + coverOffset
            require(index in 0 until count.toLong()) { "MOBI 封面记录越界" }
            val bytes = record(index.toInt())
            imageExtension(bytes)?.let { MobiCover(bytes, it) }
        } else null
        val firstImage = u32(header, 108).takeIf { it != 0xffffffffL } ?: (textRecords + 1L)
        val images = mutableListOf<MobiImageResource>()
        if (firstImage in 0 until count.toLong()) {
            for (index in firstImage.toInt() until count) {
                val size = offsets[index + 1] - offsets[index]
                input.seek(offsets[index])
                val signature = ByteArray(minOf(size, 12).toInt()).also { input.readFully(it) }
                imageExtension(signature)?.let { extension ->
                    images += MobiImageResource(index - firstImage.toInt() + 1, offsets[index], size, extension)
                }
                progress(0.84f + 0.12f * (index - firstImage.toInt() + 1) / (count - firstImage.toInt()))
            }
        }
        progress(0.96f, force = true)
        val cleanHtml = removeHtmlComments(html)
        val css = sourceHtmlElements(cleanHtml, setOf("style"), checkActive).joinToString("\n") {
            cleanHtml.substring(it.tag.end, it.contentEnd)
        }
        progress(0.965f, force = true)
        val tocIndex = if (headerEnd >= 248) u32(header, 244).takeIf { it in 1 until count.toLong() }?.toInt() else null
        val toc = tocIndex?.let { MobiToc.read(it, count, textLength, encoding, { index ->
            require(offsets[index + 1] - offsets[index] <= 8 * 1024 * 1024) { "MOBI 目录记录过大" }
            record(index)
        }, checkActive) }.orEmpty()
        val (sections, hasEmbeddedToc) = splitHtml(html, content, encoding, toc, checkActive) { progress(0.965f + 0.034f * it, force = true) }
        val noteReferences = sourceNoteReferences(html, checkActive)
        val notePositions = try { fileposLocations(content, encoding, noteReferences.mapNotNull { it.filepos?.takeIf { at -> at in content.indices } }, checkActive) }
            catch (_: IllegalArgumentException) { emptyMap() }
        val noteTargets = sourceNoteTargets(html, noteReferences.filter { it.href?.startsWith('#') == true }
            .mapNotNull { ref -> ref.href?.let(::sourceNoteFragment)?.let { ref.target to it } }.toMap(),
            noteReferences.mapNotNull { ref -> ref.filepos?.let(notePositions::get)?.let { ref.target to it } }.toMap(), checkActive)
        val notedSections = if (noteTargets.isEmpty()) sections else sections.map { section ->
            val keys = sourceNoteReferences(section.html, checkActive).map { it.target }.toSet()
            section.copy(sourceNoteTargets = noteTargets.filterKeys { it in keys })
        }
        progress(1f, force = true)
        ParsedMobi(title, author, notedSections, cover, images, css, hasEmbeddedToc)
    }

    fun copyImage(archive: File, resource: MobiImageResource, destination: File) {
        require(resource.offset >= 0 && resource.length >= 0 && resource.offset + resource.length <= archive.length()) { "MOBI 图片记录越界" }
        destination.parentFile?.mkdirs()
        RandomAccessFile(archive, "r").use { input ->
            input.seek(resource.offset)
            destination.outputStream().use { output ->
                val buffer = ByteArray(32 * 1024)
                var remaining = resource.length
                while (remaining > 0) {
                    val count = minOf(remaining, buffer.size.toLong()).toInt()
                    input.readFully(buffer, 0, count)
                    output.write(buffer, 0, count)
                    remaining -= count
                }
            }
        }
    }

    /** PalmDOC literal runs, short space pairs, and overlapping LZ77 back references. */
    fun decompressPalmDoc(input: ByteArray): ByteArray {
        val output = GrowBuffer()
        var position = 0
        while (position < input.size) {
            val token = input[position++].toInt() and 255
            when {
                token in 1..8 -> {
                    require(position + token <= input.size) { "MOBI PalmDOC 字面记录截断" }
                    repeat(token) { output.add(input[position++]) }
                }
                token < 128 -> output.add(token.toByte())
                token >= 192 -> { output.add(32); output.add((token xor 128).toByte()) }
                else -> {
                    require(position < input.size) { "MOBI PalmDOC 引用截断" }
                    val pair = (token shl 8) or (input[position++].toInt() and 255)
                    val distance = (pair ushr 3) and 0x7ff
                    val length = (pair and 7) + 3
                    require(distance in 1..output.size) { "MOBI PalmDOC 回溯引用无效" }
                    repeat(length) { output.add(output[output.size - distance]) }
                }
            }
        }
        return output.toByteArray()
    }

    private fun trimRecord(record: ByteArray, flags: Int): ByteArray {
        var end = record.size
        var remainingFlags = flags ushr 1
        while (remainingFlags != 0) {
            if (remainingFlags and 1 != 0) {
                var length = 0L
                var shift = 0
                var index = end - 1
                while (true) {
                    require(index >= 0 && shift < 28) { "MOBI 尾部记录长度截断" }
                    val value = record[index--].toInt() and 255
                    length = length or ((value and 127).toLong() shl shift)
                    shift += 7
                    if (value and 128 != 0) break
                }
                require(length in 1..end.toLong()) { "MOBI 尾部记录长度无效" }
                end -= length.toInt()
            }
            remainingFlags = remainingFlags ushr 1
        }
        if (flags and 1 != 0) {
            require(end > 0) { "MOBI 多字节尾部记录截断" }
            val length = (record[end - 1].toInt() and 3) + 1
            require(length <= end) { "MOBI 多字节尾部记录长度无效" }
            end -= length
        }
        return if (end == record.size) record else record.copyOf(end)
    }

    private fun splitHtml(html: String, raw: ByteArray, encoding: Charset, toc: List<MobiTocEntry>, checkActive: () -> Unit,
                          onProgress: (Float) -> Unit): Pair<List<MobiSection>, Boolean> {
        val bodyTag = sourceBodyTag(html, checkActive)
        val scanned = hideHtmlContent(maskHtmlComments(html), preserveOffsets = true, checkActive = checkActive)
        onProgress(.15f)
        val notes = SourceHtmlNotes(scanned, checkActive)
        onProgress(.3f)
        val boundaries = mutableMapOf<Int, String>()
        if (toc.isNotEmpty()) {
            val parents = mutableMapOf<Int, String>()
            val offsets = try { fileposLocations(raw, encoding, toc.map { it.byteOffset }, checkActive) }
                catch (_: IllegalArgumentException) { emptyMap() }
            toc.forEach { entry ->
                offsets[entry.byteOffset]?.takeIf { !notes.contains(it) && !isNoteReference("", entry.title) }
                    ?.let { offset ->
                        if (boundaries.putIfAbsent(offset, entry.title) == null) entry.tocParentTitle?.let { parents[offset] = it }
                    }
            }
            if (boundaries.isNotEmpty()) {
                val output = sectionsAtBoundaries(html, bodyTag, boundaries, checkActive)
                    .map { it.copy(tocParentTitle = parents[it.sourceStart]) }
                onProgress(1f)
                return output to true
            }
        }
        val links = sourceHtmlElements(scanned, setOf("a"), checkActive).mapNotNull { link ->
            val label = labelText(scanned.substring(link.tag.end, link.contentEnd), checkActive)
            if (label.isBlank() || notes.contains(link.tag.start) || isNoteReference(link.tag.attributes, label)) null else TocLink(label, attribute(link.tag.attributes, "href"),
                attribute(link.tag.attributes, "filepos")?.toIntOrNull(), link.end - 1)
        }.toList()
        onProgress(.45f)
        val wantedIds = links.mapNotNull { it.href?.takeIf { href -> href.startsWith('#') }?.drop(1)?.lowercase() }.toSet()
        val locations = mutableMapOf<String, Int>()
        if (wantedIds.isNotEmpty()) sourceHtmlTags(scanned, checkActive).forEach { tag ->
            htmlAttributes.findAll(tag.attributes).forEach { attr ->
                if (attr.groupValues[1].equals("id", true) || attr.groupValues[1].equals("name", true)) {
                    val id = attr.groupValues[2].ifEmpty { attr.groupValues[3] }.lowercase()
                    if (id in wantedIds) locations.putIfAbsent(id, tag.start)
                }
            }
        }
        val offsets = fileposLocations(raw, encoding, links.filter { it.href?.startsWith('#') != true }
            .mapNotNull { it.filepos?.takeIf { offset -> offset in 0 until raw.size } }, checkActive)
        onProgress(.6f)
        val resolved = links.mapNotNull { link ->
            val location = if (link.href?.startsWith('#') == true) locations[link.href.drop(1).lowercase()] else link.filepos?.let(offsets::get)
            if (location != null && location > link.end && !notes.contains(location)) link to location else null
        }
        val firstTarget = resolved.minOfOrNull { it.second } ?: html.length
        resolved.filter { it.first.end < firstTarget }.forEach { (link, location) -> boundaries.putIfAbsent(location, link.label) }
        if (boundaries.size < 2) {
            boundaries.clear()
            sourceHtmlElements(scanned, setOf("h1", "h2"), checkActive).filterNot { notes.contains(it.tag.start) }.forEach {
                val title = labelText(scanned.substring(it.tag.end, it.contentEnd), checkActive)
                if (title.isNotBlank() && !isNoteReference("", title)) boundaries[it.tag.start] = title
            }
        }
        onProgress(.8f)
        if (boundaries.size < 2) {
            val breaks = sourceHtmlTags(scanned, checkActive).filter { it.name in setOf("pagebreak", "mbp:pagebreak") && !it.closing && !notes.contains(it.start) }.toList()
            if (breaks.isNotEmpty()) {
                val pieces = mutableListOf<MobiSection>()
                var start = 0
                breaks.forEach { boundary ->
                    checkActive()
                    val part = html.substring(start, boundary.start)
                    if (hasReadingContent(part, checkActive)) pieces += MobiSection(headingTitle(part, checkActive) ?: "第 ${pieces.size + 1} 章", sourceBodyFragment(part, bodyTag), start, boundary.start)
                    start = boundary.end
                }
                val tail = html.substring(start)
                if (hasReadingContent(tail, checkActive)) pieces += MobiSection(headingTitle(tail, checkActive) ?: "第 ${pieces.size + 1} 章", sourceBodyFragment(tail, bodyTag), start, html.length)
                if (pieces.isNotEmpty()) { onProgress(1f); return pieces to false }
            }
        }
        if (boundaries.isEmpty()) {
            val output = listOf(MobiSection(headingTitle(html, checkActive) ?: "正文", html))
            onProgress(1f)
            return output to false
        }
        val output = sectionsAtBoundaries(html, bodyTag, boundaries, checkActive)
        onProgress(1f)
        return output to false
    }

    private fun sectionsAtBoundaries(html: String, bodyTag: String?, boundaries: Map<Int, String>, checkActive: () -> Unit): List<MobiSection> {
        val sorted = boundaries.entries.sortedBy { it.key }
        val output = mutableListOf<MobiSection>()
        val prefix = html.substring(0, sorted.first().key)
        if (hasReadingContent(prefix, checkActive)) output += MobiSection("前言", prefix, 0, sorted.first().key)
        sorted.forEachIndexed { index, entry ->
            checkActive()
            val end = sorted.getOrNull(index + 1)?.key ?: html.length
            output += MobiSection(entry.value, sourceBodyFragment(html.substring(entry.key, end), bodyTag), entry.key, end)
        }
        return output
    }

    /** The full text has already been strictly decoded; only UTF-8 code-point boundaries are valid filepos targets. */
    private fun fileposLocations(raw: ByteArray, encoding: Charset, positions: List<Int>, checkActive: () -> Unit): Map<Int, Int> {
        if (encoding != Charsets.UTF_8) return positions.associateWith { it }
        val result = mutableMapOf<Int, Int>()
        var byte = 0
        var character = 0
        var checked = -4096
        positions.distinct().sorted().forEach { target ->
            checkActive()
            while (byte < target) {
                if (byte - checked >= 4096) { checkActive(); checked = byte }
                val first = raw[byte].toInt() and 255
                val size = when { first < 128 -> 1; first < 224 -> 2; first < 240 -> 3; else -> 4 }
                require(byte + size <= target) { "MOBI 目录位置落在多字节文字中间" }
                byte += size
                character += if (size == 4) 2 else 1
            }
            result[target] = character
        }
        return result
    }

    private fun headingTitle(html: String, checkActive: () -> Unit): String? {
        val scanned = maskHtmlComments(html)
        val notes = SourceHtmlNotes(scanned, checkActive)
        return sourceHtmlElements(scanned, setOf("h1", "h2", "h3", "h4", "h5", "h6"), checkActive).filterNot { notes.contains(it.tag.start) }
            .map { labelText(scanned.substring(it.tag.end, it.contentEnd), checkActive) }.firstOrNull { it.isNotBlank() && !isNoteReference("", it) }
    }
    private fun hasReadingContent(html: String, checkActive: () -> Unit): Boolean = labelText(html, checkActive).isNotBlank() ||
        imagesInHtml.containsMatchIn(html)
    private fun attribute(attributes: String, name: String): String? = htmlAttributes.findAll(attributes)
        .firstOrNull { it.groupValues[1].equals(name, true) }?.let { it.groupValues[2].ifEmpty { it.groupValues[3] } }
    private fun labelText(html: String, checkActive: () -> Unit = {}): String = if ('<' !in html && '&' !in html) html.replace(labelSpace, " ").trim() else removeHtmlComments(html)
        .let { sourceHtmlPlainText(hideHtmlContent(it, preserveOffsets = false, checkActive = checkActive), checkActive) }
        .replace("&nbsp;", " ").replace("&amp;", "&")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace(numericEntities) { match ->
            val point = if (match.groupValues[1].isNotEmpty()) match.groupValues[1].toIntOrNull(16) else match.groupValues[2].toIntOrNull()
            if (point != null && Character.isValidCodePoint(point)) String(Character.toChars(point)) else match.value
        }
        .replace(labelSpace, " ").trim()

    private fun imageExtension(bytes: ByteArray): String? = when {
        bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "jpg"
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)) -> "png"
        bytes.size >= 6 && ascii(bytes, 0, 3) == "GIF" -> "gif"
        bytes.size >= 2 && ascii(bytes, 0, 2) == "BM" -> "bmp"
        bytes.size >= 12 && ascii(bytes, 0, 4) == "RIFF" && ascii(bytes, 8, 4) == "WEBP" -> "webp"
        else -> null
    }
    private fun decode(bytes: ByteArray, charset: Charset): String = try {
        charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (error: java.nio.charset.CharacterCodingException) {
        throw IllegalArgumentException("MOBI 文字编码损坏或正文截断", error)
    }
    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String {
        require(offset >= 0 && offset.toLong() + length <= bytes.size) { "MOBI 记录截断" }
        return String(bytes, offset, length, Charsets.US_ASCII)
    }
    private fun u16(bytes: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset.toLong() + 2 <= bytes.size) { "MOBI 记录截断" }
        return ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)
    }
    private fun u32(bytes: ByteArray, offset: Int): Long {
        require(offset >= 0 && offset.toLong() + 4 <= bytes.size) { "MOBI 记录截断" }
        return (0..3).fold(0L) { result, index -> (result shl 8) or (bytes[offset + index].toLong() and 255) }
    }
    private fun integer(bytes: ByteArray, offset: Int): Int {
        val value = u32(bytes, offset)
        require(value <= Int.MAX_VALUE) { "MOBI 字段超出有效范围" }
        return value.toInt()
    }

    private class GrowBuffer {
        private var data = ByteArray(4096)
        var size = 0
            private set
        fun add(value: Byte) {
            require(size < MAX_TEXT) { "MOBI 解压正文超过 64 MB" }
            if (size == data.size) data = data.copyOf(minOf(MAX_TEXT, data.size * 2))
            data[size++] = value
        }
        fun add(value: Int) = add(value.toByte())
        operator fun get(index: Int): Byte = data[index]
        fun toByteArray(): ByteArray = data.copyOf(size)
    }

    private class HuffDictionary(huff: ByteArray, cdicRecords: List<ByteArray>) {
        private data class Prefix(val length: Int, val terminal: Boolean, val maximum: Long)
        private data class Phrase(val bytes: ByteArray, val literal: Boolean)
        private val prefixes: List<Prefix>
        private val minimum = LongArray(33)
        private val maximum = LongArray(33)
        private val phrases = mutableListOf<Phrase>()
        private val cache = mutableMapOf<Int, ByteArray>()
        private val expanding = mutableSetOf<Int>()
        private var cachedBytes = 0L

        init {
            require(huff.size >= 24 && ascii(huff, 0, 4) == "HUFF" && u32(huff, 4) == 24L) { "MOBI HUFF 头无效" }
            val prefixOffset = integer(huff, 8)
            val rangeOffset = integer(huff, 12)
            require(prefixOffset.toLong() + 1024 <= huff.size && rangeOffset.toLong() + 256 <= huff.size) { "MOBI HUFF 表截断" }
            prefixes = (0..255).map { index ->
                val value = u32(huff, prefixOffset + index * 4)
                val length = (value and 31).toInt()
                val terminal = value and 128 != 0L
                require(length in 1..32 && (length > 8 || terminal)) { "MOBI HUFF 前缀长度无效" }
                Prefix(length, terminal, (((value ushr 8) + 1) shl (32 - length)) - 1)
            }
            for (length in 1..32) {
                minimum[length] = u32(huff, rangeOffset + (length - 1) * 8) shl (32 - length)
                maximum[length] = ((u32(huff, rangeOffset + (length - 1) * 8 + 4) + 1) shl (32 - length)) - 1
            }
            var expected: Int? = null
            cdicRecords.forEach { cdic ->
                require(cdic.size >= 16 && ascii(cdic, 0, 4) == "CDIC" && u32(cdic, 4) == 16L) { "MOBI CDIC 头无效" }
                val total = integer(cdic, 8)
                val bits = integer(cdic, 12)
                require(bits in 0..16 && total > 0 && (expected == null || expected == total)) { "MOBI CDIC 字典数量无效" }
                expected = total
                val number = minOf(1 shl bits, total - phrases.size)
                require(number >= 0 && 16L + number * 2L <= cdic.size) { "MOBI CDIC 偏移表截断" }
                repeat(number) { index ->
                    val position = 16 + u16(cdic, 16 + index * 2)
                    require(position >= 16 + number * 2) { "MOBI CDIC 短语偏移无效" }
                    val lengthFlags = u16(cdic, position)
                    val length = lengthFlags and 0x7fff
                    require(position.toLong() + 2 + length <= cdic.size) { "MOBI CDIC 短语截断" }
                    phrases += Phrase(cdic.copyOfRange(position + 2, position + 2 + length), lengthFlags and 0x8000 != 0)
                }
            }
            require(phrases.size == expected) { "MOBI CDIC 字典缺失" }
        }

        fun expand(input: ByteArray, depth: Int = 0): ByteArray {
            require(depth <= 64) { "MOBI HUFF 字典递归过深" }
            val output = ByteArrayOutputStream()
            var position = 0L
            val bitCount = input.size * 8L
            while (position < bitCount) {
                var code = 0L
                repeat(32) { bit ->
                    val location = position + bit
                    val value = if (location < bitCount) (input[(location / 8).toInt()].toInt() ushr (7 - (location % 8).toInt())) and 1 else 0
                    code = (code shl 1) or value.toLong()
                }
                val prefix = prefixes[(code ushr 24).toInt()]
                var length = prefix.length
                var limit = prefix.maximum
                if (!prefix.terminal) {
                    while (length <= 32 && code < minimum[length]) length++
                    require(length <= 32) { "MOBI HUFF 码字无效" }
                    limit = maximum[length]
                }
                if (position + length > bitCount) break // Last byte may contain incomplete padding bits.
                require(limit >= code) { "MOBI HUFF 码字超出范围" }
                val phraseIndex = ((limit - code) ushr (32 - length)).toInt()
                require(phraseIndex in phrases.indices) { "MOBI HUFF 短语索引无效" }
                val phrase = phrases[phraseIndex]
                val bytes = if (phrase.literal) phrase.bytes else cache[phraseIndex] ?: run {
                    require(expanding.add(phraseIndex)) { "MOBI HUFF 字典存在循环引用" }
                    val expanded = try { expand(phrase.bytes, depth + 1) } finally { expanding.remove(phraseIndex) }
                    require(cachedBytes + expanded.size <= MAX_TEXT) { "MOBI HUFF 展开字典过大" }
                    cachedBytes += expanded.size
                    cache[phraseIndex] = expanded
                    expanded
                }
                require(output.size().toLong() + bytes.size <= MAX_TEXT) { "MOBI HUFF 解压正文超过 64 MB" }
                output.write(bytes)
                position += length
            }
            return output.toByteArray()
        }
    }
}
