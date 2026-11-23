package com.eyue.reader.data.cloud

import java.io.File
import java.io.IOException
import java.util.UUID
import java.security.MessageDigest

/** COS's atomic create-only operation commits an immutable metadata generation.
 * Competing writers target the same next slot: only one succeeds; old slots are never reused.
 * Descending slot names let a bounded listing find the newest generation without a full scan.
 */
internal class CosCloudTransport(credentials: CloudCredentials, private val checkActive: () -> Unit = {},
    private val raw: S3CloudTransport = S3CloudTransport(CloudCredentials(credentials.account, credentials.password,
        credentials.serverUrl, requireNotNull(credentials.s3).let {
            it.copy(prefix = listOf(it.prefix.trim('/'), "cos-v1").filter(String::isNotEmpty).joinToString("/"))
        }), checkActive)) : CloudTransport {
    private var verified = false

    private fun journaled(path: String) = path == "catalog.json" || path.endsWith("/manifest.json") || path.startsWith(".connection-test-")
    private fun directory(path: String): String {
        CloudArchive.safeName(path)
        val parent = path.substringBeforeLast('/', "")
        return (if (parent.isEmpty()) "" else "$parent/") + ".cos-records-${path.substringAfterLast('/')}/"
    }
    private fun slot(path: String, generation: Long) = directory(path) + (Long.MAX_VALUE - generation).toString().padStart(19, '0')
    private fun generation(key: String): Long {
        val name = key.substringAfterLast('/')
        require(name.length == 19 && name.all(Char::isDigit)) { "COS 备份版本索引损坏" }
        return (Long.MAX_VALUE - requireNotNull(name.toLongOrNull())).also { require(it > 0) { "COS 备份版本无效" } }
    }

    override fun ensureCollection(path: String) = raw.ensureCollection(path)
    override fun collections(path: String) = raw.collections(path)
    override fun cancel() = raw.cancel()
    override fun upload(path: String, file: File, offset: Long, bytes: Long, progress: (Long) -> Unit) =
        raw.upload(path, file, offset, bytes, progress)
    override fun download(path: String, file: File, append: Boolean, maximum: Long, progress: (Long) -> Unit) =
        raw.download(path, file, append, maximum, progress)
    override fun deleteCollection(path: String) = raw.deleteCollection(path)

    override fun versionedMetadata(path: String): CloudVersionedMetadata? {
        if (!journaled(path)) return raw.versionedMetadata(path)
        val key = raw.objectKeys(directory(path), 1).singleOrNull() ?: return null
        val version = generation(key)
        val bytes = raw.metadata(key) ?: throw IOException("COS 备份版本已被删除，请检查存储桶生命周期规则")
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return CloudVersionedMetadata(bytes, "\"cos-v1-$version-$digest\"")
    }

    override fun metadata(path: String, maximum: Int): ByteArray? {
        if (!journaled(path)) return raw.metadata(path, maximum)
        return versionedMetadata(path)?.bytes?.also { require(it.size <= maximum) { "COS 元数据过大" } }
    }

    override fun readMetadata(path: String, etag: String?): CloudMetadataResponse {
        if (!journaled(path)) return raw.readMetadata(path, etag)
        val current = versionedMetadata(path) ?: return CloudMetadataResponse(null)
        return if (current.etag == etag) CloudMetadataResponse(null, current.etag, true)
            else CloudMetadataResponse(current.bytes, current.etag)
    }

    override fun putVersionedMetadata(path: String, bytes: ByteArray, etag: String?): Boolean {
        require(journaled(path)) { "COS 条件更新仅用于备份记录" }
        require(bytes.size <= CLOUD_METADATA_LIMIT) { "COS 元数据过大" }
        verifyWrites()
        val current = versionedMetadata(path)
        if (current?.etag != etag) return false
        val previous = current?.etag?.removePrefix("\"cos-v1-")?.substringBefore('-')?.toLong() ?: 0L
        require(previous < Long.MAX_VALUE - 1) { "COS 版本数量超过限制" }
        // A stale list result can only cause a conflict, never overwrite a committed slot.
        return raw.createCosObject(slot(path, previous + 1), bytes)
    }

    override fun putMetadata(path: String, bytes: ByteArray) {
        if (!journaled(path)) { raw.putMetadata(path, bytes); return }
        repeat(8) {
            checkActive()
            if (putVersionedMetadata(path, bytes, versionedMetadata(path)?.etag)) return
        }
        throw IOException("其他设备正在更新 COS 备份，请稍后重试")
    }

    private fun verifyWrites() {
        if (!verified) { raw.testCosCreateOnly(); verified = true }
    }

    fun testWriteAccess() {
        verifyWrites()
        val path = ".connection-test-${UUID.randomUUID()}"
        var failure: Throwable? = null
        try {
            require(putVersionedMetadata(path, "first".toByteArray(), null))
            val first = requireNotNull(versionedMetadata(path))
            require(putVersionedMetadata(path, "second".toByteArray(), first.etag)) { "COS 版本更新失败" }
            require(!putVersionedMetadata(path, "stale".toByteArray(), first.etag)) { "COS 旧版本保护未生效" }
            require(metadata(path)?.decodeToString() == "second") { "COS 版本读取失败" }
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            try { raw.objectKeys(directory(path)).forEach(raw::deleteObject) } catch (cleanup: Throwable) {
                if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
            }
        }
    }
}
