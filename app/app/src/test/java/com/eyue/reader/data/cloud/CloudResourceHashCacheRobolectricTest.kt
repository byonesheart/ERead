package com.eyue.reader.data.cloud

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CloudResourceHashCacheRobolectricTest {
    @Test fun metadataOnlyPassReusesResourceHashesAndTextIdentityAfterCacheReconstruction() = directory { root ->
        val source = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        val version = version("private/book.mobi", source.size.toLong())
        val cacheFile = File(root, "hashes.json")
        var opened = 0
        var identityReads = 0
        val asset = CloudAsset("assets/book.mobi", version.path) { opened++; ByteArrayInputStream(source) }
        val firstCache = CloudResourceHashCache(cacheFile) { version }
        val first = CloudArchive.inspect(listOf(asset), cache = firstCache)
        val identity = firstCache.identity("text-v1", listOf(version.path), {}) { identityReads++; "text-v1:${first.single().sha256}" }
        firstCache.save()

        // A worker and service are reconstructed between uploads; only reading records have changed.
        val nextCache = CloudResourceHashCache(cacheFile) { version }
        assertEquals(first, CloudArchive.inspect(listOf(asset), cache = nextCache))
        assertEquals(identity, nextCache.identity("text-v1", listOf(version.path), {}) { identityReads++; error("Unchanged text must not be opened") })
        assertEquals("Metadata-only inspection must not open the large resource again", 1, opened)
        assertEquals(1, identityReads)
        assertThrows(IOException::class.java) { nextCache.inspect(asset, CLOUD_ARCHIVE_LIMIT) { throw IOException("cancelled") } }
        assertEquals(1, opened)
    }

    @Test fun changedCtimeInvalidatesSameLengthContentEvenWhenMtimeIsRestored() = directory { root ->
        val source = File(root, "source.bin").apply { writeText("first") }
        val oldMtime = source.lastModified()
        var stamp = version(source.path, source.length()).copy(modifiedSeconds = oldMtime / 1000, modifiedNanos = oldMtime % 1000 * 1_000_000)
        var opened = 0
        var identityReads = 0
        val asset = CloudAsset("assets/source.bin", source.path) { opened++; source.inputStream() }
        val cacheFile = File(root, "hashes.json")
        val cache = CloudResourceHashCache(cacheFile) { stamp }
        val first = CloudArchive.inspect(listOf(asset), cache = cache).single()
        cache.identity("text-v1", listOf(source.path), {}) { identityReads++; "text-v1:${first.sha256}" }
        cache.save()
        source.writeText("other")
        assertTrue(source.setLastModified(oldMtime))
        assertEquals(first.bytes, source.length())
        assertEquals(oldMtime, source.lastModified())
        // A native filesystem changes ctime on a write and again when restoring mtime.
        stamp = stamp.copy(changedNanos = stamp.changedNanos + 1)
        val next = CloudResourceHashCache(cacheFile) { stamp }
        val changed = CloudArchive.inspect(listOf(asset), cache = next).single()
        next.identity("text-v1", listOf(source.path), {}) { identityReads++; "text-v1:${changed.sha256}" }
        assertNotEquals(first.sha256, changed.sha256)
        assertEquals(2, opened)
        assertEquals(2, identityReads)
    }

    @Test fun fileReplacementAndNanosecondChangesInvalidateEvenWhenTheBytesStayTheSame() = directory { root ->
        var stamp = version("private/source", 3)
        var opened = 0
        val asset = CloudAsset("assets/source", stamp.path) { opened++; ByteArrayInputStream(byteArrayOf(1, 2, 3)) }
        val cache = CloudResourceHashCache(File(root, "hashes.json")) { stamp }
        CloudArchive.inspect(listOf(asset), cache = cache)
        for (changed in listOf(stamp.copy(inode = 2), stamp.copy(device = 2), stamp.copy(modifiedNanos = 2), stamp.copy(changedNanos = 2))) {
            stamp = changed
            CloudArchive.inspect(listOf(asset), cache = cache)
        }
        assertEquals(5, opened)
    }

    @Test fun unknownVersionsAndContentUrisAlwaysReadAndAConcurrentChangeCannotSeedTheCache() = directory { root ->
        val unknown = CloudResourceHashCache(File(root, "unknown.json")) { null }
        var opened = 0
        var identityReads = 0
        val asset = CloudAsset("assets/provider", "content://provider/document/book") { opened++; ByteArrayInputStream(byteArrayOf(7)) }
        repeat(2) {
            CloudArchive.inspect(listOf(asset), cache = unknown)
            unknown.identity("text-v1", listOf(requireNotNull(asset.sourcePath)), {}) { identityReads++; "text-v1:" + "a".repeat(64) }
            unknown.save()
        }
        assertEquals(2, opened)
        assertEquals(2, identityReads)
        assertNull(cloudFileVersion(RuntimeEnvironment.getApplication(), requireNotNull(asset.sourcePath)))

        var stamp = version("private/changing", 1)
        val changing = CloudAsset("assets/changing", stamp.path) {
            opened++
            stamp = stamp.copy(changedNanos = stamp.changedNanos + 1)
            ByteArrayInputStream(byteArrayOf(7))
        }
        val unstable = CloudResourceHashCache(File(root, "unstable.json")) { stamp }
        CloudArchive.inspect(listOf(changing), cache = unstable)
        unstable.save()
        CloudArchive.inspect(listOf(changing), cache = CloudResourceHashCache(File(root, "unstable.json")) { stamp })
        assertEquals("A version changed while reading must not be cached", 4, opened)
    }

    @Test fun savingRetainsOnlyThisUploadsAssets() = directory { root ->
        val file = File(root, "hashes.json")
        fun asset(name: String) = CloudAsset("assets/$name", name) { ByteArrayInputStream(byteArrayOf(1)) }
        val first = CloudResourceHashCache(file) { version(it, 1) }
        CloudArchive.inspect(listOf(asset("a"), asset("b")), cache = first)
        first.save()
        val next = CloudResourceHashCache(file) { version(it, 1) }
        CloudArchive.inspect(listOf(asset("b")), cache = next)
        next.save()
        assertEquals(1, JSONObject(file.readText()).getJSONArray("assets").length())
    }

    private fun version(path: String, bytes: Long) = CloudFileVersion(path, 1, 1, bytes, 100, 1, 100, 1)
    private fun directory(block: (File) -> Unit) {
        val root = File(RuntimeEnvironment.getApplication().cacheDir, "cloud-hashes-${UUID.randomUUID()}").apply { mkdirs() }
        try { block(root) } finally { root.deleteRecursively() }
    }
}
