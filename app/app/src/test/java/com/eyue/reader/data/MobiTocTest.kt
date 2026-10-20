package com.eyue.reader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException

class MobiTocTest {
    @Test fun indexesNumericFilePositionNotesAcrossSectionsWithoutChangingTheNativeDirectory() {
        val template = "<body><h1>第一章</h1><p>甲🙂<a filepos='0000000000'><sup>1</sup></a>乙" +
            "<a href='#fn2'>[2]</a></p><h1>第二章</h1><p>后文</p><p id='fn1'>数字小标注释</p>" +
            "<p><a id='fn2'></a>锚点注释<a role='doc-backlink' href='#fnref2'>↩</a></p></body>"
        val position = template.substring(0, template.indexOf("<p id='fn1'>")).toByteArray().size
        val html = template.replace("0000000000", position.toString().padStart(10, '0'))
        fixture(html, listOf(0 to "第一章", html.indexOf("<h1>第二章") to "第二章")) {
            val book = MobiArchive.read(it)
            assertEquals(listOf("第一章", "第二章"), book.sections.map { it.title })
            assertTrue(book.sections[0].sourceNoteTargets.getValue("filepos:$position").contains("数字小标注释"))
            assertTrue(book.sections[0].sourceNoteTargets.getValue("#fn2").contains("锚点注释"))
            assertTrue(book.sections[1].sourceNoteTargets.isEmpty())
        }
    }

    @Test fun nativeParentIndexesKeepTheOutermostTitleWithoutMovingChapterBoundaries() {
        val html = "<body><p>卷首</p><p>第一章正文</p><p>第一节正文</p><p>尾声正文</p></body>"
        val positions = listOf(0, html.indexOf("<p>第一章"), html.indexOf("<p>第一节"), html.indexOf("<p>尾声"))
        val titles = listOf("上卷", "第一章", "第一节", "尾声")
        fixture(html, positions.zip(titles), parents = listOf(null, 0, 1, null)) {
            val sections = MobiArchive.read(it).sections
            assertEquals(titles, sections.map { it.title })
            assertEquals(listOf(null, "上卷", "上卷", null), sections.map { it.tocParentTitle })
            assertEquals(positions, sections.map { it.sourceStart })
            assertEquals(html.length, sections.last().sourceEnd)
            assertTrue(sections[1].html.contains("第一章正文") && !sections[1].html.contains("第一节正文"))
        }
    }

    @Test fun invalidOrCyclicParentIndexesLeaveTheFlatDirectoryUsable() {
        val titles = listOf(10 to "甲", 20 to "乙")
        for (parents in listOf(listOf(1, 0), listOf(99, 0), listOf(0, null))) {
            val entries = read(directory(titles, parents))
            assertEquals(titles.map { it.second }, entries.map { it.title })
            assertTrue(entries.all { it.tocParentTitle == null })
        }
    }

    @Test fun writingEditedMobiRetainsItsNativeParentsAndChapterText() {
        val html = "<body><p>卷首</p><p>第一章原文</p><p>第二章正文</p><p>尾声正文</p></body>"
        val positions = listOf(0, html.indexOf("<p>第一章"), html.indexOf("<p>第二章"), html.indexOf("<p>尾声"))
        val titles = listOf("上卷", "第一章", "第二章", "尾声")
        fixture(html, positions.zip(titles), parents = listOf(null, 0, 0, null)) { source ->
            val parsed = MobiArchive.read(source)
            val at = html.indexOf("原文")
            val edit = SourceHtmlEdit.apply(html, listOf(SourceHtmlEdit.Change(at, at + 2, "修改后的正文")))
            val output = File.createTempFile("eread-nested-written-", ".mobi")
            try {
                MobiSourceWriter.write(source, output, html, edit, parsed.sections)
                val reread = MobiArchive.read(output)
                assertEquals(titles, reread.sections.map { it.title })
                assertEquals(listOf(null, "上卷", "上卷", null), reread.sections.map { it.tocParentTitle })
                assertEquals(positions.map(edit::offset), reread.sections.map { it.sourceStart })
                assertTrue(reread.sections[1].html.contains("第一章修改后的正文"))
                assertTrue(reread.sections[2].html.contains("第二章正文"))
            } finally { output.delete() }
        }
    }

    @Test fun singleEmbeddedEntryRemainsAnExplicitSourceDirectory() {
        fixture("<body><h1>Chapter I</h1><p>正文</p></body>", listOf(0 to "源目录的标题")) {
            val book = MobiArchive.read(it)
            assertTrue(book.hasEmbeddedToc)
            assertEquals("源目录的标题", book.sections.single().title)
            assertEquals(null, book.sections.single().tocParentTitle)
        }
    }

    @Test fun byteLengthControlsConsumeAllValuesBeforeFollowingTags() {
        val records = directory(listOf(25 to "标题")).toMutableList()
        records[0][70] = 3
        records[0][74] = 4
        records[0][78] = 8
        records[1] = indexRecord(listOf(byteArrayOf(1, 48, 15) + vlq(2) + vlq(25) + vlq(26) + vlq(0) + vlq(0xffffffffL)))
        assertEquals(listOf(MobiTocEntry(25, "标题")), read(records))
    }

