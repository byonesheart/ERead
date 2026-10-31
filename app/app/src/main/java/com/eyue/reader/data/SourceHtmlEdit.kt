package com.eyue.reader.data

import java.util.UUID

/** Edits literal text only: CSS, attributes, links, images and surrounding tags stay byte-for-byte intact. */
internal object SourceHtmlEdit {
    data class Change(val start: Int, val end: Int, val replacement: String)
    data class Result(val html: String, val changes: List<Change>) {
        fun offset(original: Int): Int {
            var delta = 0
            changes.sortedBy { it.start }.forEach { change ->
                if (original <= change.start) return original + delta
                if (original < change.end) return change.start + delta +
                    minOf(original - change.start, change.replacement.length)
                delta += change.replacement.length - (change.end - change.start)
            }
            return original + delta
        }
    }

    fun plain(html: String, image: (String) -> String?): String = plain(html, {}, image)

    fun plain(html: String, checkActive: () -> Unit, image: (String) -> String?): String =
        HtmlChapterParser.readPlain("", html, checkActive, image).text

    fun replace(html: String, target: String, image: (String) -> String?): Result = replace(html, target, {}, image)

    fun replace(html: String, target: String, checkActive: () -> Unit, image: (String) -> String?): Result {
        val original = plain(html, checkActive, image)
        if (original == target) return Result(html, emptyList())
        var start = 0
        while (start < minOf(original.length, target.length) && original[start] == target[start]) start++
        if (start > 0 && start < original.length && original[start].isLowSurrogate()) start--
        var end = original.length
        var targetEnd = target.length
        while (end > start && targetEnd > start && original[end - 1] == target[targetEnd - 1]) { end--; targetEnd-- }
        if (end < original.length && original[end].isLowSurrogate()) { end++; targetEnd++ }
        val spans = textSpans(html, checkActive)
        val candidates = IntArray(spans.sumOf { it.last - it.first + 2 })
        var candidateCount = 0
        spans.forEach { span ->
            checkActive()
            var position = span.first
            candidates[candidateCount++] = position
            while (position <= span.last) {
                val entityEnd = if (html[position] == '&') html.indexOf(';', position + 1).takeIf { it in position + 2..minOf(span.last, position + 32) } else null
                position = entityEnd?.plus(1) ?: (position + Character.charCount(html.codePointAt(position)))
                candidates[candidateCount++] = position
            }
        }
        require(candidateCount > 0) { "源文件没有可修改的正文" }
        val marker = "\uE000${UUID.randomUUID()}\uE001"
        val probes = mutableMapOf<Int, Int>()
        fun probe(index: Int): Int = probes.getOrPut(index) {
            checkActive()
            val position = candidates[index]
            val (text, location) = HtmlChapterParser.probe(html.substring(0, position) + marker + html.substring(position),
                marker, checkActive, image)
            require(text == original) { "源文件包含不兼容的文本结构，修改未保存" }
            location
        }
        fun locate(offset: Int, last: Boolean = false): Int {
            var low = 0
            var high = candidateCount - 1
            while (low < high) {
                val mid = (low + high) ushr 1
                if (probe(mid) < offset) low = mid + 1 else high = mid
            }
            require(probe(low) == offset) { "无法精确定位源文件文字，修改未保存" }
            if (last) {
                high = candidateCount - 1
                while (low < high) {
                    val mid = (low + high + 1) ushr 1
                    if (probe(mid) <= offset) low = mid else high = mid - 1
                }
            }
            return candidates[low]
        }
        val oldLines = original.split('\n')
        val newLines = target.split('\n')
        val textChanges = if (oldLines.size == newLines.size) {
            var offset = 0
            oldLines.zip(newLines).mapNotNull { (old, new) ->
                checkActive()
                val lineOffset = offset
                offset += old.length + 1
                if (old == new) null else {
                    var from = 0
                    while (from < minOf(old.length, new.length) && old[from] == new[from]) from++
                    if (from > 0 && from < old.length && old[from].isLowSurrogate()) from--
                    var until = old.length
                    var newUntil = new.length
                    while (until > from && newUntil > from && old[until - 1] == new[newUntil - 1]) { until--; newUntil-- }
                    if (until < old.length && old[until].isLowSurrogate()) { until++; newUntil++ }
                    Change(lineOffset + from, lineOffset + until, new.substring(from, newUntil))
                }
            }
        } else listOf(Change(start, end, target.substring(start, targetEnd)))
        val changes = textChanges.flatMap { change ->
            checkActive()
            val from = locate(change.start, last = true)
            val until = if (change.end == change.start) from else locate(change.end)
            val inserted = escape(change.replacement).replace("\n", "<br/>")
            spans.mapNotNull { span ->
                val left = maxOf(from, span.first)
                val right = minOf(until, span.last + 1)
                if (right > left) Change(left, right, if (left == from) inserted else "") else null
            }.toMutableList().also { if (it.none { item -> item.start == from }) it += Change(from, from, inserted) }
        }
        val result = apply(html, changes)
        require(plain(result.html, checkActive, image) == target) { "此编辑会改变段落结构，无法安全覆盖源文件；修改未保存" }
        return result
    }

    fun apply(html: String, changes: List<Change>): Result {
        val output = StringBuilder(html)
        changes.sortedWith(compareByDescending<Change> { it.start }.thenByDescending { it.end })
            .forEach { output.replace(it.start, it.end, it.replacement) }
        return Result(output.toString(), changes)
    }

    private fun textSpans(html: String, checkActive: () -> Unit): List<IntRange> {
        val visible = hideHtmlContent(maskHtmlComments(html), preserveOffsets = true, checkActive = checkActive)
        val spans = mutableListOf<IntRange>()
        var cursor = 0
        fun add(end: Int) {
            if (end > cursor && visible.substring(cursor, end) == html.substring(cursor, end)) spans += cursor until end
        }
        sourceHtmlTags(html, checkActive).forEach { tag -> add(tag.start); cursor = tag.end }
        add(html.length)
        return spans
    }

    fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")
}
