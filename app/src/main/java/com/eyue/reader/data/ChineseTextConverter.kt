package com.eyue.reader.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Offline OpenCC dictionaries; conversion affects display only, never the stored book. */
internal class ChineseTextConverter private constructor(
    private val entries: Map<String, String>, private val lengths: Map<Char, IntArray>
) {
    internal data class Result(val text: String, val forward: IntArray?, val reverse: IntArray?)
    private val cache = LinkedHashMap<String, Result>(32, .75f, true)
    private var cachedCharacters = 0
    private val contextSize = lengths.values.maxOfOrNull { it.first() } ?: 1

    fun convert(source: String): Result {
        synchronized(cache) { cache[source]?.let { return it } }
        val result = convertUncached(source)
        if (source.length <= 16_384) synchronized(cache) {
            if (cache.put(source, result) == null) cachedCharacters += source.length
            while (cache.size > 32 || cachedCharacters > 131_072) {
                val oldest = cache.entries.iterator().next()
                cachedCharacters -= oldest.key.length
                cache.remove(oldest.key)
            }
        }
        return result
    }

    /** Include neighbouring words so a page boundary inside 头|发 still displays 頭|髮. */
    fun convertRange(source: String, start: Int, end: Int): Result {
        var left = (start - contextSize * 2).coerceAtLeast(0)
        var right = (end + contextSize * 2).coerceAtMost(source.length)
        if (left > 0 && Character.isLowSurrogate(source[left]) && Character.isHighSurrogate(source[left - 1])) left--
        if (right < source.length && Character.isLowSurrogate(source[right]) && Character.isHighSurrogate(source[right - 1])) right++
        val full = convert(source.substring(left, right))
        val from = start - left
        val to = end - left
        val displayStart = full.forward?.get(from) ?: from
        val displayEnd = full.forward?.get(to) ?: to
        val text = full.text.substring(displayStart, displayEnd)
        if (full.forward == null) return Result(text, null, null)
        return Result(text, IntArray(end - start + 1) { (full.forward[from + it] - displayStart).coerceIn(0, text.length) },
            IntArray(text.length + 1) { (full.reverse!![displayStart + it] - from).coerceIn(0, end - start) })
    }

    private fun convertUncached(source: String): Result {
        val output = StringBuilder(source.length)
        val ranges = ArrayList<IntArray>()
        var index = 0
        while (index < source.length) {
            var consumed = Character.charCount(source.codePointAt(index))
            var replacement: String? = null
            for (length in lengths[source[index]] ?: EMPTY_LENGTHS) {
                if (index + length > source.length) continue
                replacement = entries[source.substring(index, index + length)]
                if (replacement != null) { consumed = length; break }
            }
            val start = output.length
            if (replacement == null) output.append(source, index, index + consumed) else output.append(replacement)
            if (consumed != output.length - start) ranges += intArrayOf(index, index + consumed, start, output.length)
            index += consumed
        }
        if (ranges.isEmpty()) return Result(output.toString(), null, null)
        val forward = IntArray(source.length + 1)
        val reverse = IntArray(output.length + 1)
        var lastSource = 0
        var lastDisplay = 0
        ranges.forEach { (from, to, start, end) ->
            for (i in lastSource until from) forward[i] = lastDisplay + i - lastSource
            for (i in lastDisplay until start) reverse[i] = lastSource + i - lastDisplay
            for (i in from..to) forward[i] = start + (i - from) * (end - start) / (to - from)
            for (i in start..end) reverse[i] = from + (i - start) * (to - from) / (end - start)
            lastSource = to; lastDisplay = end
        }
        for (i in lastSource..source.length) forward[i] = lastDisplay + i - lastSource
        for (i in lastDisplay..output.length) reverse[i] = lastSource + i - lastDisplay
        return Result(output.toString(), forward, reverse)
    }

    companion object {
        private val EMPTY_LENGTHS = IntArray(0)
        private val lock = Mutex()
        private val loaded = mutableMapOf<ChineseConversion, ChineseTextConverter>()

        suspend fun load(context: Context, mode: ChineseConversion): ChineseTextConverter? {
            if (mode == ChineseConversion.ORIGINAL) return null
            return withContext(Dispatchers.IO) {
                lock.withLock {
                    loaded.getOrPut(mode) {
                        val prefix = if (mode == ChineseConversion.SIMPLIFIED) "TS" else "ST"
                        val values = HashMap<String, String>()
                        listOf("Characters", "Phrases").forEach { suffix ->
                            context.assets.open("opencc/$prefix$suffix.txt").bufferedReader().useLines { lines ->
                                lines.filter { it.isNotBlank() && !it.startsWith('#') }.forEach { line ->
                                    val parts = line.split('\t', limit = 2)
                                    if (parts.size == 2) values[parts[0]] = parts[1].substringBefore(' ')
                                }
                            }
                        }
                        ChineseTextConverter(values, values.keys.groupBy { it[0] }.mapValues { (_, keys) ->
                            keys.map { it.length }.distinct().sortedDescending().toIntArray()
                        })
                    }
                }
            }
        }
    }
}
