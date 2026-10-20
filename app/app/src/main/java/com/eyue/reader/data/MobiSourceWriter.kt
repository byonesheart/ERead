package com.eyue.reader.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.Charset

/** Rewrites a DRM-free MOBI6 Palm database. Old byte-position indices are replaced, never reused. */
internal object MobiSourceWriter {
    private data class DirectoryEntry(val position: Int, val title: String, val parentTitle: String?)
    fun write(source: File, destination: File, originalHtml: String, edited: SourceHtmlEdit.Result,
              sections: List<MobiSection>) = RandomAccessFile(source, "r").use { input ->
        val database = ByteArray(78).also { input.readFully(it) }
        val count = u16(database, 76)
        val offsets = LongArray(count + 1)
        repeat(count) { index -> input.seek(78L + index * 8); offsets[index] = input.readInt().toLong() and 0xffffffffL }
        offsets[count] = input.length()
        fun record(index: Int): ByteArray {
            input.seek(offsets[index])
            return ByteArray((offsets[index + 1] - offsets[index]).toInt()).also { input.readFully(it) }
        }
        val originalHeader = record(0)
        val oldHeaderEnd = u32(originalHeader, 20).toInt() + 16
        val header = if (oldHeaderEnd >= 248) originalHeader else {
            val padding = 248 - oldHeaderEnd
            ByteArray(originalHeader.size + padding).also { expanded ->
                originalHeader.copyInto(expanded, 0, 0, oldHeaderEnd)
                originalHeader.copyInto(expanded, 248, oldHeaderEnd)
                put32(expanded, 20, 232)
                val titleOffset = u32(originalHeader, 84).toInt()
                if (titleOffset >= oldHeaderEnd) put32(expanded, 84, titleOffset + padding)
            }
        }
        val encoding = if (u32(header, 28) == 1252L) Charset.forName("windows-1252") else Charsets.UTF_8
        // Fixed-width filepos attributes let all byte targets be recalculated without recursive shifts.
        val filepos = Regex("(?i)\\bfilepos\\s*=\\s*(['\"]?)([0-9]+)\\1")
        val originalByteSize = originalHtml.toByteArray(encoding).size.toLong()
        val matches = filepos.findAll(originalHtml).filter { match ->
            match.groupValues[2].toLongOrNull()?.let { it in 0 until originalByteSize } == true
        }.toList()
        val oldByteTargets = matches.map { it.groupValues[2].toInt() }.toSet()
        val oldTargets = mutableMapOf<Int, Int>()
        var byte = 0
        var character = 0
        while (character < originalHtml.length) {
            if (byte in oldByteTargets) oldTargets[byte] = character
            val point = originalHtml.codePointAt(character)
            byte += if (encoding == Charsets.UTF_8) utf8Size(point) else 1
            character += Character.charCount(point)
        }
        val validMatches = matches.filter { it.groupValues[2].toInt() in oldTargets }
        val links = validMatches.map { match ->
            val group = requireNotNull(match.groups[2])
            SourceHtmlEdit.Change(edited.offset(group.range.first), edited.offset(group.range.last + 1),
                "0000000000")
        }
        var result = SourceHtmlEdit.apply(edited.html, links)
        if (links.isNotEmpty()) {
            val targets = validMatches.map { result.offset(edited.offset(oldTargets.getValue(it.groupValues[2].toInt()))) }
            val byteTargets = byteOffsets(result.html, targets, encoding)
            val updated = links.mapIndexed { index, link ->
                link.copy(replacement = byteTargets.getValue(targets[index]).toString().padStart(10, '0'))
            }
            result = SourceHtmlEdit.apply(edited.html, updated)
        }
        val html = if (encoding == Charsets.UTF_8) result.html else buildString {
            val encoder = encoding.newEncoder()
            var at = 0
            while (at < result.html.length) {
                val point = result.html.codePointAt(at)
                val value = String(Character.toChars(point))
                append(if (encoder.canEncode(value)) value else "&#$point;")
                at += Character.charCount(point)
            }
        }
        val text = html.toByteArray(encoding)
        require(text.size in 1..64 * 1024 * 1024) { "修改后的 MOBI 正文超过 64 MB" }
        val textRecords = (text.indices step 4096).map { at -> compress(text.copyOfRange(at, minOf(at + 4096, text.size))) }
        val oldFirstImage = u32(header, 108).takeIf { it in 1 until count.toLong() }?.toInt() ?: count
        // Only remove a complete trailing NCX index. All preceding resource slots (including fonts)
        // retain their relative record numbers, so recindex/kindle:embed references remain valid.
        var resourceEnd = count
        val oldNcx = u32(header, 244).takeIf { it >= oldFirstImage && it < count }?.toInt()
        if (oldNcx != null) {
            val index = record(oldNcx)
            if (index.size >= 56 && index.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "INDX") {
                val dataRecords = u32(index, 24)
                val stringRecords = u32(index, 52)
                if (dataRecords > 0 && stringRecords > 0 && oldNcx + 1L + dataRecords + stringRecords == count.toLong())
                    resourceEnd = oldNcx
            }
        }
        val resources = (oldFirstImage until resourceEnd).map(::record)
        val firstResource = 1 + textRecords.size
        val sectionTargets = sections.map { result.offset(edited.offset(it.sourceStart)).coerceIn(0, result.html.length) }
        val sectionBytes = byteOffsets(result.html, sectionTargets, encoding)
        val boundaryPositions = sections.mapIndexed { index, section ->
            DirectoryEntry(sectionBytes.getValue(sectionTargets[index]), section.title, section.tocParentTitle)
        }.distinctBy { it.position }.filter { it.position < text.size }
        val directory = directory(boundaryPositions, text.size)
        val ncxIndex = firstResource + resources.size
        put16(header, 0, 2)
        put32(header, 4, text.size)
        put16(header, 8, textRecords.size)
        put16(header, 10, 4096)
        put32(header, 36, 6)
        for (at in listOf(40, 44, 48, 52, 56, 60, 64, 68, 72, 76, 200, 208, 224, 256, 260))
            if (at + 4 <= u32(header, 20) + 16) put32(header, at, -1)
        put32(header, 80, firstResource)
        put32(header, 108, if (resources.isEmpty()) -1 else firstResource)
        for (at in listOf(112, 116, 120, 124, 204, 212, 228))
            if (at + 4 <= u32(header, 20) + 16) put32(header, at, 0)
        if (u32(header, 20) + 16 >= 196) { put16(header, 192, 1); put16(header, 194, textRecords.size) }
        if (u32(header, 20) + 16 >= 244) put16(header, 242, 0)
        if (u32(header, 20) + 16 >= 248) put32(header, 244, ncxIndex)
        val exthStart = u32(header, 20).toInt() + 16
        if (u32(header, 128) and 64L != 0L && exthStart + 12 <= header.size) {
            var at = exthStart + 12
            repeat(u32(header, exthStart + 8).toInt()) {
                val id = u32(header, at).toInt()
                val length = u32(header, at + 4).toInt()
                if (length == 12 && id in setOf(116, 121, 131)) put32(header, at + 8, if (id == 116) 0 else -1)
                at += length
            }
        }
        val records = listOf(header) + textRecords + resources + directory
        require(records.size < 65536) { "MOBI 记录数量超出格式限制" }
        put16(database, 76, records.size)
        destination.outputStream().buffered().use { output ->
            output.write(database)
            var at = 78 + records.size * 8 + 2
            records.forEachIndexed { index, bytes ->
                val entry = ByteArray(8)
                put32(entry, 0, at)
                put32(entry, 4, (index + 1) and 0xffffff)
                output.write(entry)
                at += bytes.size
            }
            output.write(byteArrayOf(0, 0))
            records.forEach(output::write)
        }
    }