    @Test fun separateControlBytesAndUnknownParentValuesAreConsumedCorrectly() {
        val records = directory(listOf(25 to "标题")).toMutableList()
        records[0] = records[0].copyOf(88).also {
            put(it, 60, 32); put(it, 64, 2)
            byteArrayOf(1, 1, 1, 0, 0, 0, 0, 1, 3, 1, 1, 0, 21, 1, 2, 0, 0, 0, 0, 1).copyInto(it, 68)
        }
        records[1] = indexRecord(listOf(byteArrayOf(1, 48, 1, 3) + vlq(25) + vlq(0) + vlq(0xffffffffL)))
        assertEquals(listOf(MobiTocEntry(25, "标题")), read(records))
    }

    @Test fun labelsCanReferenceTheSecondCtocRecord() {
        val records = directory(listOf(25 to "第一标签")).toMutableList()
        put(records[0], 52, 2)
        records[1] = indexRecord(listOf(byteArrayOf(1, 48, 7) + vlq(25) + vlq(0x10000) + vlq(0xffffffffL)))
        val text = "第二标签🙂".toByteArray()
        records += vlq(text.size.toLong()) + text
        assertEquals(listOf(MobiTocEntry(25, "第二标签🙂")), read(records))
    }

    @Test fun indexEncodingControlsLabelDecoding() {
        val records = directory(listOf(25 to "Café")).toMutableList()
        put(records[0], 28, 1252)
        val text = "Café".toByteArray(Charsets.ISO_8859_1)
        records[2] = vlq(text.size.toLong()) + text
        assertEquals(listOf(MobiTocEntry(25, "Café")), read(records))
    }

    @Test fun embeddedTocWinsOverUnrelatedHeadingsAndPreservesUtf16TextAndTail() {
        val html = "<body><p>甲🙂引言</p><p>第一节正文</p><h1>正文中的小标题</h1><p>第二节🙂正文及尾部</p></body>"
        val first = html.indexOf("<p>第一")
        val second = html.indexOf("<p>第二")
        fixture(html, listOf(first to "源目录甲", second to "源目录乙")) { file ->
            val book = MobiArchive.read(file)
            assertEquals(listOf("前言", "源目录甲", "源目录乙"), book.sections.map { it.title })
            assertTrue(book.sections[1].html.contains("正文中的小标题"))
            assertTrue(book.sections.last().html.contains("第二节🙂正文及尾部"))
        }
    }

    @Test fun missingOrMalformedOptionalIndexesUseTheExistingHtmlDirectory() {
        val html = "<h1>Chapter I</h1><p>甲</p><h1>Chapter II</h1><p>乙</p>"
        for (change in listOf<(MutableList<ByteArray>) -> Unit>(
            { it[0][0] = 0 }, // INDX signature
            { put(it[0], 24, Int.MAX_VALUE) }, // record count
            { put(it[0], 60, Int.MAX_VALUE) }, // TAGX length
            { put(it[1], 20, Int.MAX_VALUE) }, // IDXT offset
            { it[1][58] = 0 }, // unterminated/invalid field sequence
            { it[2][0] = 0x7f } // truncated CTOC length
        )) fixture(html, listOf(0 to "甲", html.indexOf("<h1>Chapter II") to "乙"), change) {
            assertEquals(listOf("Chapter I", "Chapter II"), MobiArchive.read(it).sections.map { it.title })
        }
    }

    @Test fun optionalDirectoryDoesNotSwallowCancellation() {
        val records = directory(listOf(0 to "甲", 20 to "乙"))
        val cancelled = CancellationException("cancel index")
        val error = runCatching { MobiToc.read(1, 4, 100, Charsets.UTF_8, { records[it - 1] }) { throw cancelled } }.exceptionOrNull()
        assertTrue(error === cancelled)
    }

    @Test fun footnoteOnlyIndexFallsBackAndFootnoteTextRemainsInBody() {
        val html = "<h1>Chapter I</h1><p>甲</p><h1>Chapter II</h1><p>乙</p><aside epub:type='footnote'>注释文字</aside>"
        fixture(html, listOf(html.indexOf("<aside") to "译者注")) {
            val book = MobiArchive.read(it)
            assertTrue(!book.hasEmbeddedToc)
            assertEquals(listOf("Chapter I", "Chapter II"), book.sections.map { it.title })
            assertTrue(book.sections.last().html.contains("注释文字"))
        }
    }

    @Test fun offsetInsideAMultibyteCharacterFallsBackWithoutFailingTheBook() {
        val html = "<body>🙂<h1>Chapter I</h1><p>甲</p><h1>Chapter II</h1><p>乙</p></body>"
        val records = directory(listOf(7 to "wrong"))
        // The second byte of the emoji is invalid as a UTF-8 chapter boundary.
        fixture(html, listOf(0 to "wrong"), { list -> list[1] = records[1] }) {
            assertEquals(listOf("前言", "Chapter I", "Chapter II"), MobiArchive.read(it).sections.map { it.title })
        }
    }

