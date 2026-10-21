package io.legado.app.model.localBook

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URI

/** Decoded ZIP entry names only: never allow a reference to leave the publication. */
internal fun resolveEpubResource(base: String, reference: String): String? = runCatching {
    val uri = URI(reference.trim().replace(" ", "%20"))
    if (uri.isAbsolute || uri.rawAuthority != null || '\\' in reference) return null
    val path = uri.path.orEmpty()
    if (path.startsWith('/')) return null
    val joined = if (path.isEmpty()) base else base.substringBeforeLast('/', "")
        .let { if (it.isEmpty()) path else "$it/$path" }
    val parts = ArrayDeque<String>()
    for (part in joined.split('/')) {
        when (part) {
            "", "." -> Unit
            ".." -> if (parts.isEmpty()) return null else parts.removeLast()
            else -> {
                if (part.any { it == '\\' || it == '\u0000' || it.code < 32 }) return null
                parts.addLast(part)
            }
        }
    }
    parts.joinToString("/").takeIf(String::isNotEmpty)
}.getOrNull()

/** epublib stores root-relative decoded hrefs; older imports may retain OPF-relative hrefs. */
internal fun resolveEpubChapter(packagePath: String, href: String, documents: Set<String>): String? =
    href.substringBefore('#').takeIf { it in documents }
        ?: resolveEpubResource("", href)?.takeIf { it in documents }
        ?: resolveEpubResource(packagePath, href)?.takeIf { it in documents }

internal fun epubFragment(href: String): String? = href.substringAfter('#', "")
    .takeIf(String::isNotEmpty)?.let { fragment ->
        runCatching { URI("#" + fragment.replace(" ", "%20")).fragment }.getOrDefault(fragment)
    }

/** Keep publisher styles, links, SVG and fonts; book-supplied executable content is removed. */
internal fun publisherDocument(bytes: ByteArray): ByteArray {
    val document = bytes.inputStream().use { Jsoup.parse(it, null, "", Parser.xmlParser()) }
    document.outputSettings().syntax(org.jsoup.nodes.Document.OutputSettings.Syntax.html)
        .charset(Charsets.UTF_8).prettyPrint(false)
    document.select("script, iframe, frame, frameset, object, embed, applet, base").remove()
    document.select("style").forEach { style ->
        val css = style.data().ifEmpty { style.wholeText() }
        style.empty().appendChild(org.jsoup.nodes.DataNode(css))
    }
    document.select("meta[http-equiv]").remove()
    document.allElements.forEach { element ->
        element.attributes().asList().forEach { attribute ->
            val name = attribute.key.lowercase()
            val value = attribute.value.filterNot(Char::isWhitespace).lowercase()
            if (name.startsWith("on") || name == "srcdoc" ||
                value.startsWith("javascript:") || value.startsWith("vbscript:")
            ) element.removeAttr(attribute.key)
        }
    }
    document.select("meta[charset]").remove()
    val head = document.selectFirst("head") ?: document.selectFirst("html")?.prependElement("head")
    head?.prependElement("meta")?.attr("charset", "utf-8")
    if (document.select("meta[name=viewport]").isEmpty()) {
        head?.appendElement("meta")?.attr("name", "viewport")
            ?.attr("content", "width=device-width, initial-scale=1.0")
    }
    return document.outerHtml().toByteArray(Charsets.UTF_8)
}

/** Quote-based mapping tolerates native title insertion and whitespace normalization. */
internal fun epubTextPosition(text: String, quote: String, fraction: Double): Int {
    val progress = fraction.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
    if (quote.isNotBlank()) {
        val compact = StringBuilder()
        val offsets = ArrayList<Int>()
        text.forEachIndexed { index, char ->
            if (!char.isWhitespace()) { compact.append(char); offsets.add(index) }
        }
        val needle = quote.filterNot(Char::isWhitespace)
        if (needle.isNotEmpty()) {
            val expected = (progress * compact.length).toInt()
            var match = compact.indexOf(needle)
            var best = -1
            while (match >= 0) {
                if (best < 0 || kotlin.math.abs(match - expected) < kotlin.math.abs(best - expected)) best = match
                match = compact.indexOf(needle, match + 1)
            }
            if (best >= 0) return offsets[best]
        }
    }
    return (progress * text.length).toInt()
}
