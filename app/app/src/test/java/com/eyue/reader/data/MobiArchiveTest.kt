package com.eyue.reader.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

class MobiArchiveTest {
    @Test fun tenMegabytesOfUnclosedNamedAnchorsKeepTheRealTocAndFinishDirectoryProgress() {
        val html = buildString {
            append("<body><a href='#one'>Chapter I</a><a href='#two'>Chapter II</a>")
            repeat(50_000) { append("<a name='marker$it'>").append("body".repeat(50)) }
            append("<h1 id='one'>First heading</h1><p>甲🙂正文</p>")
            append("<h1 id='two'>Second heading</h1><p>乙🙂正文</p></body>")
        }
        fixture(html) { file ->
            val updates = mutableListOf<Float>()
            val started = System.nanoTime()
            val parsed = MobiArchive.read(file, onProgress = updates::add)
            println("MOBI malformed named anchors: ${file.length()} bytes, ${(System.nanoTime() - started) / 1_000_000} ms")
            assertTrue(file.length() >= 10_000_000)
            assertEquals(listOf("前言", "Chapter I", "Chapter II"), parsed.sections.map { it.title })
            assertTrue(parsed.sections[1].html.contains("甲🙂正文"))
            assertTrue(parsed.sections.last().html.contains("乙🙂正文"))
            assertTrue("目录阶段不能停留在解压的96%", updates.any { it > .96f && it < 1f })
            assertTrue(updates.zipWithNext().all { (before, after) -> after >= before })
            assertEquals(1f, updates.last(), 0f)
        }
    }

    @Test fun unclosedLinksHeadingsAndMissingTagTerminatorsDoNotSwallowLaterChapters() {
        val malformed = "<a href='#lost'>unfinished link ".repeat(3000) +
            "<h1>unfinished heading ".repeat(3000) + "<broken ".repeat(3000)
        fixture("<body>$malformed<h1>Chapter I</h1><p>正文</p><h2>Chapter II</h2><p>后续</p></body>") { file ->
            assertEquals(listOf("前言", "Chapter I", "Chapter II"), MobiArchive.read(file).sections.map { it.title })
        }
    }

    @Test fun unclosedHeadAndStyleRecoverAtTheRealBodyWithoutLeakingFakeHeadings() {
        for (head in listOf("<head>", "<head><style>")) {
            fixture("<html>$head" + "<style>h1{font-size:3em}".repeat(3000) +
                "<h1>Hidden heading</h1><body><h1>Chapter I</h1><p>正文</p><h1>Chapter II</h1><p>后续</p></body></html>") { file ->
                assertEquals(listOf("Chapter I", "Chapter II"), MobiArchive.read(file).sections.map { it.title })
            }
        }
    }

    @Test fun closedStylesheetStringsDoNotSupplyFakeChapterHeadingsOrBodyDefaults() = fixture(
        "<head><style>p:before{content:'<body><h1>Hidden</h1>'}</style></head>" +
            "<body class='real'><h1>Chapter I</h1><p>正文</p><h1>Chapter II</h1><p>后续</p></body>"
    ) { file ->
        val parsed = MobiArchive.read(file)
        assertEquals(listOf("Chapter I", "Chapter II"), parsed.sections.map { it.title })
        assertTrue(parsed.sections.all { it.html.startsWith("<body class='real'>") })
    }

    @Test fun cancellationDuringDirectoryScanningPropagatesBeforeCompletion() = fixture(
        "<body>" + "<a name='marker'>body ".repeat(20_000) + "<h1>Real heading</h1><p>正文</p></body>"
    ) { file ->
        val updates = mutableListOf<Float>()
        var directoryChecks = 0
        val cancelled = java.util.concurrent.CancellationException("cancel directory")
        val error = runCatching {
            MobiArchive.read(file, checkActive = {
                if (updates.lastOrNull()?.let { it >= .96f } == true && ++directoryChecks == 12) throw cancelled
            }, onProgress = updates::add)
        }.exceptionOrNull()
        assertTrue(error === cancelled)
        assertTrue(directoryChecks >= 12)
        assertTrue(updates.none { it == 1f })
    }

