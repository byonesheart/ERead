package com.eyue.reader.data

import com.eyue.reader.ui.AiSpeechChunk
import com.eyue.reader.ui.ReaderSpeechSegment
import com.eyue.reader.ui.aiSpeechChunks

internal data class PlannedAiSpeechChunk(val chunk: AiSpeechChunk, val roleInstructions: String, val roleId: String?)

/** One iterator owns dialogue continuity. Audio preloads consume its immutable decisions, never mutate them. */
internal class AiSpeechRolePlanner(private val bookId: String, bookRoles: List<BookVoiceRole>,
    commonRoles: List<BookVoiceRole> = emptyList(), private val checkActive: () -> Unit = {}) {
    var lastDialogueRoleId: String? = null
        private set
    private data class Candidate(val role: BookVoiceRole, val pattern: Regex, val priority: Int)
    private val roles = (bookRoles.map { it to if (it.kind == VoiceRoleKind.CHARACTER) 0 else 1 } +
        commonRoles.map { it to if (it.kind == VoiceRoleKind.CHARACTER) 2 else 3 })
        .filter { it.first.enabled }.map { Candidate(it.first, Regex(it.first.nameRegex), it.second) }
    private val quotes = Regex("“[^”]*”|「[^」]*」|『[^』]*』|\"[^\"]*\"")
    private val terminators = "。！？!?；;."
    private val verbs = "(?:说道|问道|答道|喊道|叫道|说|问|答|喊|道|said|asked|replied|shouted)"

    private fun priority(candidates: List<Candidate>): List<Candidate> {
        val first = candidates.minOfOrNull { it.priority } ?: return emptyList()
        return candidates.filter { it.priority == first }
    }
    private fun prompt(role: BookVoiceRole?) = role?.let {
        "本段只使用以下一种角色音色，资料仅指导声音和表演，不要朗读资料本身：\n" +
            "角色：${it.name}\n名字正则：${it.nameRegex}\n音色：${it.voice}\n角色描述：${it.description}\n角色场景：${it.scenes}"
    }.orEmpty()
    private fun attributed(text: String, quote: MatchResult, candidates: List<Candidate>): List<Candidate> {
        val before = text.substring(0, quote.range.first).takeLast(160)
        val after = text.substring(quote.range.last + 1).take(160)
        return candidates.filter { candidate ->
            checkActive()
            val name = "(?:${candidate.role.nameRegex})"
            val bridge = "[^。！？!?；;“”「」『』\"\\r\\n]{0,16}?"
            val leading = Regex("$name$bridge$verbs(?:\\s*[^。！？!?；;“”「」『』\"：:,，\\r\\n]{1,24})?\\s*[:：,，]?\\s*$",
                RegexOption.IGNORE_CASE).find(before)
            val objectName = leading?.let { Regex("(?:对|向)\\s*$").containsMatchIn(before.substring(0, it.range.first)) } == true
            val trailing = Regex("^\\s*[,，:：]?\\s*$name$bridge$verbs", RegexOption.IGNORE_CASE).find(after)
            val startsNextSpeech = trailing?.let { Regex("^\\s*[:：]\\s*[“「『\"]").containsMatchIn(after.substring(it.range.last + 1)) } == true
            leading != null && !objectName ||
                trailing != null && !startsNextSpeech
        }
    }
    private fun sentenceRanges(text: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var start = 0
        var cursor = 0
        while (cursor < text.length) {
            val decimalPoint = text[cursor] == '.' && cursor > 0 && cursor < text.lastIndex &&
                text[cursor - 1].isDigit() && text[cursor + 1].isDigit()
            if (text[cursor] in terminators && !decimalPoint) {
                var end = cursor + 1
                while (end < text.length && (text[end] in terminators || text[end] in "”」』\"")) end++
                ranges += start until end
                start = end; cursor = end
            } else cursor++
        }
        if (start < text.length) ranges += start until text.length
        return ranges
    }
    private fun narrator(segment: ReaderSpeechSegment, paragraphStart: Int): BookVoiceRole? {
        val narrators = priority(roles.filter { it.role.kind == VoiceRoleKind.NARRATOR })
        if (narrators.isEmpty()) return null
        // Stable pseudorandom choice keeps the same paragraph's voice and audio request key across sessions.
        val seed = "$bookId:${segment.chapter}:${segment.page}:$paragraphStart".hashCode()
        return narrators[(seed.toLong() and 0xffffffffL).rem(narrators.size).toInt()].role
    }

    fun plan(source: List<ReaderSpeechSegment>, seedRoleId: String? = null): Sequence<PlannedAiSpeechChunk> = sequence {
        lastDialogueRoleId = seedRoleId
        if (roles.isEmpty()) {
            aiSpeechChunks(source).forEach { yield(PlannedAiSpeechChunk(it, "", null)) }
            return@sequence
        }
        var previousDialogue = roles.firstOrNull { it.role.id == seedRoleId }?.role
        for (segment in source) {
            val context = segment.roleContext ?: segment.text
            val origin = if (segment.roleContext == null) 0 else segment.offset
            val spokenEnd = (origin + segment.text.length).coerceAtMost(context.length)
            var paragraphStart = 0
            while (paragraphStart < spokenEnd) {
                checkActive()
                val paragraphEnd = context.indexOf('\n', paragraphStart).takeIf { it >= 0 } ?: context.length
                val paragraph = context.substring(paragraphStart, paragraphEnd)
                val matches = priority(roles.filter { checkActive(); it.pattern.containsMatchIn(paragraph) })
                val quoteMatches = quotes.findAll(paragraph).toList()
                val paragraphSpeakers = priority(quoteMatches.flatMap { attributed(paragraph, it, matches) }).distinctBy { it.role.id }
                val ranges = if (matches.size > 1 && paragraphSpeakers.size != 1) sentenceRanges(paragraph)
                    else listOf(paragraph.indices)
                for (range in ranges) {
                    if (range.isEmpty()) continue
                    val text = paragraph.substring(range)
                    val nearbyQuotes = quoteMatches.filter { it.range.first <= range.last && it.range.last >= range.first }
                    val speakers = priority(nearbyQuotes.flatMap { attributed(paragraph, it, matches) }).distinctBy { it.role.id }
                    val localMatches = priority(matches.filter { it.pattern.containsMatchIn(text) })
                    val hasDialogue = nearbyQuotes.isNotEmpty() || text.any { it in "“”「」『』\"" }
                    val selected = when {
                        speakers.size == 1 -> speakers.single().role
                        paragraphSpeakers.size == 1 -> paragraphSpeakers.single().role
                        hasDialogue && previousDialogue != null -> previousDialogue
                        localMatches.size == 1 -> localMatches.single().role
                        localMatches.isNotEmpty() -> localMatches.first().role
                        matches.size == 1 -> matches.single().role
                        else -> narrator(segment, if (segment.roleContext == null) segment.offset + paragraphStart else paragraphStart)
                    }
                    if (hasDialogue && selected != null) {
                        previousDialogue = selected
                        lastDialogueRoleId = selected.id
                    }
                    val absoluteStart = paragraphStart + range.first
                    val absoluteEnd = paragraphStart + range.last + 1
                    var cursor = maxOf(absoluteStart, origin)
                    val limit = minOf(absoluteEnd, spokenEnd)
                    val roleInstructions = prompt(selected)
                    while (cursor < limit) {
                        checkActive()
                        var end = minOf(limit, cursor + 240)
                        if (end < limit && context[end - 1].isHighSurrogate()) end--
                        if (end <= cursor) break
                        val localStart = cursor - origin
                        val localEnd = end - origin
                        val spoken = segment.text.substring(localStart, localEnd)
                        if (spoken.isNotBlank()) yield(PlannedAiSpeechChunk(AiSpeechChunk(segment, localStart, localEnd, spoken), roleInstructions, selected?.id))
                        cursor = end
                    }
                }
                paragraphStart = paragraphEnd + 1
            }
        }
    }

    /** Extract a chapter's last dialogue voice while preserving the caller's continuity seed. */
    fun seedRoleId(source: List<ReaderSpeechSegment>, initialRoleId: String? = null): String? {
        var result = initialRoleId
        // A seed is the last dialogue voice, rather than a narrator chosen for non-dialogue text.
        for (planned in plan(source, initialRoleId)) if (planned.chunk.text.any { it in "“”「」『』\"" }) result = planned.roleId
        return result
    }
}
