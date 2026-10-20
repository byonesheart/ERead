package com.eyue.reader.data.cloud

import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import com.eyue.reader.data.documentFile
import com.eyue.reader.data.Chapter
import com.eyue.reader.data.LibraryRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest

internal data class CloudFileVersion(val path: String, val device: Long, val inode: Long, val bytes: Long,
    val modifiedSeconds: Long, val modifiedNanos: Long, val changedSeconds: Long, val changedNanos: Long) {
    fun json(): JSONObject = JSONObject().put("path", path).put("device", device).put("inode", inode).put("bytes", bytes)
        .put("mtime", modifiedSeconds).put("mtimeNs", modifiedNanos).put("ctime", changedSeconds).put("ctimeNs", changedNanos)
    companion object {
        fun read(json: JSONObject) = CloudFileVersion(json.getString("path"), json.getLong("device"), json.getLong("inode"),
            json.getLong("bytes"), json.getLong("mtime"), json.getLong("mtimeNs"), json.getLong("ctime"), json.getLong("ctimeNs"))
    }
}

/** Only app-private native files have trusted change timestamps; document providers and emulated storage are re-read. */
internal fun cloudFileVersion(context: Context, source: String): CloudFileVersion? {
    if (Build.VERSION.SDK_INT < 27) return null
    return try {
        val file = documentFile(source)?.canonicalFile ?: return null
        val privateRoots = listOf(context.filesDir.canonicalFile, context.cacheDir.canonicalFile)
        if (privateRoots.none { file.toPath().startsWith(it.toPath()) }) return null
        val stat = Os.stat(file.path)
        if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_dev == 0L || stat.st_ino == 0L || stat.st_size < 0 ||
            stat.st_ctim.tv_nsec !in 1L..999_999_999L || stat.st_mtim.tv_nsec !in 0L..999_999_999L) return null
        CloudFileVersion(file.path, stat.st_dev, stat.st_ino, stat.st_size, stat.st_mtim.tv_sec, stat.st_mtim.tv_nsec,
            stat.st_ctim.tv_sec, stat.st_ctim.tv_nsec)
    } catch (_: Exception) { null }
}

/** Local optimization only: every cache hit needs a complete reliable version match, never size/mtime alone. */
internal class CloudResourceHashCache(private val file: File, private val version: (String) -> CloudFileVersion?) {
    constructor(context: Context, bookId: String) : this(
        File(context.filesDir, "books/${CloudArchive.safeId(bookId)}/.cloud-resource-hashes.json"),
        { source -> cloudFileVersion(context, source) })

    private data class Record(val version: CloudFileVersion, val sha256: String)
    private data class PreparedRecord(val structure: String, val versions: List<CloudFileVersion>, val chapter: Chapter)
    private val records = mutableMapOf<String, Record>()
    private val retained = mutableSetOf<String>()
    private val prepared = mutableMapOf<String, PreparedRecord>()
    private val retainedPrepared = mutableSetOf<String>()
    private var identityKey: String? = null
    private var identityVersions: List<CloudFileVersion> = emptyList()
    private var identityValue: String? = null
    private val atomic = AtomicFile(file)
    private var chapterRepository: LibraryRepository? = null
    private var preparedJson: JSONArray? = null

    init {
        try {
            if (file.length() in 1L..CLOUD_METADATA_LIMIT.toLong()) {
                val json = JSONObject(atomic.readFully().toString(Charsets.UTF_8))
                preparedJson = json.optJSONArray("prepared")
                val assets = json.getJSONArray("assets")
                for (index in 0 until assets.length()) {
                    val item = assets.getJSONObject(index)
                    val stamp = CloudFileVersion.read(item.getJSONObject("version"))
                    val hash = item.getString("sha256")
                    require(hash.matches(Regex("[a-f0-9]{64}")))
                    records[stamp.path] = Record(stamp, hash)
                }
                json.optJSONObject("identity")?.let { identity ->
                    identityKey = identity.getString("key")
                    identityValue = identity.getString("value")
                    require(identityValue!!.matches(Regex("(?:text|pages)-v1:[a-f0-9]{64}")))
                    val versions = identity.getJSONArray("versions")
                    identityVersions = (0 until versions.length()).map { CloudFileVersion.read(versions.getJSONObject(it)) }
                }
            }
        } catch (_: Exception) {
            records.clear()
            identityKey = null
            identityValue = null
            identityVersions = emptyList()
            preparedJson = null
        }
    }

