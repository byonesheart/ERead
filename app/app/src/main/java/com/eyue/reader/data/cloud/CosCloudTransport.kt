package com.eyue.reader.data.cloud

import java.io.File
import java.io.IOException
import java.util.UUID
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** COS's atomic create-only operation commits an immutable metadata generation.
 * Competing writers target the same next slot: only one succeeds; old slots are never reused.
 * Descending slot names let a bounded listing find the newest generation without a full scan.
 */
internal class CosCloudTransport(credentials: CloudCredentials, private val checkActive: () -> Unit = {},
    private val raw: S3CloudTransport = S3CloudTransport(CloudCredentials(credentials.account, credentials.password,
        credentials.serverUrl, requireNotNull(credentials.s3).let {
            it.copy(prefix = listOf(it.prefix.trim('/'), "cos-v1").filter(String::isNotEmpty).joinToString("/"))
        }), checkActive, uncachedReads = true),
    private val pause: (Long) -> Unit = { Thread.sleep(it) }) : CloudTransport {
    private var verified = false
    private val knownVersions = ConcurrentHashMap<String, Long>()
    private val writtenHashes = ConcurrentHashMap<String, Pair<Long, String>>()

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun remember(path: String, version: Long) { knownVersions.merge(path, version) { a, b -> maxOf(a, b) } }
    private fun rememberTag(path: String, tag: String?) {
        val version = tag?.let { Regex("\"cos-v1-([0-9]+)-[0-9a-f]{64}\"").matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() }
        if (version != null && version in 1 until Long.MAX_VALUE) remember(path, version)
    }

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
    override fun deleteCollection(path: String) {
        raw.deleteCollection(path)
        knownVersions.keys.removeAll { it.startsWith(path) }
        writtenHashes.keys.removeAll { it.startsWith(path) }
    }

    private fun readCommitted(path: String, version: Long): ByteArray {
        var observed: ByteArray? = null
        repeat(4) { attempt ->
            checkActive()
            observed = raw.metadata(slot(path, version))
            val expected = writtenHashes[path]?.takeIf { it.first == version }?.second
            if (observed != null && (expected == null || digest(observed) == expected)) return observed
            if (attempt < 3) repeat(3 shl attempt) { checkActive(); pause(40) }
        }
        val result = if (observed == null) "对象暂不可读" else "内容摘要不一致"
        throw IOException("COS 第 $version 版读取确认失败：$result；已保留旧记录，请稍后重试或检查代理缓存")
    }

    override fun versionedMetadata(path: String): CloudVersionedMetadata? {
        if (!journaled(path)) return raw.versionedMetadata(path)
        val listed = raw.objectKeys(directory(path), 1).singleOrNull()?.let(::generation) ?: 0L
        var version = maxOf(listed, knownVersions[path] ?: 0L)
        var bytes = if (version == 0L) raw.metadata(slot(path, 1))?.also { version = 1L } ?: return null
            else readCommitted(path, version)
        // A cached/lagging listing must not hide a generation we wrote or another device committed.
        repeat(64) {
            remember(path, version)
            require(version < Long.MAX_VALUE - 1) { "COS 版本数量超过限制" }
            val next = raw.metadata(slot(path, version + 1))
                ?: return CloudVersionedMetadata(bytes, "\"cos-v1-$version-${digest(bytes)}\"")
            version++
            bytes = next
        }
        throw IOException("COS 目录版本落后较多，请稍后重试；未覆盖已有记录")
    }

    override fun metadata(path: String, maximum: Int): ByteArray? {
        if (!journaled(path)) return raw.metadata(path, maximum)
        return versionedMetadata(path)?.bytes?.also { require(it.size <= maximum) { "COS 元数据过大" } }
    }

    override fun readMetadata(path: String, etag: String?): CloudMetadataResponse {
        if (!journaled(path)) return raw.readMetadata(path, etag)
        rememberTag(path, etag)
        val current = versionedMetadata(path) ?: return CloudMetadataResponse(null)
        return if (current.etag == etag) CloudMetadataResponse(null, current.etag, true)
            else CloudMetadataResponse(current.bytes, current.etag)
    }

    override fun putVersionedMetadata(path: String, bytes: ByteArray, etag: String?): Boolean {
        require(journaled(path)) { "COS 条件更新仅用于备份记录" }
        require(bytes.size <= CLOUD_METADATA_LIMIT) { "COS 元数据过大" }
        verifyWrites()
        rememberTag(path, etag)
        val current = versionedMetadata(path)
        if (current?.etag != etag) return false
        val previous = current?.etag?.removePrefix("\"cos-v1-")?.substringBefore('-')?.toLong() ?: 0L
        require(previous < Long.MAX_VALUE - 1) { "COS 版本数量超过限制" }
        // A stale list result can only cause a conflict, never overwrite a committed slot.
        val committed = raw.createCosObject(slot(path, previous + 1), bytes)
        if (committed) {
            remember(path, previous + 1)
            val expected = (previous + 1) to digest(bytes)
            writtenHashes.compute(path) { _, old -> if (old == null || old.first <= expected.first) expected else old }
            // Confirm visibility before a new session is allowed to discover this commit.
            readCommitted(path, previous + 1)
        }
        return committed
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
            val latest = versionedMetadata(path)
            require(latest?.bytes?.decodeToString() == "second") {
                "COS 测试版本读取不一致（预期第 2 版，实际 ${latest?.etag?.removePrefix("\"cos-v1-")?.substringBefore('-') ?: "未读到版本"}）；请检查代理缓存后重试"
            }
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            try { listOf(1L, 2L).forEach { raw.deleteObject(slot(path, it)) } } catch (cleanup: Throwable) {
                if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
            }
        }
    }
}
