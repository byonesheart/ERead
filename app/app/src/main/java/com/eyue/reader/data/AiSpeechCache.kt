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

data class AiSpeechChapterCacheStatus(val currentChapters: Set<Int> = emptySet(), val otherChapters: Set<Int> = emptySet())

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
    /** Inspect the current text and actual audio, so partial, edited or cleared chapters never get a badge. */
    suspend fun completedChapters(book: Book, config: AiSpeechConfig): Set<Int> = chapterCacheStatus(book, config).currentChapters

    /** Keep completion identities for old configurations without accepting their audio for a new request. */
    suspend fun chapterCacheStatus(book: Book, config: AiSpeechConfig?,
        commonRoles: List<BookVoiceRole>? = null): AiSpeechChapterCacheStatus = withContext(Dispatchers.IO) {
        if (book.mode != LibraryMode.NOVEL || book.cloudOnly || !directory(book.id).isDirectory) return@withContext AiSpeechChapterCacheStatus()
        val expectedGeneration = generation(book.id)
        val common = commonRoles ?: LibraryRepository(context).load().commonVoiceRoles
        val current = mutableSetOf<Int>()
        val other = mutableSetOf<Int>()
        val sourceDigests = mutableMapOf<Int, String>()
        val indices = book.chapters.indices.toSet()
        val active = currentCoroutineContext()
        val seeds = if (config?.rolePromptEnabled == true) aiSpeechChapterSeeds(book, config, indices, common) else emptyMap()
        if (config != null) for (index in indices) {
            active.ensureActive()
            try {
                val keys = aiSpeechPlannedChapter(book, config, index, common, seeds[index]) { active.ensureActive() }
                    .map { key(config, it.chunk.text, it.roleInstructions) }.toList()
                if (recordPlannedChapterCompletion(book, config, index, common, seeds[index], keys, expectedGeneration)) current += index
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
              catch (_: IOException) { }
        }
        val manifests = synchronized(lock) { directory(book.id).listFiles()?.filter { it.name.startsWith("chapter-") && it.extension == "json" }.orEmpty() }
        var roleSeeds: Map<Int, String?>? = if (config?.rolePromptEnabled == true) seeds else null
        for (file in manifests) {
            active.ensureActive()
            val row = synchronized(lock) { runCatching { JSONObject(AtomicFile(file).readFully().toString(Charsets.UTF_8)) }.getOrNull() } ?: continue
            val index = row.optInt("chapter", -1)
            if (index !in indices || row.optInt("version", -1) != book.contentVersion ||
                row.optString("configuration") == config?.let(::configurationIdentity)) continue
            val roleEnabled = row.optBoolean("rolePromptEnabled")
            if (row.optString("roles") != roleScope(book, roleEnabled, common)) continue
            try {
                if (row.optString("source") != sourceDigests.getOrPut(index) { sourceDigest(book, index) }) continue
                if (roleEnabled) {
                    if (roleSeeds == null) roleSeeds = aiSpeechRoleChapterSeeds(book, indices, common)
                    if (row.optString("seed") != roleSeeds?.get(index).orEmpty()) continue
                }
                val extension = row.optString("extension").takeIf { it in setOf("wav", "mp3") } ?: continue
                val keys = row.optJSONArray("keys") ?: continue
                if (keys.length() > 0 && (0 until keys.length()).all { offset ->
                    val key = keys.getString(offset)
                    key.matches(Regex("[0-9a-f]{64}")) && find(book.id, key, extension) != null
                }) other += index
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
              catch (_: IOException) { }
        }
        if (generation(book.id) == expectedGeneration) AiSpeechChapterCacheStatus(current, other - current) else AiSpeechChapterCacheStatus()
    }

    /** Live narration calls this after the final chunk. Partial chapters can never create a completion record. */
    suspend fun recordChapterCompletion(book: Book, config: AiSpeechConfig, chapterIndex: Int,
        commonRoles: List<BookVoiceRole>? = null): Boolean = withContext(Dispatchers.IO) {
        if (book.mode != LibraryMode.NOVEL || book.cloudOnly || chapterIndex !in book.chapters.indices) return@withContext false
        val expectedGeneration = generation(book.id)
        val common = commonRoles ?: if (config.rolePromptEnabled) LibraryRepository(context).load().commonVoiceRoles else emptyList()
        val seed = aiSpeechChapterSeeds(book, config, setOf(chapterIndex), common)[chapterIndex]
        val active = currentCoroutineContext()
        val keys = aiSpeechPlannedChapter(book, config, chapterIndex, common, seed) { active.ensureActive() }
            .map { key(config, it.chunk.text, it.roleInstructions) }.toList()
        recordPlannedChapterCompletion(book, config, chapterIndex, common, seed, keys, expectedGeneration)
    }

    internal fun recordPlannedChapterCompletion(book: Book, config: AiSpeechConfig, chapter: Int,
        commonRoles: List<BookVoiceRole>, seedRoleId: String?, keys: List<String>, expectedGeneration: Long): Boolean {
        if (keys.isEmpty()) return false
        val row = JSONObject().put("chapter", chapter).put("version", book.contentVersion)
            .put("configuration", configurationIdentity(config)).put("source", sourceDigest(book, chapter))
            .put("rolePromptEnabled", config.rolePromptEnabled).put("roles", rolesDigest(book, config, commonRoles))
            .put("seed", seedRoleId.orEmpty()).put("extension", if (config.protocol == SpeechApiProtocol.MIMO) "wav" else "mp3")
            .put("keys", JSONArray(keys))
        return synchronized(lock) {
            if (generation(book.id) != expectedGeneration || keys.any { find(book.id, it, row.getString("extension")) == null }) return@synchronized false
            val directory = directory(book.id)
            if (!directory.isDirectory) return@synchronized false
            val identity = digest((row.getString("configuration") + ":" + book.contentVersion + ":" + row.getString("roles") + ":" + chapter).toByteArray(Charsets.UTF_8))
            val file = AtomicFile(File(directory, "chapter-$identity.json"))
            val stream = file.startWrite()
            try { stream.write(row.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
            catch (error: Throwable) { file.failWrite(stream); throw error }
            true
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
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        internal fun rolesDigest(book: Book, config: AiSpeechConfig, commonRoles: List<BookVoiceRole> = emptyList()): String =
            roleScope(book, config.rolePromptEnabled, commonRoles)
        private fun roleScope(book: Book, enabled: Boolean, commonRoles: List<BookVoiceRole>): String = if (!enabled) "disabled" else
            digest(JSONObject().put("book", JSONArray(book.voiceRoles.map { it.toJson() }))
                .put("common", JSONArray(commonRoles.map { it.toJson() })).toString().toByteArray(Charsets.UTF_8))
        internal fun configurationIdentity(config: AiSpeechConfig): String = digest((key(config, "") + ":" + config.rolePromptEnabled).toByteArray(Charsets.UTF_8))
        private fun sourceDigest(book: Book, chapter: Int): String {
            val digest = MessageDigest.getInstance("SHA-256")
            aiSpeechChapterSegments(book, chapter).forEach { segment ->
                digest.update("${segment.page}:${segment.offset}:${segment.text.length}:".toByteArray(Charsets.UTF_8))
                digest.update(segment.text.toByteArray(Charsets.UTF_8))
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        internal fun key(config: AiSpeechConfig, text: String, roleInstructions: String = ""): String = digest(
            (config.protocol.name + "\n" + config.endpoint + "\n").toByteArray(Charsets.UTF_8) + config.request(text, roleInstructions))
    }
}
