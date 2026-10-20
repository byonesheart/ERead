package com.eyue.reader.ui

import com.eyue.reader.data.InlineImage
import kotlin.math.roundToLong

/** Stable text blocks for lazy scrolling. Font changes do not change these UTF-16 boundaries. */
internal fun readerScrollBlocks(text: String, images: List<InlineImage>, checkActive: () -> Unit = {}): List<ReaderTextPage> {
    val blocks = mutableListOf<ReaderTextPage>()
    var offset = 0
    fun appendUntil(limit: Int) {
        while (offset < limit) {
            checkActive()
            var end = minOf(limit, offset + 1_024)
            if (end < limit) {
                var newline = end - 1
                while (newline >= offset && text[newline] != '\n') newline--
                if (newline >= offset) end = newline + 1
                else if (Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
            }
            blocks += ReaderTextPage(offset, end)
            offset = end
        }
    }
    images.withIndex().sortedBy { it.value.offset }.forEach { (index, image) ->
        val position = image.offset.coerceIn(offset, text.length)
        appendUntil(position)
        blocks += ReaderTextPage(position, position, index)
    }
    appendUntil(text.length)
    return blocks.ifEmpty { listOf(ReaderTextPage(0, 0)) }
}

internal data class ReaderScrollLocation(val chapter: Int, val offset: Int, val imageIndex: Int? = null)

/** Progress uses text positions and image slots, rather than pretending scroll blocks are screen pages. */
internal class ReaderScrollIndex(private val lengths: List<Int>, private val images: List<List<InlineImage>>,
                                 private val texts: List<String>) {
    constructor(texts: List<String>, images: List<List<InlineImage>>) : this(texts.map { it.length }, images, texts)
    private val weights = lengths.mapIndexed { index, length -> (length.toLong() + images.getOrNull(index).orEmpty().size).coerceAtLeast(1) }
    private val starts = weights.runningFold(0L, Long::plus)
    private val total = starts.last().coerceAtLeast(1)

    fun progress(chapter: Int, offset: Int, imageIndex: Int?): Float {
        val length = lengths.getOrNull(chapter) ?: return 0f
        val position = offset.coerceIn(0, length)
        val imageSlots = images.getOrNull(chapter).orEmpty().withIndex().count { (index, image) ->
            image.offset < position || image.offset == position && (imageIndex == null || index <= imageIndex)
        }
        val inside = (position.toLong() + imageSlots + if (imageIndex == null) 1 else 0).coerceAtMost(weights[chapter])
        return ((starts[chapter] + inside).toDouble() / total).toFloat().coerceIn(0f, 1f)
    }

    fun location(fraction: Float): ReaderScrollLocation {
        if (lengths.isEmpty()) return ReaderScrollLocation(0, 0)
        val target = (fraction.coerceIn(0f, 1f).toDouble() * (total - 1)).roundToLong()
        val chapter = starts.dropLast(1).indexOfLast { it <= target }.coerceAtLeast(0)
        val local = target - starts[chapter]
        var units = 0L
        var offset = 0
        images.getOrNull(chapter).orEmpty().forEachIndexed { index, image ->
            val position = image.offset.coerceIn(offset, lengths[chapter])
            val length = position - offset
            if (local < units + length) return textLocation(chapter, offset + (local - units).toInt())
            units += length
            if (local == units) return ReaderScrollLocation(chapter, position, index)
            units++
            offset = position
        }
        return textLocation(chapter, offset + (local - units).toInt())
    }

    private fun textLocation(chapter: Int, offset: Int): ReaderScrollLocation {
        val text = texts.getOrNull(chapter).orEmpty()
        var position = offset.coerceIn(0, lengths[chapter])
        if (position in 1 until text.length && Character.isLowSurrogate(text[position]) && Character.isHighSurrogate(text[position - 1])) position--
        return ReaderScrollLocation(chapter, position)
    }
}
