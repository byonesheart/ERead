package com.eyue.reader.data.cloud

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CloudArchiveTest {
    private fun <T> directory(block: (File) -> T): T {
        val root = Files.createTempDirectory("cloud-archive-test").toFile()
        try { return block(root) } finally { root.deleteRecursively() }
    }

    @Test fun streamArchiveRoundTripsAndVerifiesEachAsset() = directory { root ->
        val archive = File(root, "book.zip")
        val text = "第一章\n正文😀\n下一段".toByteArray()
        val image = ByteArray(200_000) { (it % 251).toByte() }
        val records = CloudArchive.write(archive, listOf(
            CloudAsset("assets/chapter.txt") { ByteArrayInputStream(text) },
            CloudAsset("assets/page.png") { ByteArrayInputStream(image) }
        ), { "{\"version\":1}".toByteArray() })
        assertEquals(text.size.toLong(), records[0].bytes)
        assertEquals(64, CloudArchive.hash(archive).length)
        val output = File(root, "restored")
        assertEquals("{\"version\":1}", CloudArchive.extract(archive, output, records).toString(Charsets.UTF_8))
        assertArrayEquals(text, File(output, "assets/chapter.txt").readBytes())
        assertArrayEquals(image, File(output, "assets/page.png").readBytes())
    }

    @Test fun recordsLargerThanThePreviousSixteenMegabyteBudgetRoundTripInTheExistingFormat() = directory { root ->
        val archive = File(root, "large-records.zip")
        val metadata = ("{\"version\":1,\"records\":\"" + "n".repeat(17 * 1024 * 1024) + "\"}").toByteArray()
        assertTrue(metadata.size > 16 * 1024 * 1024 && metadata.size < CLOUD_METADATA_LIMIT)
        val records = CloudArchive.write(archive, emptyList(), { metadata })
        assertArrayEquals(metadata, CloudArchive.extract(archive, File(root, "restored"), records))
    }

    @Test fun pathsRejectTraversalAbsoluteUrlsAndEmptySegments() {
        for (name in listOf("../outside", "/outside", "assets/../outside", "assets//page", "assets/./page", "C:/outside", "assets\\page", "https://evil/path", "assets/page\u0000")) {
            assertThrows(IllegalArgumentException::class.java) { CloudArchive.safeName(name) }
        }
        assertEquals("assets/chapter-1.txt", CloudArchive.safeName("assets/chapter-1.txt"))
    }

    @Test fun corruptedChecksumRollsBackOnlyNewExtraction() = directory { root ->
        val archive = File(root, "book.zip")
        val records = CloudArchive.write(archive, listOf(CloudAsset("assets/text.txt") { ByteArrayInputStream("content".toByteArray()) }), { "{}".toByteArray() })
        val previous = File(root, "previous.txt").apply { writeText("previous") }
        val output = File(root, "new")
        assertThrows(IllegalArgumentException::class.java) { CloudArchive.extract(archive, output, records.map { it.copy(sha256 = "0".repeat(64)) }) }
        assertFalse(output.exists())
        assertEquals("previous", previous.readText())
    }

    @Test fun extractionRejectsPreexistingDirectoryWithoutDeletingIt() = directory { root ->
        val archive = File(root, "book.zip")
        val records = CloudArchive.write(archive, emptyList(), { "{}".toByteArray() })
        val output = File(root, "existing").apply { mkdirs() }
        File(output, "keep.txt").writeText("keep")
        assertThrows(IllegalArgumentException::class.java) { CloudArchive.extract(archive, output, records) }
        assertEquals("keep", File(output, "keep.txt").readText())
    }

    @Test fun archiveRejectsDuplicateAssetNamesAndRemovesPartialZip() = directory { root ->
        val archive = File(root, "book.zip")
        val asset = CloudAsset("assets/page.jpg") { ByteArrayInputStream(byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { CloudArchive.write(archive, listOf(asset, asset), { byteArrayOf() }) }
        assertFalse(archive.exists())
    }

    @Test fun archiveRejectsUnexpectedFilesAndTraversalOnRestore() = directory { root ->
        for (name in listOf("assets/unlisted.txt", "../outside.txt")) {
            val archive = File(root, "bad.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry(name)); zip.write("bad".toByteArray()); zip.closeEntry()
            }
            val output = File(root, "restored")
            assertThrows(IllegalArgumentException::class.java) { CloudArchive.extract(archive, output, emptyList()) }
            assertFalse(output.exists())
            assertFalse(File(root, "outside.txt").exists())
        }
    }

    @Test fun declaredAssetSizeMustMatchRealSize() = directory { root ->
        val archive = File(root, "book.zip")
        val records = CloudArchive.write(archive, listOf(CloudAsset("assets/text") { ByteArrayInputStream("content".toByteArray()) }), { "{}".toByteArray() })
        val output = File(root, "restored")
        assertThrows(IllegalArgumentException::class.java) { CloudArchive.extract(archive, output, records.map { it.copy(bytes = 1) }) }
        assertFalse(output.exists())
    }

    @Test fun cancelledWriteDeletesPartialArchive() = directory { root ->
        val archive = File(root, "book.zip")
        var checks = 0
        assertThrows(IOException::class.java) {
            CloudArchive.write(archive, listOf(CloudAsset("assets/text") { ByteArrayInputStream(ByteArray(200_000)) }), { "{}".toByteArray() }) {
                if (++checks > 2) throw IOException("cancelled")
            }
        }
        assertFalse(archive.exists())
    }

    @Test fun boundedCopyRejectsOverflowBeforeWritingExcess() {
        val output = java.io.ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) { CloudArchive.copy(ByteArrayInputStream(ByteArray(100)), output, 99) }
        assertEquals(0, output.size())
    }

    @Test fun backupIdsCannotBecomePaths() {
        assertEquals("book-abc_123", CloudArchive.safeId("book-abc_123"))
        for (id in listOf("../book", "book/child", "book.txt", "", "x".repeat(129))) {
            assertThrows(IllegalArgumentException::class.java) { CloudArchive.safeId(id) }
        }
    }

    @Test fun multipartBoundariesStayWithinJianguoyunUploadLimit() {
        val exact = CloudArchive.parts(CLOUD_PART_BYTES, "backup-test")
        assertEquals(listOf(CloudArchivePart("backup-test-0000.bin", CLOUD_PART_BYTES)), exact)
        val larger = CloudArchive.parts(CLOUD_PART_BYTES * 2 + 17, "backup-test")
        assertEquals(listOf(CLOUD_PART_BYTES, CLOUD_PART_BYTES, 17L), larger.map { it.bytes })
        assertEquals(CLOUD_PART_BYTES * 2 + 17, larger.sumOf { it.bytes })
        val maximum = CloudArchive.parts(CLOUD_ARCHIVE_LIMIT, "backup-limit")
        assertEquals(CLOUD_PART_LIMIT, maximum.size)
        assertEquals(CLOUD_ARCHIVE_LIMIT, maximum.sumOf { it.bytes })
        assertEquals("backup-limit-32767.bin", maximum.last().name)
        assertThrows(IllegalArgumentException::class.java) { CloudArchive.parts(0, "backup-test") }
    }

    @Test fun inspectionFindsChangedBytesWithoutConstructingArchive() {
        var bytes = "first".toByteArray()
        val asset = CloudAsset("assets/source.mobi") { ByteArrayInputStream(bytes) }
        val first = CloudArchive.inspect(listOf(asset))
        bytes = "other".toByteArray()
        val second = CloudArchive.inspect(listOf(asset))
        assertEquals(first[0].bytes, second[0].bytes)
        assertNotEquals(first[0].sha256, second[0].sha256)
    }
}
