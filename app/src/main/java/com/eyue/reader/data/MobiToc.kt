package com.eyue.reader.data

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

internal data class MobiTocEntry(val byteOffset: Int, val title: String, val tocParentTitle: String? = null)

/** MOBI6 NCX record format: INDX headers, TAGX controls, IDXT offsets and CTOC strings.
 * Format reference: https://github.com/kevinhendricks/KindleUnpack/blob/master/lib/mobi_index.py
 * Parent entry numbers (tag 21): https://github.com/kevinhendricks/KindleUnpack/blob/master/lib/mobi_ncx.py
 * Independent implementation; an invalid optional directory falls back to the HTML directory. */
internal object MobiToc {
    private data class Tag(val id: Int, val values: Int, val mask: Int, val nextControl: Boolean)
    private data class Values(val tag: Tag, val count: Int?, val bytes: Int?)
    private data class Entry(val offset: Int, val label: Long, val parent: Long?)
    private class Cursor(val data: ByteArray, var position: Int, val end: Int) {
        fun value(): Long {
            var value = 0L
            repeat(5) {
                require(position < end)
                val byte = data[position++].toInt() and 255
                value = (value shl 7) or (byte and 127).toLong()
                require(value <= 0xffffffffL)
                if (byte and 128 != 0) return value
            }
            throw IllegalArgumentException("Unterminated MOBI index value")
        }
    }