    fun snapshot(paths: List<String>): List<CloudFileVersion>? {
        val versions = paths.map(version)
        return versions.filterNotNull().takeIf { it.size == paths.size }
    }

    fun preparedChapter(id: String, structure: String, repository: LibraryRepository, check: () -> Unit): Chapter? {
        retainedPrepared += id
        // Defer parsing chapter metadata until the caller supplies its repository.
        if (chapterRepository == null) {
            chapterRepository = repository
            try {
                val rows = preparedJson
                preparedJson = null
                if (rows != null) for (index in 0 until rows.length()) {
                    val row = rows.getJSONObject(index)
                    val versions = row.getJSONArray("versions")
                    require(versions.length() in 3..4)
                    prepared[row.getString("id")] = PreparedRecord(row.getString("structure"),
                        (0 until versions.length()).map { CloudFileVersion.read(versions.getJSONObject(it)) },
                        repository.readChapter(row.getJSONObject("chapter")))
                }
            } catch (_: Exception) { prepared.clear() }
        }
        check()
        val record = prepared[id]?.takeIf { it.structure == structure } ?: return null
        val paths = record.versions.map { it.path }
        if (snapshot(paths) != record.versions) return null
        check()
        return record.chapter.takeIf { snapshot(paths) == record.versions }
    }

    fun rememberPreparedChapter(id: String, structure: String, versions: List<CloudFileVersion>, chapter: Chapter) {
        prepared[id] = PreparedRecord(structure, versions, chapter)
    }

    fun inspect(asset: CloudAsset, maximum: Long, check: () -> Unit): CloudAssetRecord {
        check()
        val before = asset.sourcePath?.let(version)
        if (before != null) {
            retained += before.path
            val cached = records[before.path]
            if (cached != null && cached.version == before && version(requireNotNull(asset.sourcePath)) == before) {
                require(before.bytes <= maximum) { "备份文件超过声明大小或限制" }
                check()
                return CloudAssetRecord(asset.name, before.bytes, cached.sha256)
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val discard = object : OutputStream() {
            override fun write(value: Int) {}
            override fun write(bytes: ByteArray, offset: Int, length: Int) {}
        }
        val bytes = asset.open().use { CloudArchive.copy(it, discard, maximum, check, digest) }
        val hash = CloudArchive.hex(digest.digest())
        if (before != null) {
            if (bytes == before.bytes && asset.sourcePath?.let(version) == before) records[before.path] = Record(before, hash)
            else records.remove(before.path)
        }
        return CloudAssetRecord(asset.name, bytes, hash)
    }

    fun identity(key: String, paths: List<String>, check: () -> Unit, compute: () -> String?): String? {
        check()
        val before = paths.map { version(it) }
        if (before.all { it != null } && identityKey == key && before == identityVersions && paths.map(version) == before) {
            check()
            return identityValue
        }
        val result = compute()
        if (result != null && before.all { it != null } && paths.map(version) == before) {
            identityKey = key
            identityVersions = before.filterNotNull()
            identityValue = result
        } else { identityKey = null; identityVersions = emptyList(); identityValue = null }
        return result
    }

    fun save() {
        records.keys.retainAll(retained)
        prepared.keys.retainAll(retainedPrepared)
        val json = JSONObject().put("assets", JSONArray(records.values.map {
            JSONObject().put("version", it.version.json()).put("sha256", it.sha256)
        }))
        identityValue?.let { value -> json.put("identity", JSONObject().put("key", identityKey).put("value", value)
            .put("versions", JSONArray(identityVersions.map { it.json() }))) }
        chapterRepository?.let { repository -> json.put("prepared", JSONArray(prepared.map { (id, record) ->
            JSONObject().put("id", id).put("structure", record.structure)
                .put("versions", JSONArray(record.versions.map { it.json() })).put("chapter", repository.writeChapter(record.chapter))
        })) }
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > CLOUD_METADATA_LIMIT) { atomic.delete(); return }
        try {
            val output = atomic.startWrite()
            try { output.write(bytes); atomic.finishWrite(output) }
            catch (error: Exception) { atomic.failWrite(output); throw error }
        } catch (_: Exception) { /* A failed optional cache write must not prevent a backup. */ }
    }
}