    private fun directory(entries: List<DirectoryEntry>, textLength: Int): List<ByteArray> {
        val strings = mutableListOf(ByteArrayOutputStream())
        val labels = entries.map { entry ->
            val text = entry.title.toByteArray(Charsets.UTF_8).take(4096).toByteArray()
            val value = vlq(text.size) + text
            if (strings.last().size() + value.size > 65535) strings += ByteArrayOutputStream()
            val offset = (strings.lastIndex shl 16) + strings.last().size()
            strings.last().write(value)
            offset
        }
        val roots = mutableMapOf<String, Int>()
        val parents = entries.mapIndexed { index, entry ->
            if (entry.parentTitle == null) { roots[entry.title] = index; null } else roots[entry.parentTitle]
        }
        val children = parents.indices.filter { parents[it] != null }.groupBy { parents[it]!! }
        val fields = entries.mapIndexed { index, entry ->
            val child = children[index]
            val parent = parents[index]
            val control = 15 or (if (parent != null) 16 else 0) or (if (child != null) 96 else 0)
            val key = index.toString(16).padStart(4, '0').toByteArray(Charsets.US_ASCII)
            byteArrayOf(key.size.toByte()) + key + byteArrayOf(control.toByte()) + vlq(entry.position) +
                vlq((entries.getOrNull(index + 1)?.position ?: textLength) - entry.position) + vlq(labels[index]) + vlq(if (parent == null) 0 else 1) +
                (parent?.let(::vlq) ?: byteArrayOf()) +
                (child?.let { vlq(it.first()) + vlq(it.last()) } ?: byteArrayOf())
        }
        val batches = mutableListOf<MutableList<ByteArray>>()
        fields.forEach { field ->
            if (batches.isEmpty() || 192 + batches.last().sumOf { it.size + 2 } + field.size + 6 > 65535) batches += mutableListOf<ByteArray>()
            batches.last() += field
        }
        val summaries = batches.map { batch ->
            val last = batch.last()
            val key = last.copyOfRange(0, 1 + (last[0].toInt() and 255))
            key + ByteArray(2).also { put16(it, 0, batch.size) }
        }
        val mainIdxt = aligned(192 + 44 + summaries.sumOf { it.size })
        val main = ByteArray(aligned(mainIdxt + 4 + batches.size * 2))
        "INDX".toByteArray().copyInto(main)
        put32(main, 4, 192); put32(main, 16, 2); put32(main, 20, mainIdxt)
        put32(main, 24, batches.size); put32(main, 28, 65001); put32(main, 32, -1)
        put32(main, 36, entries.size); put32(main, 52, strings.size); put32(main, 180, 192)
        "TAGX".toByteArray().copyInto(main, 192)
        put32(main, 196, 44); put32(main, 200, 1)
        byteArrayOf(1, 1, 1, 0, 2, 1, 2, 0, 3, 1, 4, 0, 4, 1, 8, 0,
            21, 1, 16, 0, 22, 1, 32, 0, 23, 1, 64, 0, 0, 0, 0, 1).copyInto(main, 204)
        "IDXT".toByteArray().copyInto(main, mainIdxt)
        var summaryOffset = 236
        summaries.forEachIndexed { index, bytes ->
            put16(main, mainIdxt + 4 + index * 2, summaryOffset)
            bytes.copyInto(main, summaryOffset)
            summaryOffset += bytes.size
        }
        return listOf(main) + batches.map { batch ->
            val idxt = aligned(192 + batch.sumOf { it.size })
            val data = ByteArray(aligned(idxt + 4 + batch.size * 2))
            "INDX".toByteArray().copyInto(data)
            put32(data, 4, 192); put32(data, 12, 1); put32(data, 20, idxt); put32(data, 24, batch.size)
            put32(data, 28, -1); put32(data, 32, -1)
            var at = 192
            batch.forEachIndexed { index, field -> put16(data, idxt + 4 + index * 2, at); field.copyInto(data, at); at += field.size }
            "IDXT".toByteArray().copyInto(data, idxt)
            data
        } + strings.map { it.toByteArray() }
    }

