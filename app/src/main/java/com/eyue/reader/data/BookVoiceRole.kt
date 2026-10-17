package com.eyue.reader.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class VoiceRoleKind { CHARACTER, NARRATOR }

data class BookVoiceRole(
    val name: String,
    val nameRegex: String,
    val voice: String = "",
    val description: String = "",
    val scenes: String = "",
    val aliases: List<String> = emptyList(),
    val id: String = UUID.randomUUID().toString(),
    val enabled: Boolean = true,
    val kind: VoiceRoleKind = VoiceRoleKind.CHARACTER
) {
    val characterCount: Int get() = listOf(name, nameRegex, voice, description, scenes).sumOf { it.codePointCount(0, it.length) }
    init {
        require(name.isNotBlank() && nameRegex.isNotBlank()) { "请填写角色名字和名字正则" }
        require(id.isNotBlank() && characterCount <= 3000) { "单个角色信息最多 3000 字" }
        require(runCatching { Regex(nameRegex) }.isSuccess) { "名字正则无效，请检查表达式" }
    }
    internal fun toJson() = JSONObject().put("id", id).put("name", name).put("nameRegex", nameRegex)
        .put("voice", voice).put("description", description).put("scenes", scenes).put("aliases", JSONArray(aliases))
        .put("enabled", enabled).put("kind", kind.name)
    companion object {
        internal fun fromJson(json: JSONObject) = BookVoiceRole(json.getString("name"), json.getString("nameRegex"),
            json.optString("voice"), json.optString("description"), json.optString("scenes"),
            json.optJSONArray("aliases")?.let { list -> List(list.length()) { list.getString(it) } }.orEmpty(), json.getString("id"),
            json.optBoolean("enabled", true), runCatching { VoiceRoleKind.valueOf(json.optString("kind", "CHARACTER")) }.getOrDefault(VoiceRoleKind.CHARACTER))
    }
}

fun defaultCommonVoiceRoles(): List<BookVoiceRole> = listOf(
    BookVoiceRole("男人", "男人", "自然沉稳的成年男声", "旁白清晰自然", id = "common-narrator-male", kind = VoiceRoleKind.NARRATOR),
    BookVoiceRole("女人", "女人", "自然清晰的成年女声", "旁白平静自然", id = "common-narrator-female", kind = VoiceRoleKind.NARRATOR)
)

internal fun BookVoiceRole.addName(alias: String): BookVoiceRole {
    val added = alias.trim()
    require(added.isNotBlank()) { "请输入要添加的名字" }
    require(added != name && added !in aliases && !Regex(nameRegex).containsMatchIn(added)) { "该名字已存在" }
    return copy(nameRegex = "(?:$nameRegex)|${Regex.escape(added)}", aliases = aliases + added)
}

/** Match the complete paragraph around a transport chunk, keeping a long paragraph's voice consistent. */
internal fun roleVoicePrompt(roles: List<BookVoiceRole>, text: String, start: Int = 0, end: Int = text.length): String {
    return RoleVoicePromptResolver(roles).prompt(text, start, end)
}

/** Keep the current paragraph and two preloaded paragraphs; long paragraphs are matched only once. */
internal class RoleVoicePromptResolver(roles: List<BookVoiceRole>) {
    private val planner = AiSpeechRolePlanner("", roles)
    private data class PromptRange(val from: Int, val until: Int, val prompt: String)
    private data class Entry(val text: String, val from: Int, val until: Int, val ranges: List<PromptRange>)
    private val recent = java.util.ArrayDeque<Entry>()
    @Synchronized
    fun prompt(text: String, start: Int = 0, end: Int = text.length): String {
        if (text.isEmpty()) return ""
        var position = start.coerceIn(0, text.length)
        val limit = end.coerceIn(position, text.length)
        while (position < limit) {
            if (text[position] == '\n') { position++; continue }
            val entry = recent.firstOrNull { it.text === text && position >= it.from && position < it.until } ?: run {
                val from = text.lastIndexOf('\n', position - 1) + 1
                val until = text.indexOf('\n', position).takeIf { it >= 0 } ?: text.length
                val ranges = mutableListOf<PromptRange>()
                planner.plan(listOf(com.eyue.reader.ui.ReaderSpeechSegment(0, 0, from,
                    text.substring(from, until), text))).forEach { planned ->
                    val range = PromptRange(from + planned.chunk.start, from + planned.chunk.end, planned.roleInstructions)
                    val previous = ranges.lastOrNull()
                    if (previous != null && previous.until == range.from && previous.prompt === range.prompt)
                        ranges[ranges.lastIndex] = previous.copy(until = range.until)
                    else ranges += range
                }
                Entry(text, from, until, ranges).also {
                    if (recent.size == 3) recent.removeFirst()
                    recent.addLast(it)
                }
            }
            entry.ranges.firstOrNull { it.until > position && it.from < limit }?.let { return it.prompt }
            position = entry.until
        }
        return ""
    }
}
