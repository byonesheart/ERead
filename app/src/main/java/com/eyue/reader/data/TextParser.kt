package com.eyue.reader.data

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import java.util.Locale
import kotlin.math.abs

data class ParsedTextChapter(val title: String, val text: String, val inlineImages: List<InlineImage> = emptyList(),
                             val sourceStyles: List<SourceTextStyle> = emptyList(), val tocParentTitle: String? = null,
                             val sourceFootnotes: List<SourceFootnote> = emptyList())
data class TextMetadata(val title: String? = null, val author: String = "")

object TextParser {
    /** Existing chapter caches can be tightened without rereading a linked original. */
    internal fun compactParagraphs(chapter: ParsedTextChapter): Pair<ParsedTextChapter, IntArray> {
        val text = chapter.text
        val special = chapter.sourceStyles.filter { it.paragraph }.sortedBy { it.start }
        val offsets = IntArray(text.length + 1)
        val body = StringBuilder(text.length)
        var cursor = 0
        var paragraph = 0
        while (cursor < text.length) {
            if (text[cursor] != '\n') {
                body.append(text[cursor])
                offsets[++cursor] = body.length
            } else {
                val start = cursor
                while (cursor < text.length && text[cursor] == '\n') cursor++
                while (paragraph < special.size && special[paragraph].end < start) paragraph++
                val protected = paragraph < special.size && special[paragraph].start <= cursor
                val count = if (protected) minOf(2, cursor - start) else 1
                repeat(count) { body.append('\n') }
                for (index in start + 1..cursor) offsets[index] = body.length - count + minOf(index - start, count)
            }
        }
        val compacted = chapter.copy(text = body.toString(),
            inlineImages = chapter.inlineImages.map { it.copy(offset = offsets[it.offset.coerceIn(0, text.length)]) },
            sourceFootnotes = chapter.sourceFootnotes.map { it.copy(start = offsets[it.start.coerceIn(0, text.length)], end = offsets[it.end.coerceIn(0, text.length)]) },
            sourceStyles = chapter.sourceStyles.map { it.copy(start = offsets[it.start.coerceIn(0, text.length)], end = offsets[it.end.coerceIn(0, text.length)]) })
        return compacted to offsets
    }

    fun relocateImage(chapters: List<ParsedTextChapter>, path: String?, previousPosition: Long): Pair<Int, InlineImage>? {
        if (path == null) return null
        var preceding = 0L
        var distance = Long.MAX_VALUE
        var result: Pair<Int, InlineImage>? = null
        chapters.forEachIndexed { index, chapter ->
            chapter.inlineImages.filter { it.path == path }.forEach { image ->
                val current = abs(preceding + image.offset - previousPosition)
                if (current < distance) { result = index to image; distance = current }
            }
            preceding += chapter.text.length
        }
        return result
    }

    fun validateRule(rule: String) {
        require(rule.isNotBlank()) { "目录规则不能为空" }
        val regex = Regex(rule, RegexOption.MULTILINE)
        require(!regex.containsMatchIn("")) { "目录规则不能匹配空内容" }
    }

