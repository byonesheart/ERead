package com.eyue.reader.data.cloud

import android.content.Context
import android.util.AtomicFile
import com.eyue.reader.data.Book
import com.eyue.reader.data.LibraryMode
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

internal data class CloudBookSummary(
    val id: String, val title: String, val mode: LibraryMode, val fileName: String?,
    val fileBytes: Long?, val revision: String, val backupAt: Long
) {
    fun matches(book: Book): Boolean = id == book.id ||
        mode == book.mode && title.trim().equals(book.title.trim(), ignoreCase = true) ||
        !fileName.isNullOrBlank() && fileName == book.sourceFileName

    fun sameIdentity(other: CloudBookSummary): Boolean = copy(backupAt = other.backupAt) == other

    fun write(): JSONObject = JSONObject().put("id", id).put("title", title).put("mode", mode.name)
        .put("fileName", fileName).put("fileBytes", fileBytes).put("revision", revision).put("backupAt", backupAt)

    companion object {
        fun from(info: CloudBookInfo) = with(info.book) {
            CloudBookSummary(id, title, mode, sourceFileName, sourceFileBytes, info.revision, info.backupAt)
        }

        fun read(json: JSONObject) = CloudBookSummary(CloudArchive.safeId(json.getString("id")),
            json.getString("title"), LibraryMode.valueOf(json.getString("mode")),
            json.optString("fileName").takeIf { it.isNotBlank() }, json.optLong("fileBytes", -1).takeIf { it >= 0 },
            CloudArchive.safeId(json.getString("revision")), json.getLong("backupAt"))
    }
}

/** Older E阅 versions can still read and update the original version-1 ID list. */
internal data class CloudCatalog(val ids: List<String>, val summaries: Map<String, CloudBookSummary> = emptyMap()) {
    fun write(): ByteArray = JSONObject().put("version", 1).put("bookIds", JSONArray(ids.sorted()))
        .put("summaries", JSONArray(ids.sorted().mapNotNull { summaries[it]?.write() })).toString().toByteArray()

    companion object {
        fun read(bytes: ByteArray): CloudCatalog {
            require(bytes.size <= CLOUD_METADATA_LIMIT)
            val json = JSONObject(bytes.decodeToString())
            require(json.getInt("version") == 1) { "云端书籍索引版本不受支持" }
            val ids = json.getJSONArray("bookIds")
            require(ids.length() <= 10_000) { "云端书籍数量超过限制" }
            val entries = (0 until ids.length()).map { CloudArchive.safeId(ids.getString(it)) }.distinct()
            val allowed = entries.toHashSet()
            val summaries = json.optJSONArray("summaries")?.takeIf { it.length() <= 10_000 }
            return CloudCatalog(entries, buildMap {
                if (summaries != null) for (index in 0 until summaries.length()) {
                    val row = runCatching { CloudBookSummary.read(summaries.getJSONObject(index)) }.getOrNull()
                    if (row != null && row.id in allowed) put(row.id, row)
                }
            })
        }
    }
}

/** A cached response is reused only after validation by the server for this account and resource. */
internal class CloudMetadataCache(context: Context, identity: String) {
    private val root = File(context.cacheDir, "webdav-metadata/" + MessageDigest.getInstance("SHA-256")
        .digest(identity.toByteArray()).joinToString("") { "%02x".format(it) })

    fun hasBookRecords(): Boolean = synchronized(disk) {
        root.listFiles().orEmpty().any { it.name.startsWith("book-") && it.extension == "data" }
    }

    fun <T> read(transport: CloudTransport, path: String, key: String, verifiedTag: String? = null,
        parse: (ByteArray) -> T): T? {
        val namespace = if (path == "catalog.json") "catalog" else "book"
        val file = AtomicFile(File(root, "$namespace-${CloudArchive.safeId(key)}.data"))
        val cached = synchronized(disk) {
            runCatching {
                DataInputStream(file.openRead().buffered()).use { input ->
                    val etag = requireNotNull(cloudReadETag(input.readUTF()))
                    val size = input.readInt()
                    require(size in 1..MAX_ENTRY)
                    CloudVersionedMetadata(ByteArray(size).also(input::readFully), etag)
                }
            }.getOrNull()
        }
        val response = if (verifiedTag != null && cached?.etag == verifiedTag) CloudMetadataResponse(null, verifiedTag, unchanged = true)
            else transport.readMetadata(path, cached?.etag)
        if (response.unchanged && cached != null) {
            // A damaged local copy must never make a valid remote library unreadable.
            try { return parse(cached.bytes) } catch (_: IllegalArgumentException) { }
            catch (_: org.json.JSONException) { }
        }
        val fresh = if (response.unchanged) transport.readMetadata(path, null) else response
        val bytes = fresh.bytes
        if (bytes == null) {
            synchronized(disk) { file.delete() }
            return null
        }
        return parse(bytes).also {
            val etag = cloudReadETag(fresh.etag)
            synchronized(disk) {
                if (etag == null || bytes.size !in 1..MAX_ENTRY) file.delete()
                else runCatching {
                    val output = file.startWrite()
                    try {
                        val data = DataOutputStream(output)
                        data.writeUTF(etag)
                        data.writeInt(bytes.size)
                        data.write(bytes)
                        file.finishWrite(output)
                    } catch (error: Throwable) { file.failWrite(output); throw error }
                }
            }
        }
    }

    fun prune() = synchronized(disk) {
        val files = root.listFiles().orEmpty().filter { it.isFile && it.extension == "data" }
        var kept = 0L
        for (file in files.sortedByDescending(File::lastModified)) {
            kept += file.length()
            if (kept > MAX_CACHE) AtomicFile(file).delete()
        }
    }

    companion object {
        private val disk = Any()
        private const val MAX_ENTRY = 8 * 1024 * 1024
        private const val MAX_CACHE = 64L * 1024 * 1024
    }
}
