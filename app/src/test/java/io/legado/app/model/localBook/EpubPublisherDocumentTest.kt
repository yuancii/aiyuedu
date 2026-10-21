package io.legado.app.model.localBook

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class EpubPublisherDocumentTest {
    @Test
    fun resolvesRelativeStylesFontsAndUnicodeWithoutTreatingPlusAsSpace() {
        assertEquals("OPS/styles/main.css", resolveEpubResource("OPS/Text/chapter.xhtml", "../styles/main.css"))
        assertEquals("OPS/Fonts/字体+a.woff2", resolveEpubResource("OPS/Text/a.xhtml", "../Fonts/%E5%AD%97%E4%BD%93+a.woff2?v=1"))
        assertEquals("OPS/Text/chapter.xhtml", resolveEpubResource("OPS/Text/chapter.xhtml", "#note"))
        assertEquals("OPS/book.opf", resolveEpubResource("", "OPS/book.opf"))
    }

    @Test
    fun rejectsExternalResourcesAndEncodedArchiveEscapes() {
        for (reference in listOf("https://example.com/a", "//example.com/a", "file:///private/a",
            "../../private", "%2e%2e/%2e%2e/private", "%2fprivate", "..%5cprivate", "a%00.css")) {
            assertNull(reference, resolveEpubResource("OPS/book.opf", reference))
        }
    }

    @Test
    fun mapsExistingNativeChapterIdentitiesWithoutDuplicatingPackageDirectory() {
        val documents = setOf("OEBPS/Text/chapter.xhtml", "OEBPS/Text/第 1章.xhtml", "OEBPS/Text/100%.xhtml")
        assertEquals("OEBPS/Text/chapter.xhtml", resolveEpubChapter("OEBPS/content.opf", "OEBPS/Text/chapter.xhtml#part", documents))
        assertEquals("OEBPS/Text/chapter.xhtml", resolveEpubChapter("OEBPS/content.opf", "Text/chapter.xhtml#part", documents))
        assertEquals("OEBPS/Text/第 1章.xhtml", resolveEpubChapter("OEBPS/content.opf", "OEBPS/Text/第 1章.xhtml", documents))
        assertEquals("OEBPS/Text/第 1章.xhtml", resolveEpubChapter("OEBPS/content.opf", "Text/%E7%AC%AC%201%E7%AB%A0.xhtml", documents))
        assertEquals("OEBPS/Text/100%.xhtml", resolveEpubChapter("OEBPS/content.opf", "OEBPS/Text/100%.xhtml", documents))
        assertNull(resolveEpubChapter("OEBPS/content.opf", "https://example.com/a", documents))
        assertEquals("第 1章+a", epubFragment("OEBPS/Text/第 1章.xhtml#%E7%AC%AC%201%E7%AB%A0+a"))
        assertNull(epubFragment("OEBPS/Text/chapter.xhtml"))
    }

    @Test
    fun preservesPublisherCssFontsTablesSvgAndResourcePaths() {
        val original = """<?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml"><head>
            <link rel="stylesheet" href="../style.css"/>
            <style>@font-face {font-family:book;src:url('../font.ttf')} p {color:red}</style>
            <meta name="viewport" content="width=800,height=1200"/>
            </head><body class="book"><table><tr><td>原文</td></tr></table>
            <svg xmlns="http://www.w3.org/2000/svg"><image href="../cover.svg"/></svg>
            <p id="chapter" style="text-indent:2em">正文 <ruby>字<rt>zi</rt></ruby></p>
            </body></html>""".trimIndent()
        val doc = Jsoup.parse(publisherDocument(original.toByteArray()).toString(Charsets.UTF_8))
        assertEquals("../style.css", doc.selectFirst("link")!!.attr("href"))
        assertTrue(doc.selectFirst("style")!!.data().contains("font.ttf"))
        assertEquals("width=800,height=1200", doc.selectFirst("meta[name=viewport]")!!.attr("content"))
        assertEquals("原文", doc.selectFirst("td")!!.text())
        assertEquals("../cover.svg", doc.selectFirst("image")!!.attr("href"))
        assertEquals("text-indent:2em", doc.getElementById("chapter")!!.attr("style"))
        assertEquals("zi", doc.selectFirst("rt")!!.text())
    }

    @Test
    fun removesExecutableBookContentWithoutRemovingStyles() {
        val original = """<html><head><base href="https://example.com/"/>
            <meta http-equiv="refresh" content="0;url=https://example.com"/><style>p{color:red}</style>
            </head><body onload="evil()"><script>evil()</script><iframe src="file:///a"/>
            <p onclick="evil()">text</p><a href="java&#x73;cript:evil()">bad</a></body></html>"""
        val doc = Jsoup.parse(publisherDocument(original.toByteArray()).toString(Charsets.UTF_8))
        assertTrue(doc.select("script,iframe,base,meta[http-equiv],[onclick],[onload]").isEmpty())
        assertFalse(doc.selectFirst("a")!!.hasAttr("href"))
        assertEquals("p{color:red}", doc.selectFirst("style")!!.data())
    }

    @Test
    fun findsQuoteAfterWhitespaceChangesAndChoosesNearbyDuplicate() {
        val text = "heading\n  同一段文字\nother\n同一段文字 end"
        assertEquals(text.lastIndexOf("同一段"), epubTextPosition(text, "同 一段\n文字", 0.9))
        assertEquals(text.indexOf("同一段"), epubTextPosition(text, "同一段文字", 0.0))
    }

    @Test
    fun fallsBackToBoundedProgressWhenContentChanged() {
        assertEquals(5, epubTextPosition("0123456789", "missing", 0.5))
        assertEquals(10, epubTextPosition("0123456789", "", 3.0))
        assertEquals(0, epubTextPosition("0123456789", "", Double.NaN))
        assertEquals(0, epubTextPosition("", "", 0.7))
    }
}
