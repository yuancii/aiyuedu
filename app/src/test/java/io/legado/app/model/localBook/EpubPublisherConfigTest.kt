package io.legado.app.model.localBook

import io.legado.app.data.entities.Book
import org.junit.Assert.*
import org.junit.Test

class EpubPublisherConfigTest {
    @Test
    fun oldBookConfigurationKeepsNativeRenderingAndExistingSettings() {
        val config = Book.Converters().stringToReadConfig("""{"pageAnim":2,"splitLongChapter":false}""")!!
        assertFalse(config.epubUsePublisherLayout)
        assertNull(config.epubPublisherLocator)
        assertNull(config.epubPublisherAnchors)
        assertTrue(config.epubPublisherStyleOverrides.isEmpty())
        assertEquals(2, config.pageAnim)
        assertFalse(config.splitLongChapter)
    }

    @Test
    fun publisherChoiceAndLocatorRoundTripWithoutLosingNativeSettings() {
        val config = Book.ReadConfig(pageAnim = 4, ttsEngine = "system", manualReplaceRuleIds = listOf(7, 12),
            epubUsePublisherLayout = true,
            epubPublisherLocator = """{"href":"OPS/正文.xhtml","quote":"原始段落","offset":15}""",
            epubPublisherAnchors = """{"12":{"href":"OPS/正文.xhtml","quote":"标注","fraction":0.3}}""",
            epubPublisherStyleOverrides = listOf("textSize", "textFont", "padding"))
        val converter = Book.Converters()
        assertEquals(config, converter.stringToReadConfig(converter.readConfigToString(config)))
    }
}
