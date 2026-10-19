package com.eyue.reader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubArchiveTest {
    @Test fun indexesSameFileAndCrossFileNotesWithoutAddingChaptersOrFollowingExternalLinks() {
        val body = "<html><body><p>正文<a epub:type='noteref' href='#fn1'>[1]</a>" +
            "<a role='doc-noteref' href='notes.xhtml#fn2'>②</a><a href='#next'>下一章</a>" +
            "<a epub:type='noteref' href='https://example.invalid/#fn3'>[3]</a></p>" +
            "<aside id='fn1' role='doc-footnote'>同文件注释</aside><h2 id='next'>继续正文</h2></body></html>"
        val file = archive(mapOf(
            "META-INF/container.xml" to "<container><rootfile full-path='book.opf'/></container>",
            "book.opf" to "<package><manifest><item id='body' href='body.xhtml'/><item id='notes' href='notes.xhtml'/></manifest><spine><itemref idref='body'/></spine></package>",
            "body.xhtml" to body,
            "notes.xhtml" to "<html><body><aside id='fn2' role='doc-endnote'><p>跨文件注释</p><a role='doc-backlink' href='body.xhtml#ref2'>返回</a></aside></body></html>"))
        try {
            val section = EpubArchive.read(file).sections.single()
            assertEquals(body, section.html)
            assertEquals(setOf("#fn1", "notes.xhtml#fn2"), section.sourceNoteTargets.keys)
            assertTrue(section.sourceNoteTargets.getValue("#fn1").contains("同文件注释"))
            assertTrue(section.sourceNoteTargets.getValue("notes.xhtml#fn2").contains("跨文件注释"))
        } finally { file.delete() }
    }

    @Test fun nestedNavKeepsTheOutermostParentWithoutChangingAnchorBoundaries() {
        val body = """<html><body><h1 id="volume">上卷</h1><p>卷首</p><h2 id="one">第一章</h2><p>正文一</p><h3 id="detail">第一节</h3><p>正文二</p><h1 id="last">尾声</h1><p>结尾</p></body></html>"""
        val file = noteArchive("""<html xmlns:epub="http://www.idpf.org/2007/ops"><nav epub:type="toc"><ol><li><a href="body.xhtml#volume">上卷</a><ol><li><a href="body.xhtml#one">第一章</a><ol><li><a href="body.xhtml#detail">第一节</a></li></ol></li></ol></li><li><a href="body.xhtml#last">尾声</a></li></ol></nav></html>""", body)
        try {
            val sections = EpubArchive.read(file).sections
            assertEquals(listOf("上卷", "第一章", "第一节", "尾声"), sections.map { it.title })
            assertEquals(listOf(null, "上卷", "上卷", null), sections.map { it.tocParentTitle })
            assertEquals(listOf(0, body.indexOf("<h2"), body.indexOf("<h3"), body.indexOf("<h1 id=\"last\"")), sections.map { it.sourceStart })
            assertEquals(body.length, sections.last().sourceEnd)
            assertTrue(sections[1].html.contains("正文一") && !sections[1].html.contains("正文二"))
        } finally { file.delete() }
    }

    @Test fun spanNavParentIsRetainedForSeparateSpineFiles() {
        val file = archive(mapOf(
            "META-INF/container.xml" to "<container><rootfile full-path=\"book.opf\"/></container>",
            "book.opf" to """<package><manifest><item id="a" href="a.xhtml"/><item id="b" href="b.xhtml"/><item id="nav" href="nav.xhtml" properties="nav"/></manifest><spine><itemref idref="a"/><itemref idref="b"/></spine></package>""",
            "nav.xhtml" to """<html><nav><ol><li><span>第一部</span><ol><li><a href="a.xhtml">开篇</a></li><li><a href="b.xhtml">相遇</a></li></ol></li></ol></nav></html>""",
            "a.xhtml" to "<html><body><p>正文一</p></body></html>",
            "b.xhtml" to "<html><body><p>正文二</p></body></html>"))
        try {
            val sections = EpubArchive.read(file).sections
            assertEquals(listOf("开篇", "相遇"), sections.map { it.title })
            assertEquals(listOf("第一部", "第一部"), sections.map { it.tocParentTitle })
            assertEquals(listOf("a.xhtml", "b.xhtml"), sections.map { it.path })
        } finally { file.delete() }
    }

    @Test fun nestedNcxKeepsTheOutermostParentAndPhysicalSpineOrder() {
        val file = archive(mapOf(
            "META-INF/container.xml" to "<container><rootfile full-path=\"book.opf\"/></container>",
            "book.opf" to """<package><manifest><item id="v" href="v.xhtml"/><item id="a" href="a.xhtml"/><item id="b" href="b.xhtml"/><item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/></manifest><spine toc="ncx"><itemref idref="v"/><itemref idref="a"/><itemref idref="b"/></spine></package>""",
            "toc.ncx" to """<ncx><navMap><navPoint><navLabel><text>卷一</text></navLabel><content src="v.xhtml"/><navPoint><navLabel><text>开篇</text></navLabel><content src="a.xhtml"/><navPoint><navLabel><text>小节</text></navLabel><content src="b.xhtml"/></navPoint></navPoint></navPoint></navMap></ncx>""",
            "v.xhtml" to "<html><body>卷首</body></html>",
            "a.xhtml" to "<html><body>正文一</body></html>",
            "b.xhtml" to "<html><body>正文二</body></html>"))
        try {
            val sections = EpubArchive.read(file).sections
            assertEquals(listOf("卷一", "开篇", "小节"), sections.map { it.title })
            assertEquals(listOf(null, "卷一", "卷一"), sections.map { it.tocParentTitle })
            assertEquals(listOf("v.xhtml", "a.xhtml", "b.xhtml"), sections.map { it.path })
        } finally { file.delete() }
    }

    @Test fun ignoresNotesNavigationAndCommentedAnchorCopies() {
        val file = noteArchive("""<html xmlns:epub="http://www.idpf.org/2007/ops"><nav epub:type="endnotes"><a href="body.xhtml#note">译者说明</a></nav><nav><a href="body.xhtml#one">第一章</a><a href="body.xhtml#two">第二章</a></nav></html>""",
            """<html><body><!--<h1 id="two">注释中的假标题</h1>--><h1 id="one">第一章</h1><p>正文一</p><h1 id="two">第二章</h1><p>正文二</p><aside id="note" role="doc-endnote">保留解释</aside></body></html>""")
        try {
            val sections = EpubArchive.read(file).sections
            assertEquals(listOf("第一章", "第二章"), sections.map { it.title })
            assertTrue(sections.first().html.contains("正文一"))
            assertTrue(!sections.first().html.contains("正文二"))
            assertTrue(sections.last().html.contains("保留解释"))
        } finally { file.delete() }
    }

    @Test fun tocReferencesToSemanticNotesDoNotNameOrSplitTheBody() {
        val file = noteArchive("""<html xmlns:epub="http://www.idpf.org/2007/ops"><nav epub:type="toc"><a href="body.xhtml#note">译者说明</a><a href="body.xhtml#one">第一章</a><a href="body.xhtml#other" epub:type="noteref">[1]</a></nav></html>""",
            """<html><body><aside id="note" role="doc-footnote"><h1>译者说明</h1>解释</aside><h1 id="one">第一章</h1><p>正文</p><aside id="other" class="footnote">注解</aside></body></html>""")
        try {
            assertEquals(listOf("第一章"), EpubArchive.read(file).sections.map { it.title })
        } finally { file.delete() }
    }

    @Test fun notesOnlyNavigationCannotBeUsedAsTheChapterTitle() {
        val file = noteArchive("""<html xmlns:epub="http://www.idpf.org/2007/ops"><nav epub:type="footnotes"><a href="body.xhtml#note">译者说明</a></nav></html>""",
            """<html><body><h1>正文</h1><p>正文内容</p><aside id="note" role="doc-footnote">解释</aside></body></html>""")
        try { assertEquals("第 1 章", EpubArchive.read(file).sections.single().title) }
        finally { file.delete() }
    }

    private fun noteArchive(nav: String, body: String) = archive(mapOf(
        "META-INF/container.xml" to "<container><rootfile full-path=\"book.opf\"/></container>",
        "book.opf" to """<package><metadata><title>注释目录回归</title></metadata><manifest><item id="body" href="body.xhtml"/><item id="nav" href="nav.xhtml" properties="nav"/></manifest><spine><itemref idref="body"/></spine></package>""",
        "nav.xhtml" to nav, "body.xhtml" to body))

    @Test fun parsesMetadataSpineNavAndCoverFromRealZip() {
        val file = archive(mapOf(
            "META-INF/container.xml" to """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/book.opf"/></rootfiles></container>""",
            "OPS/book.opf" to """<package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/"><metadata><dc:title>春日书</dc:title><dc:creator>张青</dc:creator></metadata><manifest><item id="b" href="chapters/second.xhtml" media-type="application/xhtml+xml"/><item id="a" href="chapters/first.xhtml" media-type="application/xhtml+xml"/><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="cover" href="images/cover.png" media-type="image/png" properties="cover-image"/></manifest><spine><itemref idref="a"/><itemref idref="b"/></spine></package>""",
            "OPS/nav.xhtml" to """<!DOCTYPE html><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol><li><a href="chapters/first.xhtml#start">春风</a></li><li><a href="chapters/second.xhtml">春雨</a></li></ol></nav></body></html>""",
            "OPS/chapters/first.xhtml" to "<html><body><p>第一章正文</p></body></html>",
            "OPS/chapters/second.xhtml" to "<html><body><p>第二章正文</p></body></html>",
            "OPS/images/cover.png" to "test-image"
        ))
        try {
            val book = EpubArchive.read(file)
            assertEquals("春日书", book.title)
            assertEquals("张青", book.author)
            assertEquals(listOf("春风", "春雨"), book.sections.map { it.title })
            assertTrue(book.sections.all { it.tocParentTitle == null })
            assertTrue(book.sections.first().html.contains("第一章正文"))
            assertEquals("OPS/images/cover.png", book.coverPath)
        } finally { file.delete() }
    }

    @Test fun supportsNcxAndBlankAuthor() {
        val file = archive(mapOf(
            "META-INF/container.xml" to "<container><rootfiles><rootfile full-path=\"book.opf\"/></rootfiles></container>",
            "book.opf" to """<package><metadata><title>匿名书</title></metadata><manifest><item id="a" href="a.xhtml"/><item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/></manifest><spine toc="ncx"><itemref idref="a"/></spine></package>""",
            "a.xhtml" to "<html><body>正文</body></html>",
            "toc.ncx" to """<ncx><navMap><navPoint><navLabel><text>第一回</text></navLabel><content src="a.xhtml#part"/></navPoint></navMap></ncx>"""
        ))
        try {
            val book = EpubArchive.read(file)
            assertEquals("", book.author)
            assertEquals("第一回", book.sections.single().title)
            assertNull(book.sections.single().tocParentTitle)
            assertNull(book.coverPath)
        } finally { file.delete() }
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsZipTraversalBeforeParsing() {
        val file = archive(mapOf("../evil.txt" to "bad"))
        try { EpubArchive.read(file) } finally { file.delete() }
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsXmlEntityDeclarations() {
        val file = archive(mapOf("META-INF/container.xml" to
            """<!DOCTYPE container [<!ENTITY secret SYSTEM "file:///private.txt">]><container><rootfile full-path="&secret;"/></container>"""))
        try { EpubArchive.read(file) } finally { file.delete() }
    }

    @Test fun splitsMultipleTocAnchorsInsideOneSpineDocument() {
        val file = archive(mapOf(
            "META-INF/container.xml" to "<container><rootfile full-path=\"book.opf\"/></container>",
            "book.opf" to """<package><metadata><title>长篇</title></metadata><manifest><item id="body" href="body.xhtml"/><item id="nav" href="nav.xhtml" properties="nav"/></manifest><spine><itemref idref="body"/></spine></package>""",
            "nav.xhtml" to """<html><nav><a href="body.xhtml#one">第一章</a><a href="body.xhtml#two">第二章</a></nav></html>""",
            "body.xhtml" to """<html><head><link rel="stylesheet" href="styles.css"/><style>h1{text-align:center}</style></head><body style="font-size:20px"><h1 id="one">第一章</h1><p>正文一</p><h1 id="two">第二章</h1><p>正文二</p></body></html>""",
            "styles.css" to "@import 'sizes.css'; h1{font-weight:normal}",
            "sizes.css" to "h1{font-size:30px}"
        ))
        try {
            val updates = mutableListOf<Float>()
            val sections = EpubArchive.read(file, onProgress = updates::add).sections
            assertEquals(0f, updates.first(), 0f)
            assertEquals(1f, updates.last(), 0f)
            assertTrue(updates.zipWithNext().all { (before, after) -> after >= before })
            assertEquals(listOf("第一章", "第二章"), sections.map { it.title })
            assertTrue(sections[0].html.contains("正文一"))
            assertTrue(!sections[0].html.contains("正文二"))
            assertTrue(sections[1].html.contains("正文二"))
            sections.forEach { section ->
                val heading = SourceStyleParser.mark(section.html, section.css, "test_").styles.single()
                assertEquals(1.5f, heading.fontScale!!, .001f)
                assertEquals(SourceTextAlignment.CENTER, heading.alignment)
                assertEquals(false, heading.bold)
            }
        } finally { file.delete() }
    }

    @Test fun resolvesParentRelativeAndEncodedReferences() {
        assertEquals("OPS/images/my cover.png", EpubArchive.resolve("OPS/chapters/a.xhtml", "../images/my%20cover.png#cover"))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsExternalReference() {
        EpubArchive.resolve("OPS/a.xhtml", "https://example.com/image.jpg")
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsEscapingRelativeReference() {
        EpubArchive.resolve("a.xhtml", "../image.jpg")
    }

    private fun archive(entries: Map<String, String>): File {
        val file = File.createTempFile("eyue-epub-test-", ".epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, contents) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(contents.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return file
    }
}