    @Test fun realGutenbergMobiUsesTitlesFromItsNativeIndex() {
        val source = listOf(File("../.tools/fixtures/alice.mobi"), File(".tools/fixtures/alice.mobi")).firstOrNull { it.isFile }
        assumeTrue("Public-domain corpus is optional", source != null)
        val titles = MobiArchive.read(source!!).sections.map { it.title }
        assertTrue(titles.any { it.contains("Rabbit-Hole", ignoreCase = true) })
        assertTrue(titles.any { it.contains("Alice", ignoreCase = true) && it.contains("Evidence", ignoreCase = true) })
    }

    private fun fixture(html: String, toc: List<Pair<Int, String>>, mutate: (MutableList<ByteArray>) -> Unit = {},
                        parents: List<Int?> = emptyList(), block: (File) -> Unit) {
        val bytes = html.toByteArray()
        val records = directory(toc.map { (at, title) -> html.substring(0, at).toByteArray().size to title }, parents).toMutableList()
        mutate(records)
        val header = ByteArray(252)
        put16(header, 0, 1)
        put(header, 4, bytes.size)
        put16(header, 8, 1)
        "MOBI".toByteArray().copyInto(header, 16)
        put(header, 20, 232)
        put(header, 24, 2)
        put(header, 28, 65001)
        put(header, 36, 6)
        put(header, 84, 248)
        put(header, 88, 4)
        put(header, 104, 6)
        put(header, 108, -1)
        put(header, 244, 2)
        "Book".toByteArray().copyInto(header, 248)
        val all = listOf(header, bytes) + records
        val table = 78 + all.size * 8 + 2
        val pdb = ByteArray(table + all.sumOf { it.size })
        "BOOKMOBI".toByteArray().copyInto(pdb, 60)
        put16(pdb, 76, all.size)
        var at = table
        all.forEachIndexed { index, record -> put(pdb, 78 + index * 8, at); record.copyInto(pdb, at); at += record.size }
        val file = File.createTempFile("eread-index-", ".mobi")
        try { file.writeBytes(pdb); block(file) } finally { file.delete() }
    }

    private fun directory(toc: List<Pair<Int, String>>, parents: List<Int?> = emptyList()): List<ByteArray> {
        val strings = ByteArrayOutputStream()
        val fields = toc.mapIndexed { index, (offset, title) ->
            val at = strings.size()
            val text = title.toByteArray()
            strings.write(vlq(text.size.toLong())); strings.write(text)
            byteArrayOf(1, ('0'.code + index).toByte(), 7) + vlq(offset.toLong()) + vlq(at.toLong()) + vlq(parents.getOrNull(index)?.toLong() ?: 0xffffffffL)
        }
        val main = ByteArray(84)
        "INDX".toByteArray().copyInto(main)
        put(main, 4, 56); put(main, 24, 1); put(main, 28, 65001); put(main, 52, 1)
        "TAGX".toByteArray().copyInto(main, 56)
        put(main, 60, 28); put(main, 64, 1)
        byteArrayOf(1, 1, 1, 0, 3, 1, 2, 0, 21, 1, 4, 0, 0, 0, 0, 1).copyInto(main, 68)
        return listOf(main, indexRecord(fields), strings.toByteArray())
    }

    private fun read(records: List<ByteArray>): List<MobiTocEntry> =
        MobiToc.read(1, records.size + 1, 100, Charsets.UTF_8, { records[it - 1] }, {})

    private fun indexRecord(fields: List<ByteArray>): ByteArray {
        val idxt = 56 + fields.sumOf { it.size }
        val data = ByteArray(idxt + 4 + fields.size * 2)
        "INDX".toByteArray().copyInto(data)
        put(data, 4, 56); put(data, 20, idxt); put(data, 24, fields.size)
        var at = 56
        fields.forEachIndexed { index, bytes -> put16(data, idxt + 4 + index * 2, at); bytes.copyInto(data, at); at += bytes.size }
        "IDXT".toByteArray().copyInto(data, idxt)
        return data
    }
    private fun vlq(value: Long): ByteArray {
        var remaining = value
        val bytes = mutableListOf((remaining and 127).toByte())
        remaining = remaining ushr 7
        while (remaining != 0L) { bytes.add(0, (remaining and 127).toByte()); remaining = remaining ushr 7 }
        bytes[bytes.lastIndex] = (bytes.last().toInt() or 128).toByte()
        return bytes.toByteArray()
    }
    private fun put(bytes: ByteArray, at: Int, value: Int) { ByteBuffer.wrap(bytes, at, 4).putInt(value) }
    private fun put16(bytes: ByteArray, at: Int, value: Int) { ByteBuffer.wrap(bytes, at, 2).putShort(value.toShort()) }
}
