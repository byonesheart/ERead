package com.eyue.reader.data

import android.content.Context
import com.eyue.reader.R
import java.io.File
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object EpubFixtures {
    fun create(context: Context, directory: File, name: String, version: String = "3.0", oneSpine: Boolean = false, utf16: Boolean = false,
                        fixed: Boolean = false, missingSpine: Boolean = false, imageOnly: Boolean = false,
                        paragraphs: Int = 0, footnotes: Boolean = false): File {
        val epub3 = version.startsWith('3')
        val nav = if (epub3) """<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>"""
            else """<item id="nav" href="toc.ncx" media-type="application/x-dtbncx+xml"/>"""
        val spine = if (oneSpine) """<itemref idref="body"/>""" else """<itemref idref="body"/><itemref idref="second"/>"""
        val chapters = """<item id="body" href="${if (missingSpine) "missing.xhtml" else "body.xhtml"}" media-type="application/xhtml+xml"/>
            <item id="second" href="second.xhtml" media-type="application/xhtml+xml"/>
            <item id="css" href="style.css" media-type="text/css"/><item id="font" href="font.otf" media-type="font/otf"/>
            <item id="image" href="image.png" media-type="image/png"/>"""
        val packageXml = """<?xml version="1.0" encoding="UTF-8"?><package xmlns="http://www.idpf.org/2007/opf" version="$version" unique-identifier="bookid"
            xmlns:dc="http://purl.org/dc/elements/1.1/"><metadata><dc:identifier id="bookid">urn:uuid:$name</dc:identifier>
            <dc:title>标准 EPUB $name</dc:title><dc:creator>测试作者</dc:creator><dc:language>zh-CN</dc:language>
            ${if (fixed) """<meta property="rendition:layout">pre-paginated</meta>""" else ""}
            ${if (epub3) """<meta property="dcterms:modified">2026-10-24T00:00:00Z</meta>""" else ""}</metadata>
            <manifest>$nav$chapters</manifest><spine toc="nav">$spine</spine></package>""".trimIndent()
        val prose = (0 until paragraphs).joinToString("") { "<p id=\"paragraph-$it\">段落_${it}_锚点：图文混排、标题断页和章节切换应保留原始结构。阅读位置、批注与朗读使用同一规范文本索引。</p>" }
        val notes = if (footnotes) """<p>注释正文<a epub:type="noteref" href="#fn1" id="noteref1">[1]</a></p><aside epub:type="footnote" role="doc-footnote" id="fn1"><p>标准 EPUB 注释正文</p></aside>""" else ""
        val body = """<?xml version="1.0" encoding="${if (utf16) "UTF-16" else "UTF-8"}"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>正文</title>
            <link rel="stylesheet" type="text/css" href="style.css"/><meta name="viewport" content="width=600,height=800"/></head><body>
            ${if (imageOnly) """<div id="one"><img src="image.png" alt=""/></div>""" else """<h1 id="one">开篇</h1><p>正文甲</p><img src="image.png" alt=""/>"""}
            ${if (imageOnly) "" else prose + notes}
            ${if (oneSpine && !imageOnly) """<h2 id="two">第二节</h2><p>正文乙</p>""" else ""}</body></html>""".trimIndent()
        val entries = linkedMapOf(
            "META-INF/container.xml" to """<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="OPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray(),
            "OPS/package.opf" to packageXml.toByteArray(),
            "OPS/body.xhtml" to body.toByteArray(if (utf16) Charsets.UTF_16 else Charsets.UTF_8),
            "OPS/second.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>继续</title></head><body>${if (imageOnly) "" else "<p>继续正文</p>"}</body></html>""".toByteArray(),
            "OPS/style.css" to "@font-face{font-family:Fixture;src:url(font.otf)} h1{break-before:page} p{font-family:Fixture} img{max-width:100%}".toByteArray(),
            "OPS/font.otf" to context.resources.openRawResource(R.font.reader_hei).use { it.readBytes() },
            "OPS/image.png" to Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGPQrdj5HwAEkgJeUZdzEAAAAABJRU5ErkJggg==")
        )
        if (epub3) entries["OPS/nav.xhtml"] = """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>目录</title></head><body><nav epub:type="toc"><ol><li><a href="body.xhtml#one">开篇</a>
            ${if (oneSpine) """<ol><li><a href="body.xhtml#two">第二节</a></li></ol>""" else ""}</li>
            ${if (!oneSpine) """<li><a href="second.xhtml">继续</a></li>""" else ""}</ol></nav></body></html>""".toByteArray()
        else entries["OPS/toc.ncx"] = """<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head/><docTitle><text>目录</text></docTitle><navMap>
            <navPoint id="one" playOrder="1"><navLabel><text>开篇</text></navLabel><content src="body.xhtml#one"/></navPoint>
            <navPoint id="second" playOrder="2"><navLabel><text>继续</text></navLabel><content src="second.xhtml"/></navPoint></navMap></ncx>""".toByteArray()
        return File(directory, "$name.epub").apply {
            ZipOutputStream(outputStream()).use { zip ->
                val mimetype = "application/epub+zip".toByteArray()
                zip.putNextEntry(ZipEntry("mimetype").apply { method = ZipEntry.STORED; size = mimetype.size.toLong(); compressedSize = size; crc = CRC32().apply { update(mimetype) }.value })
                zip.write(mimetype); zip.closeEntry()
                entries.forEach { (path, bytes) -> zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry() }
            }
        }
    }
}
