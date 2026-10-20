package com.eyue.reader.data.cloud

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CloudResourceHashCacheDeviceTest {
    @Test fun nativeStatReusesUnchangedBytesButDetectsAWriteWithRestoredMtime() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "native-cloud-hash-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val source = File(root, "source").apply { writeBytes(ByteArray(2 * 1024 * 1024) { 7 }) }
            assertTrue(source.setLastModified(946684800000L))
            val before = cloudFileVersion(context, source.path)
            assumeNotNull(before)
            val cacheFile = File(root, "hashes.json")
            var opened = 0
            val asset = CloudAsset("assets/source", source.path) { opened++; source.inputStream() }
            val initial = CloudResourceHashCache(cacheFile) { cloudFileVersion(context, it) }
            val original = CloudArchive.inspect(listOf(asset), cache = initial).single()
            initial.save()
            val recreated = CloudResourceHashCache(cacheFile) { cloudFileVersion(context, it) }
            assertEquals(original, CloudArchive.inspect(listOf(asset), cache = recreated).single())
            assertEquals(1, opened)
            val mtime = source.lastModified()
            Thread.sleep(5)
            source.writeBytes(ByteArray(2 * 1024 * 1024) { 8 })
            assertTrue(source.setLastModified(mtime))
            assertEquals(mtime, source.lastModified())
            val changed = cloudFileVersion(context, source.path)
            assertEquals(before!!.modifiedSeconds, changed!!.modifiedSeconds)
            assertEquals(before.modifiedNanos, changed.modifiedNanos)
            assertNotEquals(before, changed)
            assertNotEquals(original.sha256, CloudArchive.inspect(listOf(asset), cache = recreated).single().sha256)
            assertEquals(2, opened)
        } finally { root.deleteRecursively() }
    }
}