    @Test fun declaredTextLengthAllowsRecordTailWithoutChangingTheBody() = fixture("<p>body</p>unused tail") { file ->
        val bytes = file.readBytes()
        val headerOffset = ByteBuffer.wrap(bytes, 78, 4).int
        put32(bytes, headerOffset + 4, "<p>body</p>".toByteArray().size)
        file.writeBytes(bytes)
        assertEquals("<p>body</p>", MobiArchive.read(file).sections.single().html)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsTextLengthAboveTheExistingLimit() = fixture("<p>body</p>") { file ->
        val bytes = file.readBytes()
        val headerOffset = ByteBuffer.wrap(bytes, 78, 4).int
        put32(bytes, headerOffset + 4, 64 * 1024 * 1024 + 1)
        file.writeBytes(bytes)
        MobiArchive.read(file)
    }

    @Test fun bodyFootnoteLinksDoNotReplaceChapterHeadings() = fixture(
        "<h1>Chapter I</h1><p>正文<a href='#note-a'>[1]</a><a href='#note-b'>注释2</a></p>" +
            "<h1>Chapter II</h1><p>后续</p><aside id='note-a' epub:type='footnote'><h1>[1]</h1>解释一</aside>" +
            "<section id='note-b' role='doc-endnote'><h2>注释2</h2>解释二</section>"
    ) { file ->
        val sections = MobiArchive.read(file).sections
        assertEquals(listOf("Chapter I", "Chapter II"), sections.map { it.title })
        assertTrue(sections.last().html.contains("解释一"))
        assertTrue(sections.last().html.contains("解释二"))
    }

    @Test fun frontTocKeepsChaptersButIgnoresSemanticAndUnmarkedBodyReferences() = fixture(
        "<nav><a href='#one'>第一章</a><a href='#two'>第二章</a>" +
            "<a href='#notes' role='doc-noteref'>译者说明</a></nav>" +
            "<div id='one'><p>正文<a href='#later'>查看说明</a></p></div>" +
            "<div id='two'><p>后续</p></div><div id='later'>仍属于第二章的说明</div>" +
            "<aside id='notes' epub:type='footnote'>译者注释</aside>"
    ) { file ->
        val sections = MobiArchive.read(file).sections
        assertEquals(listOf("前言", "第一章", "第二章"), sections.map { it.title })
        assertTrue(sections.last().html.contains("译者注释"))
        assertTrue(sections.last().html.contains("仍属于第二章的说明"))
    }

    @Test fun fileposFootnotesDoNotBecomeChapters() {
        val template = "<h1>Chapter I</h1><p>正文<a filepos='0000000000'>[1]</a></p>" +
            "<h1>Chapter II</h1><p>后续<a filepos='0000000000' epub:type='noteref'>说明</a></p>" +
            "<aside role='doc-footnote'>解释</aside>"
        val offset = template.substring(0, template.indexOf("<aside")).toByteArray().size
        fixture(template.replace("0000000000", offset.toString().padStart(10, '0'))) { file ->
            assertEquals(listOf("Chapter I", "Chapter II"), MobiArchive.read(file).sections.map { it.title })
        }
    }

    @Test fun commentedHeadingsAnchorsAndPagebreaksNeverProduceChapters() = fixture(
        "<!--<h1>错误注释标题</h1><a href='#hidden'>错误目录</a><div id='hidden'>隐藏</div><mbp:pagebreak/>-->" +
            "<h1>Chapter I</h1><p>正文</p><!--<h2>第二个错误标题</h2>--><h1>Chapter II</h1><p>后续</p>"
    ) { file ->
        assertEquals(listOf("Chapter I", "Chapter II"), MobiArchive.read(file).sections.map { it.title })
    }

    @Test fun commentsWithMultibyteTextDoNotMoveFileposTargets() {
        val parts = listOf("<p>甲🙂正文</p><!--注释🙂<h1>假标题</h1>-->", "<p>乙🙂正文</p>")
        fixture(fileposHtml(parts)) { file ->
            val sections = MobiArchive.read(file).sections.drop(1)
            assertEquals(listOf("第0段", "第1段"), sections.map { it.title })
            parts.forEachIndexed { index, part -> assertTrue(sections[index].html.contains(part)) }
        }
    }

    @Test fun singleChapterAndPagebreakFallbackIgnoreNoteHeadings() {
        val note = "<aside epub:type='footnote'><h1>译者说明</h1>解释</aside>"
        fixture("$note<h1>唯一章节</h1><p>正文</p>") { file ->
            assertEquals("唯一章节", MobiArchive.read(file).sections.last().title)
        }
        fixture("$note<p>正文</p><mbp:pagebreak/><h1>第二章</h1><p>后续</p>") { file ->
            assertEquals(listOf("第 1 章", "第二章"), MobiArchive.read(file).sections.map { it.title })
        }
    }

    @Test fun thousandsOfFileposTargetsAreIndexedWithoutRepeatedPrefixDecoding() {
        val count = 2500
        val parts = (0 until count).map { "<p>第${it}段🙂" + "正文甲乙。".repeat(100) + "</p>" }
        val html = fileposHtml(parts)
        val positions = Regex("filepos='(\\d+)'").findAll(html).map { it.groupValues[1].toLong() }.toList()
        fixture(html) { file ->
            val started = System.nanoTime()
            val book = MobiArchive.read(file)
            println("MOBI indexed $count filepos targets, ${file.length()} bytes, ${(System.nanoTime() - started) / 1_000_000} ms")
            println("Filepos work: legacy repeated prefixes would copy/decode ${positions.sum()} bytes; one index scans ${positions.max()} bytes")
            assertEquals(count + 1, book.sections.size)
            assertEquals("第0段", book.sections[1].title)
            assertTrue(book.sections[1].html.contains("第0段🙂"))
            assertEquals("第2499段", book.sections.last().title)
            assertTrue(book.sections.last().html.contains("第2499段🙂"))
            assertTrue(book.sections[1251].html.contains("第1250段🙂"))
        }
    }

    @Test fun manyNamedAnchorsKeepTheirOwnChaptersAndResolveCaseLikeLegacyMobi() {
        val count = 1200
        val links = (0 until count).joinToString("") { "<a href='#part$it'>第${it}段</a>" }
        val body = (0 until count).joinToString("") { "<div id='PART$it'><p>正文第${it}段🙂</p></div>" }
        fixture("<html><body>$links$body</body></html>") { file ->
            val sections = MobiArchive.read(file).sections
            assertEquals(count + 1, sections.size)
            assertEquals("第1199段", sections.last().title)
            assertTrue(sections[101].html.contains("正文第100段🙂"))
        }
    }

    @Test fun utf8AndWindows1252FileposTargetsMapToExactUtf16ChapterBoundaries() {
        for (charset in listOf(Charsets.UTF_8, Charset.forName("windows-1252"))) {
            val parts = if (charset == Charsets.UTF_8) listOf("<p>甲🙂正文</p>", "<p>乙🙂正文</p>")
                else listOf("<p>First “quoted” body.</p>", "<p>Second café body.</p>")
            fixture(fileposHtml(parts, charset), encoding = if (charset == Charsets.UTF_8) 65001 else 1252) { file ->
                val sections = MobiArchive.read(file).sections.drop(1)
                assertEquals(2, sections.size)
                parts.forEachIndexed { index, part -> assertTrue(sections[index].html.contains(part)) }
            }
        }
    }

    @Test(expected = IllegalArgumentException::class) fun fileposInsideAUtf8CharacterIsRejected() {
        val prefix = "<a filepos='0000000000'>坏位置</a>"
        val body = "<p>甲🙂正文</p>"
        val target = prefix.toByteArray().size + 4
        fixture(prefix.replace("0000000000", target.toString().padStart(10, '0')) + body) { MobiArchive.read(it) }
    }

    @Test fun manyPagebreaksAreSplitInOrderIncludingTheFinalPiece() {
        val html = (0 until 1500).joinToString("<mbp:pagebreak/>") { "<p>Page $it</p>" }
        fixture(html) { file ->
            val sections = MobiArchive.read(file).sections
            assertEquals(1500, sections.size)
            assertTrue(sections.last().html.contains("Page 1499"))
        }
    }

    private fun fileposHtml(parts: List<String>, charset: Charset = Charsets.UTF_8): String {
        val blankToc = parts.indices.joinToString("") { "<a filepos='0000000000'>第${it}段</a>" }
        var byteOffset = blankToc.toByteArray(charset).size
        val positions = parts.map { part -> byteOffset.also { byteOffset += part.toByteArray(charset).size } }
        val toc = positions.mapIndexed { index, offset -> "<a filepos='${offset.toString().padStart(10, '0')}'>第${index}段</a>" }.joinToString("")
        return toc + parts.joinToString("")
    }

    @Test fun progressCoversPalmDocHuffAndImageEnumerationWithoutChangingTheBook() {
        for (compression in listOf(2, 0x4448)) fixture("<p>中文正文</p>", compression = compression,
            images = listOf(pngHeader, pngHeader, pngHeader)) { file ->
            val updates = mutableListOf<Float>()
            val parsed = MobiArchive.read(file, onProgress = updates::add)
            val baseline = MobiArchive.read(file)
            assertEquals(0f, updates.first(), 0f)
            assertEquals(1f, updates.last(), 0f)
            assertTrue(updates.zipWithNext().all { (before, after) -> after >= before })
            assertTrue(updates.any { it >= 0.08f && it <= 0.8f })
            assertTrue("资源枚举应报告真实中间进度", updates.any { it > 0.84f && it < 0.96f })
            assertEquals(baseline.sections, parsed.sections)
            assertEquals(baseline.images, parsed.images)
            assertEquals(3, parsed.images.size)
        }
    }

    @Test fun recordProgressIsMonotonicCompletesAndKeepsTheParsedContent() = fixture(
        "<h1>第一章</h1><p>" + "正文内容。".repeat(5000) + "</p>"
    ) { file ->
        val updates = mutableListOf<Float>()
        val parsed = MobiArchive.read(file, onProgress = updates::add)
        val withoutProgress = MobiArchive.read(file)
        assertEquals(0f, updates.first(), 0f)
        assertEquals(1f, updates.last(), 0f)
        assertTrue(updates.any { it > 0f && it < 1f })
        assertTrue(updates.zipWithNext().all { (before, after) -> after >= before })
        assertEquals(withoutProgress.sections, parsed.sections)
        assertEquals(withoutProgress.css, parsed.css)
        assertEquals(withoutProgress.title, parsed.title)
    }

    @Test fun globalStylesAndBodyDefaultsSurviveChapterSplitting() = fixture(
        "<html><head><style>body{font-size:20px} h1{font-size:30px;text-align:center}</style></head>" +
            "<body style='font-size:20px'><h1>第一章</h1><p>正文一</p><h1>第二章</h1><p>正文二</p></body></html>"
    ) { file ->
        val book = MobiArchive.read(file)
        assertEquals(2, book.sections.size)
        assertTrue(book.css.contains("text-align:center"))
        book.sections.forEach { section ->
            val heading = SourceStyleParser.mark(section.html, book.css, "test_").styles.single()
            assertEquals(1.5f, heading.fontScale!!, .001f)
            assertEquals(SourceTextAlignment.CENTER, heading.alignment)
        }
    }

    @Test fun readsOptionalOfficialGutenbergCorpus() {
        val source = listOf(File("../.tools/fixtures/alice.mobi"), File(".tools/fixtures/alice.mobi")).firstOrNull { it.isFile }
        assumeTrue("Official public-domain MOBI corpus is optional and not bundled", source != null)
        val book = MobiArchive.read(source!!)
        assertTrue(book.title.contains("Alice", ignoreCase = true))
        assertTrue(book.author.contains("Carroll", ignoreCase = true))
        assertTrue(book.sections.size > 1)
        val text = book.sections.joinToString(" ") { it.html }.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ")
        assertTrue(text.contains("White Rabbit", ignoreCase = true))
    }

    @Test fun readsRealPdbMetadataTocAndCoverRecord() = fixture(
        "<html><body><a href='#one'>春风</a><a href='#two'>春雨</a><h1 id='one'>第一章</h1><p>正文一</p><h1 id='two'>第二章</h1><p>正文二</p></body></html>",
        author = "明确作者", updatedTitle = "更新书名", cover = pngHeader
    ) { file ->
        val book = MobiArchive.read(file)
        assertEquals("更新书名", book.title)
        assertEquals("明确作者", book.author)
        assertEquals(listOf("前言", "春风", "春雨"), book.sections.map { it.title })
        assertTrue(book.sections.last().html.contains("正文二"))
        assertEquals("png", book.cover!!.extension)
        assertArrayEquals(pngHeader, book.cover.bytes)
    }

    @Test fun noAuthorFieldStaysBlankAndUsesHeaderTitle() = fixture("<p>anonymous text</p>") { file ->
        val book = MobiArchive.read(file)
        assertEquals("Header Title", book.title)
        assertEquals("", book.author)
        assertNull(book.cover)
        assertEquals("正文", book.sections.single().title)
    }

    @Test fun exposesAndStreamsBodyImageRecordsByOneBasedRecindexBeyondTheCover() = fixture(
        "<p>before<img recindex='00002'/>middle<img recindex='00003'/>after</p>", cover = pngHeader,
        images = listOf(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 12, 13), pngHeader)
    ) { file ->
        val book = MobiArchive.read(file)
        assertEquals(listOf(1, 2, 3), book.images.map { it.recIndex })
        assertEquals(listOf("png", "jpg", "png"), book.images.map { it.extension })
        val destination = File.createTempFile("eyue-mobi-image-", ".jpg")
        try {
            MobiArchive.copyImage(file, book.images[1], destination)
            assertArrayEquals(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 12, 13), destination.readBytes())
        } finally { destination.delete() }
    }

    @Test fun palmDocOverlappingReferencesAndSpacePairs() {
        assertEquals("abcabcabc! A", MobiArchive.decompressPalmDoc(
            byteArrayOf(97, 98, 99, 0x80.toByte(), 0x1b, 33, 0xc1.toByte())).toString(Charsets.UTF_8))
    }

    @Test fun palmDocLiteralRunsPreserveUtf8() = fixture("<h1>第一章 春风</h1><p>中文正文</p>", compression = 2) { file ->
        assertTrue(MobiArchive.read(file).sections.single().html.contains("中文正文"))
    }

    @Test fun huffCdicExpandsLiteralPhraseFromActualDictionaryRecords() = fixture(
        "<p>HUFF compressed text</p>", compression = 0x4448
    ) { file -> assertEquals("<p>HUFF compressed text</p>", MobiArchive.read(file).sections.single().html) }

    @Test fun huffCdicExpandsNestedPhrases() = fixture(
        "<p>recursive dictionary phrase</p>", compression = 0x4448, nestedHuff = true
    ) { file -> assertTrue(MobiArchive.read(file).sections.single().html.contains("recursive dictionary phrase")) }

    @Test fun huffCdicResolvesLongNonterminalCodeThroughLengthRanges() = fixture(
        "<p>long huffman code</p>", compression = 0x4448, longHuff = true
    ) { file -> assertEquals("<p>long huffman code</p>", MobiArchive.read(file).sections.single().html) }

    @Test fun stripsTrailingRecordEntriesAndMultibyteOverlap() = fixture("<p>actual body</p>", extraFlags = 3) { file ->
        assertEquals("<p>actual body</p>", MobiArchive.read(file).sections.single().html)
    }

    @Test fun pageBreaksProvideChaptersWithoutHeadings() = fixture(
        "<p>正文一</p><mbp:pagebreak/><p>正文二</p>"
    ) { file ->
        val sections = MobiArchive.read(file).sections
        assertEquals(2, sections.size)
        assertTrue(sections[0].html.contains("正文一"))
        assertTrue(sections[1].html.contains("正文二"))
    }

    @Test fun imageOnlyPagesAndHeadingPrefixesAreNotDiscarded() {
        fixture("<img recindex='00002'/><mbp:pagebreak/><p>Body</p>") { file ->
            val sections = MobiArchive.read(file).sections
            assertEquals(2, sections.size)
            assertTrue(sections.first().html.contains("recindex='00002'"))
        }
        fixture("<img recindex='00002'/><h1>Chapter I</h1><p>Body one</p><h1>Chapter II</h1><p>Body two</p>") { file ->
            val sections = MobiArchive.read(file).sections
            assertEquals(3, sections.size)
            assertEquals("前言", sections.first().title)
            assertTrue(sections.first().html.contains("recindex='00002'"))
        }
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsDrmEncryption() = fixture("<p>encrypted</p>", encrypted = true) { file ->
        MobiArchive.read(file)
    }

    @Test fun rejectsPureKf8WithActionableMessage() = fixture("<p>fragmented</p>", version = 8) { file ->
        val error = runCatching { MobiArchive.read(file) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException && error.message.orEmpty().contains("KF8"))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsTruncatedPalmDocLiteral() {
        MobiArchive.decompressPalmDoc(byteArrayOf(4, 65))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsInvalidPalmDocBackReference() {
        MobiArchive.decompressPalmDoc(byteArrayOf(0x80.toByte(), 8))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsInvalidRecordOffsets() = fixture("<p>body</p>") { file ->
        val bytes = file.readBytes()
        put32(bytes, 86, bytes.size + 50)
        file.writeBytes(bytes)
        MobiArchive.read(file)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsTruncatedTextLength() = fixture("<p>body</p>") { file ->
        val bytes = file.readBytes()
        val headerOffset = ByteBuffer.wrap(bytes, 78, 4).int
        put32(bytes, headerOffset + 4, 9999)
        file.writeBytes(bytes)
        MobiArchive.read(file)
    }

    private fun fixture(html: String, compression: Int = 1, author: String? = null, updatedTitle: String? = null,
                        cover: ByteArray? = null, extraFlags: Int = 0, version: Int = 6,
                        encrypted: Boolean = false, nestedHuff: Boolean = false, longHuff: Boolean = false,
                        images: List<ByteArray> = emptyList(), encoding: Int = 65001, block: (File) -> Unit) {
        val text = html.toByteArray(if (encoding == 65001) Charsets.UTF_8 else Charset.forName("windows-1252"))
        val dictionaries = if (compression == 0x4448) huffRecords(text, nestedHuff, longHuff) else emptyList()
        var payload = when (compression) {
            2 -> text.asList().chunked(8).flatMap { listOf(it.size.toByte()) + it }.toByteArray()
            0x4448 -> if (longHuff) byteArrayOf(0, 0) else byteArrayOf(0)
            else -> text
        }
        if (extraFlags and 1 != 0) payload += byteArrayOf(0, 1)
        if (extraFlags and 2 != 0) payload += byteArrayOf(7, 8, 0x83.toByte())
        val exthFields = mutableListOf<Pair<Int, ByteArray>>()
        if (author != null) exthFields += 100 to author.toByteArray(Charsets.UTF_8)
        if (updatedTitle != null) exthFields += 503 to updatedTitle.toByteArray(Charsets.UTF_8)
        if (cover != null) exthFields += 201 to byteArrayOf(0, 0, 0, 0)
        val exthSize = 12 + exthFields.sumOf { 8 + it.second.size }
        val title = "Header Title".toByteArray()
        val titleOffset = 248 + if (exthFields.isEmpty()) 0 else exthSize
        val header = ByteArray(titleOffset + title.size)
        put16(header, 0, compression)
        put32(header, 4, text.size)
        put16(header, 8, 1)
        put16(header, 10, 4096)
        put16(header, 12, if (encrypted) 1 else 0)
        "MOBI".toByteArray().copyInto(header, 16)
        put32(header, 20, 232)
        put32(header, 24, 2)
        put32(header, 28, encoding)
        put32(header, 36, version)
        put32(header, 84, titleOffset)
        put32(header, 88, title.size)
        put32(header, 104, 6)
        put32(header, 108, 2 + dictionaries.size)
        put32(header, 112, 2)
        put32(header, 116, dictionaries.size)
        put16(header, 242, extraFlags)
        if (exthFields.isNotEmpty()) {
            put32(header, 128, 64)
            "EXTH".toByteArray().copyInto(header, 248)
            put32(header, 252, exthSize)
            put32(header, 256, exthFields.size)
            var offset = 260
            exthFields.forEach { (id, bytes) ->
                put32(header, offset, id)
                put32(header, offset + 4, 8 + bytes.size)
                bytes.copyInto(header, offset + 8)
                offset += 8 + bytes.size
            }
        }
        title.copyInto(header, titleOffset)
        val records = listOf(header, payload) + dictionaries + listOfNotNull(cover) + images
        val tableSize = 78 + records.size * 8 + 2
        val pdb = ByteArray(tableSize + records.sumOf { it.size })
        "BOOKMOBI".toByteArray().copyInto(pdb, 60)
        put16(pdb, 76, records.size)
        var offset = tableSize
        records.forEachIndexed { index, record ->
            put32(pdb, 78 + index * 8, offset)
            record.copyInto(pdb, offset)
            offset += record.size
        }
        val file = File.createTempFile("eyue-mobi-test-", ".mobi")
        try { file.writeBytes(pdb); block(file) } finally { file.delete() }
    }

    private fun huffRecords(text: ByteArray, nested: Boolean, long: Boolean): List<ByteArray> {
        val huff = ByteArray(1304)
        "HUFF".toByteArray().copyInto(huff)
        put32(huff, 4, 24)
        put32(huff, 8, 24)
        put32(huff, 12, 1048)
        repeat(256) { index -> put32(huff, 24 + index * 4, ((if (nested && index == 1) 2 else index) shl 8) or 0x88) }
        if (long) {
            put32(huff, 24, 9)
            put32(huff, 1048 + 8 * 8, 1) // Code 0 falls below the nine-bit minimum and uses ten bits.
        }
        val phrases = if (nested) listOf(byteArrayOf(1) to false, text to true) else listOf(text to true)
        val cdic = ByteArray(16 + phrases.size * 2 + phrases.sumOf { it.first.size + 2 })
        "CDIC".toByteArray().copyInto(cdic)
        put32(cdic, 4, 16)
        put32(cdic, 8, phrases.size)
        put32(cdic, 12, if (nested) 1 else 0)
        var offset = 16 + phrases.size * 2
        phrases.forEachIndexed { index, (bytes, literal) ->
            put16(cdic, 16 + index * 2, offset - 16)
            put16(cdic, offset, bytes.size or if (literal) 0x8000 else 0)
            bytes.copyInto(cdic, offset + 2)
            offset += bytes.size + 2
        }
        return listOf(huff, cdic)
    }

    private fun put16(bytes: ByteArray, offset: Int, value: Int) { ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.BIG_ENDIAN).putShort(value.toShort()) }
    private fun put32(bytes: ByteArray, offset: Int, value: Int) { ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).putInt(value) }
    private val pngHeader = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
}