    private fun compress(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        val previous = IntArray(65536) { -1 }
        val literal = ByteArrayOutputStream(8)
        fun flush() { if (literal.size() > 0) { output.write(literal.size()); literal.writeTo(output); literal.reset() } }
        var at = 0
        while (at < bytes.size) {
            var match = 0
            var distance = 0
            if (at + 2 < bytes.size) {
                val key = ((bytes[at].toInt() and 255) * 251 + (bytes[at + 1].toInt() and 255) * 31 + (bytes[at + 2].toInt() and 255)) and 65535
                val prior = previous[key]
                previous[key] = at
                if (prior >= 0 && at - prior <= 2047) {
                    while (match < minOf(10, bytes.size - at) && bytes[prior + match] == bytes[at + match]) match++
                    if (match >= 3) distance = at - prior
                }
            }
            if (distance > 0) {
                flush()
                val token = 0x8000 or (distance shl 3) or (match - 3)
                output.write(token ushr 8); output.write(token and 255); at += match
            } else {
                val token = bytes[at].toInt() and 255
                if (token == 0 || token in 9..127) { flush(); output.write(token) }
                else { literal.write(token); if (literal.size() == 8) flush() }
                at++
            }
        }
        flush()
        return output.toByteArray()
    }

    private fun vlq(value: Int): ByteArray {
        var remaining = value
        val bytes = mutableListOf((remaining and 127).toByte())
        remaining = remaining ushr 7
        while (remaining > 0) { bytes.add(0, (remaining and 127).toByte()); remaining = remaining ushr 7 }
        bytes[bytes.lastIndex] = (bytes.last().toInt() or 128).toByte()
        return bytes.toByteArray()
    }
    private fun aligned(value: Int): Int = (value + 3) and -4
    private fun utf8Size(point: Int): Int = when { point < 128 -> 1; point < 2048 -> 2; point < 65536 -> 3; else -> 4 }
    private fun byteOffsets(html: String, positions: List<Int>, encoding: Charset): Map<Int, Int> {
        val wanted = positions.toSet()
        val mapped = mutableMapOf<Int, Int>()
        var at = 0
        var bytes = 0
        val encoder = if (encoding == Charsets.UTF_8) null else encoding.newEncoder()
        while (at <= html.length) {
            if (at in wanted) mapped[at] = bytes
            if (at == html.length) break
            val point = html.codePointAt(at)
            bytes += if (encoder == null) utf8Size(point) else {
                val text = String(Character.toChars(point))
                if (encoder.canEncode(text)) text.toByteArray(encoding).size else "&#$point;".length
            }
            at += Character.charCount(point)
        }
        return mapped
    }
    private fun u16(bytes: ByteArray, at: Int): Int = ByteBuffer.wrap(bytes, at, 2).short.toInt() and 65535
    private fun u32(bytes: ByteArray, at: Int): Long = ByteBuffer.wrap(bytes, at, 4).int.toLong() and 0xffffffffL
    private fun put16(bytes: ByteArray, at: Int, value: Int) { ByteBuffer.wrap(bytes, at, 2).putShort(value.toShort()) }
    private fun put32(bytes: ByteArray, at: Int, value: Int) { ByteBuffer.wrap(bytes, at, 4).putInt(value) }
}
