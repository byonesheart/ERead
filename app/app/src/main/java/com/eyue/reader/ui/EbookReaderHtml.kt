package com.eyue.reader.ui

import androidx.core.text.HtmlCompat
import com.eyue.reader.data.EbookSourceDocument
import com.eyue.reader.data.SourceHtmlTag
import com.eyue.reader.data.sourceHtmlAttribute
import com.eyue.reader.data.sourceHtmlTags
import org.json.JSONObject

private val ebookBlockedContainers = setOf("script", "iframe", "frameset", "object", "applet", "portal")
private val ebookBlockedTags = setOf("base", "frame", "embed", "param")
private val ebookUrlAttributes = setOf("href", "xlink:href", "src", "action", "formaction", "poster", "background", "data", "codebase")
private val ebookAttributes = Regex("\\s+([A-Za-z_:][\\w:.-]*)(?:\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+))?")

/** An ebook supplies markup and CSS; scripts and the application bridge belong only to the reader. */
internal fun ebookReaderHtml(source: EbookSourceDocument, script: String, nonce: String, initialOptions: JSONObject? = null,
    allowReaderFrames: Boolean = false): String {
    require(nonce.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid reader script nonce" }
    val policy = "default-src 'none'; img-src https://eread.local data:; style-src https://eread.local 'unsafe-inline'; " +
        "font-src https://eread.local data:; script-src 'nonce-$nonce'; connect-src 'none'; frame-src ${if (allowReaderFrames) "https://eread.local" else "'none'"}; " +
        "object-src 'none'; form-action 'none'; base-uri 'none'"
    val trustedHead = "<meta charset=\"UTF-8\"><meta http-equiv=\"Content-Security-Policy\" content=\"$policy\">" +
        "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no\">" +
        "<style>html[data-eread-loading] body{opacity:0!important}</style>"
    val clean = ebookSanitizePublisherHtml(source.html)
    val tags = sourceHtmlTags(clean).toList()
    val html = tags.firstOrNull { !it.closing && it.name == "html" }
    val body = tags.firstOrNull { !it.closing && it.name == "body" }
    val head = tags.firstOrNull { !it.closing && it.name == "head" }
    val headEnd = head?.let { opening -> tags.firstOrNull { it.closing && it.name == "head" && it.start >= opening.end }
        ?.let { it.start to it.end } ?: (body?.start?.takeIf { it >= opening.end } ?: clean.length).let { it to it } }
    val publisherHead = if (head != null && headEnd != null) clean.substring(head.end, headEnd.first) else ""
    val content = buildString(clean.length) {
        var cursor = 0
        tags.forEach { tag ->
            if (head != null && headEnd != null && tag.start in head.start until headEnd.second) {
                if (cursor <= head.start) { append(clean, cursor, head.start); cursor = headEnd.second }
            } else if (tag.name in setOf("html", "head", "body")) {
                if (tag.start >= cursor) { append(clean, cursor, tag.start); cursor = tag.end }
            }
        }
        append(clean, cursor, clean.length)
    }
    // Rebuild only the envelope: the first active head always contains our CSP,
    // including when comments/styles contain fake heads or content precedes the head.
    return buildString(clean.length + trustedHead.length + script.length + 128) {
        append("<!doctype html>")
        append(html?.let { clean.substring(it.start, it.end - 1) + " data-eread-loading>" } ?: "<html data-eread-loading>")
        append(head?.let { clean.substring(it.start, it.end) } ?: "<head>")
        append(trustedHead).append(publisherHead).append("</head>")
        append(body?.let { clean.substring(it.start, it.end) } ?: "<body>")
        append(content)
        append("<script nonce=\"").append(nonce).append("\">").append(script)
        // Start from the trusted document itself. Waiting for onPageFinished can
        // leave a locally served chapter waiting on unrelated resource callbacks.
        initialOptions?.let {
            val options = it.toString().replace("<", "\\u003c").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
            append("\n;window.ERead.init(").append(options).append(");")
        }
        append("</script>")
        append("</body></html>")
    }
}

private fun ebookSanitizePublisherHtml(html: String): String {
    val result = StringBuilder(html.length)
    var cursor = 0
    var blocked: SourceHtmlTag? = null
    var depth = 0
    sourceHtmlTags(html).forEach { tag ->
        val name = tag.name.substringAfterLast(':')
        val active = blocked
        if (active != null) {
            if (tag.name == active.name) {
                if (!tag.closing && !tag.selfClosing) depth++
                else if (tag.closing && --depth == 0) { cursor = tag.end; blocked = null }
            }
        } else if (!tag.closing && name in ebookBlockedContainers) {
            result.append(html, cursor, tag.start)
            if (tag.selfClosing) cursor = tag.end else { blocked = tag; depth = 1 }
        } else {
            val drop = name in ebookBlockedTags || name in ebookBlockedContainers || name == "meta" && (
                sourceHtmlAttribute(tag.attributes, "http-equiv") != null ||
                    sourceHtmlAttribute(tag.attributes, "charset") != null ||
                    sourceHtmlAttribute(tag.attributes, "name")?.equals("viewport", true) == true)
            if (drop || !tag.closing) {
                result.append(html, cursor, tag.start)
                if (!drop) {
                    val attributes = ebookAttributes.replace(tag.attributes) { match ->
                        val attribute = match.groupValues[1].lowercase()
                        val value = match.groupValues[2].removeSurrounding("\"").removeSurrounding("'")
                        if (attribute.startsWith("on") || attribute in setOf("nonce", "srcdoc") ||
                            attribute in ebookUrlAttributes && ebookUnsafeUrl(value)) "" else match.value
                    }
                    result.append('<').append(tag.name).append(attributes).append('>')
                }
                cursor = tag.end
            }
        }
    }
    if (blocked == null) result.append(html, cursor, html.length)
    return result.toString()
}

private fun ebookUnsafeUrl(value: String): Boolean {
    val decoded = HtmlCompat.fromHtml(value.replace("<", "&lt;"), HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
        .filterNot { it.isWhitespace() || it.code <= 0x20 || it.code == 0x7f }.lowercase()
    return decoded.startsWith("javascript:") || decoded.startsWith("vbscript:") || decoded.startsWith("file:") ||
        decoded.startsWith("content:") || decoded.startsWith("intent:") || decoded.startsWith("android-app:") ||
        decoded.startsWith("data:") && !decoded.startsWith("data:image/")
}
