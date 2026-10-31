package com.eyue.reader.data.cloud

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal const val CLOUD_METADATA_LIMIT = 64 * 1024 * 1024
internal const val CLOUD_ARCHIVE_LIMIT = 32L * 1024 * 1024 * 1024
internal const val CLOUD_PART_BYTES = 1024L * 1024
internal const val CLOUD_LEGACY_PART_BYTES = 128L * 1024 * 1024
internal const val CLOUD_PART_LIMIT = 32768
internal const val CLOUD_ENTRY_LIMIT = 200_000

internal data class CloudAsset(val name: String, val sourcePath: String? = null, val open: () -> InputStream)
internal data class CloudAssetRecord(val name: String, val bytes: Long, val sha256: String)

internal object CloudArchive {
    private val discard = object : OutputStream() {
        override fun write(value: Int) {}
        override fun write(bytes: ByteArray, offset: Int, length: Int) {}
    }
    fun safeName(name: String): String {
        require(name.isNotEmpty() && name.length <= 256 && !name.startsWith('/') && '\\' !in name && ':' !in name && '\u0000' !in name) { "备份中包含无效路径" }
        require(name.split('/').all { it.isNotEmpty() && it != "." && it != ".." && it.matches(Regex("[A-Za-z0-9._-]+")) }) { "备份路径越界" }
        return name
    }

    fun safeId(id: String): String {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "备份图书标识无效" }
        return id
    }

    fun parts(bytes: Long, revision: String, partBytes: Long = CLOUD_PART_BYTES): List<CloudArchivePart> {
        require(bytes in 1..CLOUD_ARCHIVE_LIMIT)
        require(partBytes == CLOUD_PART_BYTES || partBytes == 4L * 1024 * 1024)
        safeId(revision)
        var remaining = bytes
        val result = mutableListOf<CloudArchivePart>()
        while (remaining > 0) {
            val length = minOf(remaining, partBytes)
            result += CloudArchivePart("$revision-${result.size.toString().padStart(4, '0')}.bin", length)
            remaining -= length
        }
        return result
    }

    fun write(destination: File, assets: List<CloudAsset>, metadata: (List<CloudAssetRecord>) -> ByteArray, checkActive: () -> Unit = {}): List<CloudAssetRecord> {
        require(assets.size < CLOUD_ENTRY_LIMIT)
        val names = HashSet<String>()
        var expanded = 0L
        val records = mutableListOf<CloudAssetRecord>()
        try {
            ZipOutputStream(destination.outputStream().buffered()).use { zip ->
                for (asset in assets) {
                    checkActive()
                    safeName(asset.name)
                    require(asset.name.startsWith("assets/") && names.add(asset.name)) { "备份文件路径重复" }
                    zip.putNextEntry(ZipEntry(asset.name))
                    val digest = MessageDigest.getInstance("SHA-256")
                    val size = asset.open().use { input -> copy(input, zip, CLOUD_ARCHIVE_LIMIT - expanded, checkActive, digest) }
                    expanded += size
                    zip.closeEntry()
                    records += CloudAssetRecord(asset.name, size, hex(digest.digest()))
                }
                val bytes = metadata(records)
                require(bytes.size <= CLOUD_METADATA_LIMIT) { "图书排版与注释记录超过 64 MB，无法备份；书籍文件大小不受此限制" }
                zip.putNextEntry(ZipEntry("metadata.json"))
                zip.write(bytes)
                zip.closeEntry()
            }
            require(destination.length() <= CLOUD_ARCHIVE_LIMIT) { "备份文件过大" }
            return records
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }

    fun inspect(assets: List<CloudAsset>, checkActive: () -> Unit = {}, cache: CloudResourceHashCache? = null): List<CloudAssetRecord> {
        require(assets.size < CLOUD_ENTRY_LIMIT)
        var total = 0L
        val seen = HashSet<String>()
        return assets.map { asset ->
            require(safeName(asset.name).startsWith("assets/") && seen.add(asset.name))
            checkActive()
            val record = cache?.inspect(asset, CLOUD_ARCHIVE_LIMIT - total, checkActive) ?: run {
                val digest = MessageDigest.getInstance("SHA-256")
                val bytes = asset.open().use { copy(it, discard, CLOUD_ARCHIVE_LIMIT - total, checkActive, digest) }
                CloudAssetRecord(asset.name, bytes, hex(digest.digest()))
            }
            total += record.bytes
            record
        }
    }

    fun extract(source: File, destination: File, records: List<CloudAssetRecord>, checkActive: () -> Unit = {}): ByteArray {
        require(!destination.exists()) { "还原暂存目录已存在" }
        require(records.size < CLOUD_ENTRY_LIMIT && records.map { it.name }.toSet().size == records.size)
        val expected = records.associateBy { safeName(it.name) }
        require(expected.values.all { it.name.startsWith("assets/") && it.bytes >= 0 && it.sha256.matches(Regex("[a-f0-9]{64}")) }) { "备份文件清单损坏" }
        require(expected.values.fold(0L) { sum, item -> Math.addExact(sum, item.bytes) } <= CLOUD_ARCHIVE_LIMIT)
        check(destination.mkdirs() || destination.isDirectory)
        val root = destination.canonicalFile
        try {
            ZipFile(source).use { zip ->
                require(zip.size() == records.size + 1) { "备份文件清单不匹配" }
                val seen = HashSet<String>()
                var metadata: ByteArray? = null
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    checkActive()
                    val entry = entries.nextElement()
                    val name = safeName(entry.name)
                    require(seen.add(name) && !entry.isDirectory) { "备份文件路径重复" }
                    if (name == "metadata.json") {
                        metadata = zip.getInputStream(entry).use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            copy(input, output, CLOUD_METADATA_LIMIT.toLong(), checkActive)
                            output.toByteArray()
                        }
                    } else {
                        val record = requireNotNull(expected[name]) { "备份包含未登记文件" }
                        require(entry.size == record.bytes) { "备份文件大小不匹配" }
                        val file = File(root, name).canonicalFile
                        require(file.path.startsWith(root.path + File.separator)) { "备份路径越界" }
                        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
                        val digest = MessageDigest.getInstance("SHA-256")
                        val size = zip.getInputStream(entry).use { input -> file.outputStream().buffered().use { output -> copy(input, output, record.bytes, checkActive, digest) } }
                        require(size == record.bytes && hex(digest.digest()) == record.sha256) { "备份文件校验失败" }
                    }
                }
                require(seen == expected.keys + "metadata.json") { "备份缺少文件" }
                return requireNotNull(metadata) { "备份缺少图书信息" }
            }
        } catch (error: Throwable) {
            check(destination.canonicalFile == root)
            destination.deleteRecursively()
            throw error
        }
    }

    fun hash(file: File, checkActive: () -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { copy(it, discard, CLOUD_ARCHIVE_LIMIT, checkActive, digest) }
        return hex(digest.digest())
    }

    fun copy(input: InputStream, output: OutputStream, maximum: Long, checkActive: () -> Unit = {}, digest: MessageDigest? = null, progress: (Long) -> Unit = {}): Long {
        require(maximum >= 0)
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            require(count.toLong() <= maximum - total) { "备份文件超过声明大小或限制" }
            output.write(buffer, 0, count)
            digest?.update(buffer, 0, count)
            total += count
            progress(total)
        }
        return total
    }

    internal fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
