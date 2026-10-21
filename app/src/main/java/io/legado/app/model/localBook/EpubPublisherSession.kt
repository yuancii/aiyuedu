package io.legado.app.model.localBook

import android.webkit.WebResourceResponse
import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookHelp
import me.ag2s.epublib.util.zip.AndroidZipFile
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.UUID

/** Owns a separate ZIP handle so native parser cache eviction cannot invalidate a WebView. */
class EpubPublisherSession(book: Book) : AutoCloseable {
    private val descriptor = requireNotNull(BookHelp.getBookPFD(book)) { "Cannot open EPUB" }
    private val zip = try { AndroidZipFile(descriptor, book.originName) }
        catch (error: Throwable) { descriptor.close(); throw error }
    private val token = UUID.randomUUID().toString()
    private val origin = "https://epub-reader.invalid/$token/"
    private var closed = false
    val packagePath: String
    val spine: List<String>
    val fixedLayout: Boolean
    private val documents: Set<String>
    private val mimeTypes: Map<String, String>

    init {
        try {
            val container = xml("META-INF/container.xml")
            packagePath = requireNotNull(container.allElements.firstOrNull {
                it.tagName().substringAfter(':') == "rootfile"
            }?.attr("full-path")
                ?.let { resolveEpubResource("", it) }) { "EPUB package missing" }
            val opf = xml(packagePath)
            val items = opf.allElements.filter { it.tagName().substringAfter(':') == "item" }
            val manifest = items
                .associate { it.attr("id") to resolveEpubResource(packagePath, it.attr("href")) }
            documents = items.filter { it.attr("media-type") in setOf("application/xhtml+xml", "text/html") }
                .mapNotNull { manifest[it.attr("id")] }.toSet()
            mimeTypes = items.mapNotNull { item -> manifest[item.attr("id")]?.let { path ->
                item.attr("media-type").takeIf(String::isNotBlank)?.let { path to it }
            } }.toMap()
            spine = opf.allElements.filter { it.tagName().substringAfter(':') == "itemref" }
                .mapNotNull { manifest[it.attr("idref")] }.distinct()
            require(spine.isNotEmpty()) { "EPUB reading order is empty" }
            fixedLayout = opf.allElements.any {
                it.attr("property") == "rendition:layout" && it.text() == "pre-paginated"
            }
        } catch (error: Throwable) { close(); throw error }
    }

    private fun xml(path: String) = bytes(path).inputStream().use {
        Jsoup.parse(it, null, "", Parser.xmlParser())
    }

    private fun bytes(path: String): ByteArray {
        check(!closed)
        val entry = requireNotNull(zip.getEntry(path)) { "EPUB resource not found: $path" }
        return zip.getInputStream(entry).use { it.readBytes() }
    }

    fun pathForChapter(href: String): String? = resolveEpubChapter(packagePath, href, documents)

    fun isDocument(path: String) = path in documents

    /** A native chapter may concatenate several spine documents. Find the visible text's source. */
    @Synchronized
    fun documentForQuote(startPath: String, nextChapterPath: String?, quote: String): String {
        val needle = quote.filterNot(Char::isWhitespace)
        val start = spine.indexOf(startPath)
        if (needle.length < 8 || start < 0) return startPath
        val next = nextChapterPath?.let(spine::indexOf)?.takeIf { it >= start }
        val end = if (next == start) start + 1 else next ?: spine.size
        for (path in spine.subList(start, end)) {
            val text = bytes(path).inputStream().use { Jsoup.parse(it, null, "").body().text() }
            if (text.filterNot(Char::isWhitespace).contains(needle)) return path
        }
        return startPath
    }

    fun url(path: String, fragment: String? = null): String = origin +
        URI(null, null, path, null).rawPath +
        fragment?.takeIf(String::isNotBlank)?.let { "#" + URI(null, null, "", it).rawFragment }.orEmpty()

    fun pathForUrl(url: String): String? = runCatching {
        val uri = URI(url)
        if (uri.scheme != "https" || uri.host != "epub-reader.invalid" || uri.port != -1 ||
            uri.rawUserInfo != null || !uri.rawPath.startsWith("/$token/")) return null
        resolveEpubResource("", uri.rawPath.removePrefix("/$token/"))
    }.getOrNull()

    @Synchronized
    fun response(url: String): WebResourceResponse {
        val path = pathForUrl(url) ?: return blocked()
        return try {
            val mime = mimeTypes[path] ?: mime(path)
            val data = bytes(path).let { if (mime == "text/html" || mime == "application/xhtml+xml") publisherDocument(it) else it }
            WebResourceResponse(if (mime == "application/xhtml+xml") "text/html" else mime,
                if (mime == "text/html" || mime == "application/xhtml+xml") "UTF-8" else null,
                200, "OK", mapOf(
                    "Content-Security-Policy" to "default-src 'self' data:; script-src 'none'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; connect-src 'none'; frame-src 'none'; form-action 'none'; base-uri 'none'",
                    "X-Content-Type-Options" to "nosniff"
                ), ByteArrayInputStream(data))
        } catch (_: Exception) { blocked() }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try { zip.close() } finally { descriptor.close() }
    }

    companion object {
        fun blocked() = WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
            emptyMap(), ByteArrayInputStream(ByteArray(0)))

        private fun mime(path: String) = when (path.substringAfterLast('.').lowercase()) {
            "html", "htm" -> "text/html"
            "xhtml" -> "application/xhtml+xml"
            "css" -> "text/css"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            else -> "application/octet-stream"
        }
    }
}