    fun decode(bytes: ByteArray): String {
        val decoded = when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            else -> try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: CharacterCodingException) {
                Charset.forName("GB18030").newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            }
        }
        return decoded.replace("\r\n", "\n").replace('\r', '\n').removePrefix("\uFEFF")
    }

    fun metadata(text: String): TextMetadata {
        val header = text.take(12_000).lineSequence().take(100).toList()
        val author = header.firstNotNullOfOrNull {
            Regex("^\\s*(?:作者|Author)\\s*[:：]\\s*(.+?)\\s*$", RegexOption.IGNORE_CASE)
                .matchEntire(it)?.groupValues?.get(1)
        }.orEmpty()
        val title = header.firstNotNullOfOrNull {
            Regex("^\\s*(?:书名|Title)\\s*[:：]\\s*(.+?)\\s*$", RegexOption.IGNORE_CASE)
                .matchEntire(it)?.groupValues?.get(1)
        }
        return TextMetadata(title, author)
    }

    fun split(text: String, rule: String): List<ParsedTextChapter> {
        validateRule(rule)
        val headings = Regex(rule, RegexOption.MULTILINE).findAll(text).toList()
        require(headings.none { it.value.isBlank() }) { "目录规则匹配到了空白内容，请收紧规则" }
        if (headings.isEmpty()) return listOf(ParsedTextChapter("正文", text.trim()))
        val result = mutableListOf<ParsedTextChapter>()
        val prefix = text.substring(0, headings.first().range.first).trim()
        if (prefix.isNotEmpty()) result += ParsedTextChapter("前言", prefix)
        headings.forEachIndexed { index, match ->
            val end = headings.getOrNull(index + 1)?.range?.first ?: text.length
            result += ParsedTextChapter(match.value.trim(), text.substring(match.range.first, end).trim())
        }
        return result
    }

    fun relocatePosition(oldChapters: List<String>, newChapters: List<String>, chapterIndex: Int,
                         offset: Int, quote: String = ""): Pair<Int, Int> {
        if (newChapters.isEmpty()) return 0 to 0
        val oldIndex = chapterIndex.coerceIn(0, oldChapters.lastIndex.coerceAtLeast(0))
        val oldText = oldChapters.getOrNull(oldIndex).orEmpty()
        val oldOffset = offset.coerceIn(0, oldText.length)
        val flatOffset = oldChapters.take(oldIndex).sumOf { it.length.toLong() } + oldOffset
        fun findAnchor(anchor: String, delta: Int): Pair<Int, Int>? {
            if (anchor.isBlank()) return null
            var best: Pair<Int, Int>? = null
            var distance = Long.MAX_VALUE
            var preceding = 0L
            newChapters.forEachIndexed { index, text ->
                var found = text.indexOf(anchor)
                while (found >= 0) {
                    val position = (found + delta).coerceIn(0, text.length)
                    val candidateDistance = abs(preceding + position - flatOffset)
                    if (candidateDistance < distance) {
                        best = index to position
                        distance = candidateDistance
                    }
                    found = text.indexOf(anchor, found + 1)
                }
                preceding += text.length
            }
            return best
        }
        findAnchor(quote.trim(), 0)?.let { return it }
        val excerptStart = (oldOffset - 24).coerceAtLeast(0)
        val excerptEnd = (oldOffset + 72).coerceAtMost(oldText.length)
        val excerpt = oldText.substring(excerptStart, excerptEnd)
        findAnchor(excerpt, oldOffset - excerptStart)?.let { return it }
        fun findWhitespaceAnchor(anchor: String, delta: Int): Pair<Int, Int>? {
            if (anchor.isBlank() || anchor.none { it.isWhitespace() }) return null
            val tokens = Regex("\\S+").findAll(anchor).map { Regex.escape(it.value) }.toList()
            val pattern = Regex(tokens.joinToString("\\s+"))
            val before = anchor.take(delta).count { !it.isWhitespace() }
            var best: Pair<Int, Int>? = null
            var distance = Long.MAX_VALUE
            var preceding = 0L
            newChapters.forEachIndexed { index, text ->
                pattern.findAll(text).forEach { match ->
                    var local = 0
                    var count = 0
                    while (local < match.value.length && count < before) {
                        if (!match.value[local].isWhitespace()) count++
                        local++
                    }
                    if (delta > 0 && anchor.getOrNull(delta - 1)?.isWhitespace() == true)
                        while (local < match.value.length && match.value[local].isWhitespace()) local++
                    val position = match.range.first + local
                    val candidateDistance = abs(preceding + position - flatOffset)
                    if (candidateDistance < distance) { best = index to position; distance = candidateDistance }
                }
                preceding += text.length
            }
            return best
        }
        findWhitespaceAnchor(quote.trim(), 0)?.let { return it }
        findWhitespaceAnchor(excerpt, oldOffset - excerptStart)?.let { return it }
        var remaining = flatOffset
        newChapters.forEachIndexed { index, text ->
            if (remaining < text.length || index == newChapters.lastIndex) {
                return index to remaining.coerceIn(0, text.length.toLong()).toInt()
            }
            remaining -= text.length
        }
        return 0 to 0
    }
}

/** Numeric name ordering keeps chapter 2 before chapter 10 without integer overflow. */
object NaturalOrder : Comparator<String> {
    private val tokens = Regex("\\d+|\\D+")

    override fun compare(first: String, second: String): Int {
        val left = tokens.findAll(first.lowercase(Locale.ROOT)).map { it.value }.toList()
        val right = tokens.findAll(second.lowercase(Locale.ROOT)).map { it.value }.toList()
        for (index in 0 until minOf(left.size, right.size)) {
            val a = left[index]
            val b = right[index]
            val comparison = if (a[0].isDigit() && b[0].isDigit()) {
                val an = a.trimStart('0').ifEmpty { "0" }
                val bn = b.trimStart('0').ifEmpty { "0" }
                an.length.compareTo(bn.length).takeIf { it != 0 } ?: an.compareTo(bn)
            } else a.compareTo(b)
            if (comparison != 0) return comparison
        }
        return left.size.compareTo(right.size).takeIf { it != 0 }
            ?: first.compareTo(second, ignoreCase = true)
    }
}