    fun read(index: Int, recordCount: Int, textLength: Int, encoding: Charset,
             record: (Int) -> ByteArray, checkActive: () -> Unit): List<MobiTocEntry> {
        if (index !in 1 until recordCount) return emptyList()
        return try {
            var loaded = 0L
            fun load(number: Int): ByteArray {
                checkActive()
                require(number in 0 until recordCount)
                return record(number).also { loaded += it.size; require(loaded <= 8 * 1024 * 1024) }
            }
            val main = load(index)
            val headerSize = header(main)
            val records = number(main, 24)
            val stringRecords = number(main, 52)
            require(records > 0 && stringRecords > 0 && index.toLong() + records + stringRecords < recordCount)
            val charset = when (word(main, 28)) {
                65001L -> Charsets.UTF_8
                1252L -> Charset.forName("windows-1252")
                0xffffffffL -> encoding
                else -> return emptyList()
            }
            require(signature(main, headerSize, "TAGX"))
            val tagLength = number(main, headerSize + 4)
            val controls = number(main, headerSize + 8)
            require(tagLength >= 12 && tagLength <= 12 + 256 * 4 && (tagLength - 12) % 4 == 0 &&
                headerSize.toLong() + tagLength <= main.size && controls in 1..32)
            val tags = (headerSize + 12 until headerSize + tagLength step 4).map { at ->
                Tag(main[at].toInt() and 255, main[at + 1].toInt() and 255,
                    main[at + 2].toInt() and 255, (main[at + 3].toInt() and 255).also { require(it in 0..1) } == 1)
            }
            val entries = mutableListOf<Entry>()
            var totalEntries = 0
            repeat(records) { section ->
                val data = load(index + section + 1)
                val size = header(data)
                val start = number(data, 20)
                val count = number(data, 24)
                require(start >= size && signature(data, start, "IDXT") && start.toLong() + 4 + count * 2L <= data.size)
                totalEntries += count
                require(totalEntries <= 20_000)
                val positions = IntArray(count + 1) { entry -> if (entry == count) start else short(data, start + 4 + entry * 2) }
                repeat(count) { entry ->
                    checkActive()
                    val begin = positions[entry]
                    val end = positions[entry + 1]
                    require(begin >= size && begin < end && end <= start)
                    val textBytes = data[begin].toInt() and 255
                    val controlStart = begin + 1 + textBytes
                    require(controlStart.toLong() + controls <= end)
                    val cursor = Cursor(data, controlStart + controls, end)
                    var control = 0
                    val plans = mutableListOf<Values>()
                    tags.forEach { tag ->
                        if (tag.nextControl) control++ else {
                            require(control < controls)
                            val value = (data[controlStart + control].toInt() and 255) and tag.mask
                            if (value != 0) {
                                require(tag.values > 0)
                                if (value == tag.mask && Integer.bitCount(tag.mask) > 1) {
                                    val bytes = cursor.value()
                                    require(bytes <= end - cursor.position)
                                    plans += Values(tag, null, bytes.toInt())
                                } else plans += Values(tag, value ushr Integer.numberOfTrailingZeros(tag.mask), null)
                            }
                        }
                    }
                    var offset: Long? = null
                    var label: Long? = null
                    var parent: Long? = null
                    plans.forEach { plan ->
                        fun consume() {
                            val value = cursor.value()
                            if (plan.tag.id == 1 && offset == null) offset = value
                            if (plan.tag.id == 3 && label == null) label = value
                            if (plan.tag.id == 21 && parent == null && value != 0xffffffffL) parent = value
                        }
                        if (plan.bytes != null) {
                            val limit = cursor.position.toLong() + plan.bytes
                            require(limit <= end)
                            while (cursor.position < limit) consume()
                            require(cursor.position.toLong() == limit)
                        } else {
                            val values = plan.count!!.toLong() * plan.tag.values
                            require(values <= end - cursor.position)
                            repeat(values.toInt()) { consume() }
                        }
                    }
                    require(offset != null && offset!! < textLength && label != null)
                    entries += Entry(offset!!.toInt(), label!!, parent)
                }
            }
            val strings = mutableMapOf<Int, ByteArray>()
            val labels = mutableMapOf<Long, String>()
            val titles = entries.map { entry ->
                checkActive()
                labels.getOrPut(entry.label) {
                    val stringOffset = entry.label
                    val section = stringOffset / 0x10000
                    require(section < stringRecords)
                    val data = strings.getOrPut(section.toInt()) { load(index + records + 1 + section.toInt()) }
                    val at = (stringOffset % 0x10000).toInt()
                    require(at < data.size)
                    val cursor = Cursor(data, at, data.size)
                    val length = cursor.value()
                    require(length <= 4096 && cursor.position.toLong() + length <= data.size)
                    charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(data, cursor.position, length.toInt())).toString().trim()
                }
            }
            val roots = IntArray(entries.size) { -2 }
            fun root(index: Int): Int {
                var current = index
                val path = mutableSetOf<Int>()
                val result: Int
                while (true) {
                    checkActive()
                    if (roots[current] != -2) { result = roots[current]; break }
                    if (!path.add(current)) { result = -1; break }
                    val parent = entries[current].parent
                    if (parent == null) { result = current; break }
                    if (parent !in 0L until entries.size.toLong()) { result = -1; break }
                    current = parent.toInt()
                }
                path.forEach { roots[it] = result }
                return result
            }
            entries.mapIndexedNotNull { index, entry ->
                val title = titles[index]
                val parent = root(index).takeIf { it >= 0 && it != index }?.let { titles[it].takeIf(String::isNotBlank) }
                if (title.isEmpty()) null else MobiTocEntry(entry.offset, title, parent)
            }
        } catch (_: IllegalArgumentException) { emptyList() }
          catch (_: java.nio.charset.CharacterCodingException) { emptyList() }
    }

    private fun header(data: ByteArray): Int {
        require(data.size >= 56 && signature(data, 0, "INDX"))
        return number(data, 4).also { require(it >= 56 && it <= data.size) }
    }
    private fun signature(data: ByteArray, at: Int, value: String): Boolean = at >= 0 && at.toLong() + value.length <= data.size &&
        value.indices.all { data[at + it].toInt() == value[it].code }
    private fun word(data: ByteArray, at: Int): Long {
        require(at >= 0 && at.toLong() + 4 <= data.size)
        return (0..3).fold(0L) { value, index -> (value shl 8) or (data[at + index].toInt() and 255).toLong() }
    }
    private fun number(data: ByteArray, at: Int): Int = word(data, at).also { require(it <= Int.MAX_VALUE) }.toInt()
    private fun short(data: ByteArray, at: Int): Int {
        require(at >= 0 && at.toLong() + 2 <= data.size)
        return ((data[at].toInt() and 255) shl 8) or (data[at + 1].toInt() and 255)
    }
}
