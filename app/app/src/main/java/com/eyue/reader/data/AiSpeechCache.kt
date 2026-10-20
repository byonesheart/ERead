package com.eyue.reader.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

data class AiSpeechChapterCacheStatus(val currentChapters: Set<Int> = emptySet(), val otherChapters: Set<Int> = emptySet(),
    val emptyChapters: Set<Int> = emptySet())

/** Completed audio is kept per book; no API credentials or readable text are stored in its filenames. */
class AiSpeechCache(private val context: Context) {
    private val root = File(context.filesDir, "ai-speech-cache")
    private fun directory(bookId: String) = File(root, digest(bookId.toByteArray(Charsets.UTF_8)))
    internal fun generation(bookId: String): Long = synchronized(lock) { generations[directory(bookId).absolutePath] ?: 0L }
    internal fun find(bookId: String, key: String, extension: String): File? = synchronized(lock) {
        File(directory(bookId), "$key.$extension").takeIf { it.isFile && it.length() > 0 }
    }
    internal fun remove(bookId: String, key: String, extension: String) = synchronized(lock) {
        File(directory(bookId), "$key.$extension").delete()
        Unit
    }
    internal fun commit(bookId: String, key: String, extension: String, temporary: File, generation: Long,
                        overwrite: Boolean = false): File? = synchronized(lock) {
        val directory = directory(bookId)
        if ((generations[directory.absolutePath] ?: 0L) != generation) return@synchronized null
        require(temporary.isFile && temporary.length() > 0) { "AI 语音没有返回音频" }
        require(directory.isDirectory || directory.mkdirs()) { "无法保存 AI 语音缓存" }
        val target = File(directory, "$key.$extension")
        if (!overwrite && target.isFile && target.length() > 0) { temporary.delete(); return@synchronized target }
        if (overwrite) {
            try { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (error: IOException) { throw IOException("无法原子更新 AI 语音缓存，原音频已保留", error) }
        } else check(temporary.renameTo(target)) { "无法保存 AI 语音缓存" }
        target
    }
    fun bytes(bookId: String): Long = synchronized(lock) {
        directory(bookId).listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L
    }
    /** Completion records index finished chapters; badge refreshes never read or plan book text. */
    suspend fun completedChapters(book: Book, config: AiSpeechConfig): Set<Int> = chapterCacheStatus(book, config).currentChapters

    /** Keep completion identities for old configurations without accepting their audio for a new request. */
    suspend fun chapterCacheStatus(book: Book, config: AiSpeechConfig?,
        commonRoles: List<BookVoiceRole>? = null): AiSpeechChapterCacheStatus = withContext(Dispatchers.IO) {
        if (book.mode != LibraryMode.NOVEL || book.cloudOnly || !directory(book.id).isDirectory) return@withContext AiSpeechChapterCacheStatus()
        val expectedGeneration = generation(book.id)
        val common = commonRoles ?: LibraryRepository(context).load().commonVoiceRoles
        val current = mutableSetOf<Int>()
        val other = mutableSetOf<Int>()
        val empty = mutableSetOf<Int>()
        val sources = ChapterSourceMetadata(book)
        val chapterSeeds = mutableMapOf<Int, String?>(0 to null)
        val active = currentCoroutineContext()
        val manifests = synchronized(lock) { directory(book.id).listFiles()?.filter { it.name.startsWith("chapter-") && it.extension == "json" }.orEmpty() }
        val configuration = config?.let(::configurationIdentity)
        val roles = roleScope(book, true, common)
        for (file in manifests) {
            active.ensureActive()
            val row = synchronized(lock) { runCatching { JSONObject(AtomicFile(file).readFully().toString(Charsets.UTF_8)) }.getOrNull() } ?: continue
            val index = row.optInt("chapter", -1)
            if (index !in book.chapters.indices || row.optInt("schema", -1) != 2 || row.optInt("version", -1) != book.contentVersion) continue
            val source = sources.chapter(index) ?: continue
            if (row.optString("source") != source) continue
            val keys = row.optJSONArray("keys") ?: continue
            if (row.optBoolean("empty") && keys.length() == 0) { empty += index; continue }
            val roleEnabled = row.optBoolean("rolePromptEnabled")
            if (row.optString("roles") != (if (roleEnabled) roles else "disabled")) continue
            if (roleEnabled) {
                val preceding = sources.preceding(index) ?: continue
                if (row.optString("precedingSource") != preceding ||
                    row.optString("seed") != readChapterRoleSeed(book, index, roles, sources::preceding, chapterSeeds).orEmpty()) continue
            }
            val identity = row.optString("configuration").takeIf { it.matches(cacheKeyPattern) } ?: continue
            val extension = row.optString("extension").takeIf { it in setOf("wav", "mp3") } ?: continue
            if (keys.length() > 0 && (0 until keys.length()).all { offset ->
                val key = keys.optString(offset)
                key.matches(cacheKeyPattern) && find(book.id, key, extension) != null
            }) {
                if (identity == configuration) current += index else other += index
            }
        }
        if (generation(book.id) == expectedGeneration) AiSpeechChapterCacheStatus(current, other - current, empty) else AiSpeechChapterCacheStatus()
    }

    /** Live narration calls this after the final chunk. Partial chapters can never create a completion record. */
    suspend fun recordChapterCompletion(book: Book, config: AiSpeechConfig, chapterIndex: Int,
        commonRoles: List<BookVoiceRole>? = null, seedRoleId: String? = null): Boolean = withContext(Dispatchers.IO) {
        if (book.mode != LibraryMode.NOVEL || book.cloudOnly || chapterIndex !in book.chapters.indices) return@withContext false
        val expectedGeneration = generation(book.id)
        val common = commonRoles ?: if (config.rolePromptEnabled) LibraryRepository(context).load().commonVoiceRoles else emptyList()
        val seed = if (config.rolePromptEnabled) seedRoleId ?: chapterRoleSeed(book, chapterIndex, common) else null
        val active = currentCoroutineContext()
        val keys = aiSpeechPlannedChapter(book, config, chapterIndex, common, seed) { active.ensureActive() }
            .map { key(config, it.chunk.text, it.roleInstructions) }.toList()
        recordPlannedChapterCompletion(book, config, chapterIndex, common, seed, keys, expectedGeneration)
    }

    internal fun recordPlannedChapterCompletion(book: Book, config: AiSpeechConfig, chapter: Int,
        commonRoles: List<BookVoiceRole>, seedRoleId: String?, keys: List<String>, expectedGeneration: Long): Boolean {
        if (book.mode != LibraryMode.NOVEL || book.cloudOnly || chapter !in book.chapters.indices || keys.any { !it.matches(cacheKeyPattern) }) return false
        val source = sourceMetadata(book, chapter) ?: return false
        val precedingSource = if (config.rolePromptEnabled && keys.isNotEmpty()) precedingSourceMetadata(book, chapter) ?: return false else ""
        val row = JSONObject().put("schema", 2).put("chapter", chapter).put("version", book.contentVersion)
            .put("configuration", configurationIdentity(config)).put("source", source).put("precedingSource", precedingSource)
            .put("rolePromptEnabled", config.rolePromptEnabled).put("roles", rolesDigest(book, config, commonRoles))
            .put("seed", seedRoleId.orEmpty()).put("extension", if (config.protocol == SpeechApiProtocol.MIMO) "wav" else "mp3")
            .put("keys", JSONArray(keys)).put("empty", keys.isEmpty())
        return synchronized(lock) {
            if (generation(book.id) != expectedGeneration || keys.any { find(book.id, it, row.getString("extension")) == null }) return@synchronized false
            val directory = directory(book.id)
            if (!directory.isDirectory && (keys.isNotEmpty() || !directory.mkdirs())) return@synchronized false
            val identity = digest((row.getString("configuration") + ":" + book.contentVersion + ":" + row.getString("roles") + ":" + chapter).toByteArray(Charsets.UTF_8))
            val file = AtomicFile(File(directory, "chapter-$identity.json"))
            val stream = file.startWrite()
            try { stream.write(row.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
            catch (error: Throwable) { file.failWrite(stream); throw error }
            true
        }
    }

    /** A cold jump only consults the previous chapter's saved dialogue state, never its text. */
    internal fun chapterRoleSeed(book: Book, chapter: Int, commonRoles: List<BookVoiceRole>): String? {
        if (chapter !in book.chapters.indices || chapter == 0) return null
        val expectedGeneration = generation(book.id)
        val sources = ChapterSourceMetadata(book)
        val seed = readChapterRoleSeed(book, chapter, roleScope(book, true, commonRoles), sources::preceding, mutableMapOf(0 to null))
        return seed.takeIf { generation(book.id) == expectedGeneration }
    }

    internal fun recordChapterRoleSeed(book: Book, chapter: Int, commonRoles: List<BookVoiceRole>,
        seedRoleId: String?, tailRoleId: String?, expectedGeneration: Long) {
        if (chapter !in book.chapters.indices || book.mode != LibraryMode.NOVEL || book.cloudOnly) return
        val source = precedingSourceMetadata(book, chapter + 1) ?: return
        val roles = roleScope(book, true, commonRoles)
        val row = JSONObject().put("schema", 2).put("chapter", chapter).put("version", book.contentVersion)
            .put("roles", roles).put("source", source).put("seed", seedRoleId.orEmpty()).put("tail", tailRoleId.orEmpty())
        synchronized(lock) {
            if (generation(book.id) != expectedGeneration) return
            val directory = directory(book.id)
            if (!directory.isDirectory && !directory.mkdirs()) return
            val file = AtomicFile(roleSeedFile(book, chapter, roles))
            val stream = file.startWrite()
            try { stream.write(row.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
            catch (error: Throwable) { file.failWrite(stream); throw error }
        }
    }

    private fun roleSeedFile(book: Book, chapter: Int, roles: String): File = File(directory(book.id),
        "role-${digest("${book.contentVersion}:$roles:$chapter".toByteArray(Charsets.UTF_8))}.json")

    /** Resolve dependencies iteratively; one badge scan reads each checkpoint at most once. */
    private fun readChapterRoleSeed(book: Book, chapter: Int, roles: String, precedingSource: (Int) -> String?,
        seeds: MutableMap<Int, String?>): String? {
        val pending = mutableListOf<Pair<Int, JSONObject>>()
        var current = chapter
        while (current > 0 && !seeds.containsKey(current)) {
            val row = synchronized(lock) { runCatching {
                JSONObject(AtomicFile(roleSeedFile(book, current - 1, roles)).readFully().toString(Charsets.UTF_8))
            }.getOrNull() }
            if (row == null || row.optInt("schema", -1) != 2 || row.optInt("chapter", -1) != current - 1 ||
                row.optInt("version", -1) != book.contentVersion || row.optString("roles") != roles ||
                row.optString("source") != precedingSource(current)) {
                seeds[current] = null
                break
            }
            pending += current to row
            current--
        }
        for ((index, row) in pending.asReversed()) {
            seeds[index] = if (row.optString("seed") == seeds[index - 1].orEmpty()) row.optString("tail").takeIf { it.isNotEmpty() } else null
        }
        return seeds[chapter]
    }

    private class ChapterSourceMetadata(private val book: Book) {
        private val sources = mutableMapOf<Int, String?>()
        private val prefixes = mutableListOf<String?>("")
        fun chapter(index: Int): String? {
            if (!sources.containsKey(index)) sources[index] = sourceMetadata(book, index)
            return sources[index]
        }
        fun preceding(index: Int): String? {
            while (prefixes.size <= index) {
                val previous = prefixes.last()
                val source = chapter(prefixes.lastIndex)
                prefixes += if (previous == null || source == null) null else digest("$previous:$source".toByteArray(Charsets.UTF_8))
            }
            return prefixes[index]
        }
    }
    /** In-flight requests that started before this clear cannot write their audio back. */
    fun clear(bookId: String) = synchronized(lock) {
        val directory = directory(bookId)
        generations[directory.absolutePath] = (generations[directory.absolutePath] ?: 0L) + 1L
        directory.listFiles()?.forEach { it.delete() }
        directory.delete()
        Unit
    }
    companion object {
        private val lock = Any()
        private val generations = mutableMapOf<String, Long>()
        private val cacheKeyPattern = Regex("[0-9a-f]{64}")
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        internal fun rolesDigest(book: Book, config: AiSpeechConfig, commonRoles: List<BookVoiceRole> = emptyList()): String =
            roleScope(book, config.rolePromptEnabled, commonRoles)
        private fun roleScope(book: Book, enabled: Boolean, commonRoles: List<BookVoiceRole>): String = if (!enabled) "disabled" else
            digest(JSONObject().put("book", JSONArray(book.voiceRoles.map { it.toJson() }))
                .put("common", JSONArray(commonRoles.map { it.toJson() })).toString().toByteArray(Charsets.UTF_8))
        internal fun configurationIdentity(config: AiSpeechConfig): String = digest((key(config, "") + ":" + config.rolePromptEnabled).toByteArray(Charsets.UTF_8))
        private fun sourceMetadata(book: Book, chapterIndex: Int): String? {
            val chapter = book.chapters[chapterIndex]
            val paths = chapter.textPath?.let { listOf(0 to it) } ?: chapter.pages.mapIndexedNotNull { index, page -> page.textPath?.let { index to it } }
            val metadata = JSONArray().put(chapter.id)
            for ((page, path) in paths) {
                val file = File(path)
                if (!file.isFile) return null
                metadata.put(JSONArray().put(page).put(file.absolutePath).put(file.length()).put(file.lastModified()))
            }
            return digest(metadata.toString().toByteArray(Charsets.UTF_8))
        }
        private fun precedingSourceMetadata(book: Book, chapter: Int): String? {
            var result = ""
            for (index in 0 until chapter) {
                val source = sourceMetadata(book, index) ?: return null
                result = digest("$result:$source".toByteArray(Charsets.UTF_8))
            }
            return result
        }
        internal fun key(config: AiSpeechConfig, text: String, roleInstructions: String = ""): String = digest(
            (config.protocol.name + "\n" + config.endpoint + "\n").toByteArray(Charsets.UTF_8) + config.request(text, roleInstructions))
    }
}
