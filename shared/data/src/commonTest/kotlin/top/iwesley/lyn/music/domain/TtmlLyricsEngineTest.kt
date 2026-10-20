package top.iwesley.lyn.music.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TtmlLyricsEngineTest {
    @Test
    fun `word lyrics retain spacing timing and inline translation without background ruby or romanization`() {
        val raw = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
                xmlns:tts="http://www.w3.org/ns/ttml#styling">
              <body><p begin="1s" end="3s">
                <span begin="1s" end="1.5s">Hello</span> <span begin="1.5s" end="2s"><span tts:ruby="base">world</span><span tts:ruby="text">注音</span></span> &amp; <span begin="2s" end="3s">you</span>
                <span ttm:role="x-translation">你好，世界</span><span ttm:role="x-roman">romanized</span>
                <span ttm:role="x-bg"><span begin="1s" end="3s">background</span></span>
              </p></body>
            </tt>
        """.trimIndent()
        val document = assertNotNull(parseCachedLyrics(MANAGED_LRCAPI_SOURCE_ID, raw))
        val line = assertNotNull(parseEnhancedLyricsPresentation(raw, document)).lines.single()

        assertEquals("Hello world & you", document.lines.single().text)
        assertEquals("你好，世界", line.translationText)
        assertEquals(listOf("Hello ", "world & ", "you"), line.segments.map { it.text })
        assertEquals(listOf(1_000L, 1_500L, 2_000L), line.segments.map { it.startTimeMs })
        assertEquals(listOf(1_500L, 2_000L, 3_000L), line.segments.map { it.endTimeMs })
        assertEquals(raw, document.rawPayload)
    }

    @Test
    fun `header translations use line keys and inline translation takes precedence`() {
        val raw = """
            <tt xmlns:i="http://music.apple.com/lyric-ttml-internal" xmlns:m="http://www.w3.org/ns/ttml#metadata">
              <head><metadata><i:iTunesMetadata>
                <translations><translation><text for="L1">头部译文<span m:role="x-bg">忽略</span></text><text for="L2">第二句译文</text></translation></translations>
                <transliterations><transliteration><text for="L1">romanization</text></transliteration></transliterations>
              </i:iTunesMetadata></metadata></head>
              <body><p begin="1s" i:key="L1">First<span m:role="x-translation">行内译文</span></p><p begin="2s" i:key="L2">Second</p></body>
            </tt>
        """.trimIndent()
        val document = assertNotNull(parseCachedLyrics("manual-override", raw))
        val presentation = assertNotNull(parseEnhancedLyricsPresentation(raw, document))

        assertEquals(listOf("First", "Second"), document.lines.map { it.text })
        assertEquals(listOf("行内译文", "第二句译文"), presentation.lines.map { it.translationText })
        assertTrue(presentation.lines.all { it.segments.isEmpty() })
    }

    @Test
    fun `line breaks separate words in inline and header translations without changing original lyrics`() {
        val raw = """
            <tt xmlns:i="urn:itunes" xmlns:m="urn:metadata" xmlns:t="http://www.w3.org/ns/ttml">
              <head><metadata><translations><translation><text for="L2">Good<br/>morning<t:br/>all</text></translation></translations></metadata></head>
              <body>
                <p begin="1s" end="3s"><span begin="1s" end="2s">One</span> <span begin="2s" end="3s">two</span><span m:role="x-translation">Hello<br/><t:br/>world<span m:role="x-bg">ignored<br/>background</span></span></p>
                <p begin="3s" end="4s" i:key="L2">Second</p>
              </body>
            </tt>
        """.trimIndent()
        val document = assertNotNull(parseCachedLyrics(MANAGED_LRCAPI_SOURCE_ID, raw))
        val presentation = assertNotNull(parseEnhancedLyricsPresentation(raw, document))

        assertEquals(listOf("One two", "Second"), document.lines.map { it.text })
        assertEquals(listOf("Hello world", "Good morning all"), presentation.lines.map { it.translationText })
        assertEquals(listOf("One ", "two"), presentation.lines.first().segments.map { it.text })
        assertEquals(listOf(1_000L, 2_000L), presentation.lines.first().segments.map { it.startTimeMs })
    }

    @Test
    fun `duplicate expanded attributes invalidate the entire TTML including ignored and translated subtrees`() {
        val invalid = listOf(
            """<tt><body><p begin="1s" begin="2s">Ambiguous</p></body></tt>""",
            """<tt xmlns:a="urn:timing" xmlns:b="urn:timing"><body><p a:begin="1s" b:begin="2s">Ambiguous</p></body></tt>""",
            """<tt xmlns:m="urn:metadata"><body><p begin="1s">Original<span m:role="x-bg"><span begin="1s" begin="2s">Background</span></span></p></body></tt>""",
            """<tt xmlns:m="urn:metadata"><body><p begin="1s">Original<span m:role="x-translation"><span lang="en" lang="zh">Translation</span></span></p></body></tt>""",
        )
        invalid.forEach { raw ->
            assertNull(parseTtmlLyricsPayload(raw), raw)
            assertNull(parseCachedLyrics(MANAGED_LRCAPI_SOURCE_ID, raw), raw)
        }
    }

    @Test
    fun `same local attribute names remain valid in distinct namespaces and separate elements`() {
        val raw = """<tt xmlns:a="urn:a" xmlns:b="urn:b"><body><p begin="1s" a:label="A" b:label="B">First</p><p begin="2s" a:label="C">Second</p></body></tt>"""
        val parsed = assertNotNull(parseTtmlLyricsPayload(raw))

        assertEquals(listOf("First", "Second"), parsed.lines.map { it.text })
        assertEquals(listOf(1_000L, 2_000L), parsed.lines.map { it.timestampMs })
    }

    @Test
    fun `duplicate namespace declarations are rejected regardless of URI prefix or element scope`() {
        val invalid = listOf(
            """<tt xmlns:m="urn:a" xmlns:m="urn:a"><body><p begin="1s">Repeated</p></body></tt>""",
            """<tt xmlns:m='urn:a' xmlns:m = "urn:b"><body><p begin="1s">Repeated</p></body></tt>""",
            """<tt xmlns="http://www.w3.org/ns/ttml" xmlns="http://www.w3.org/ns/ttml"><body><p begin="1s">Repeated</p></body></tt>""",
            """<tt xmlns="urn:a" xmlns='urn:b'><body><p begin="1s">Repeated</p></body></tt>""",
            """<歌词:tt xmlns:歌词="http://www.w3.org/ns/ttml" xmlns:歌词="http://www.w3.org/ns/ttml"><歌词:body><歌词:p begin="1s">Repeated</歌词:p></歌词:body></歌词:tt>""",
            """<tt xmlns:m="urn:metadata"><body><p begin="1s">Original<span m:role="x-bg"><br xmlns:x="urn:a" xmlns:x="urn:a"/></span></p></body></tt>""",
        )
        invalid.forEach { raw ->
            assertNull(parseTtmlLyricsPayload(raw), raw)
            assertNull(parseCachedLyrics(MANAGED_LRCAPI_SOURCE_ID, raw), raw)
        }
    }

    @Test
    fun `namespace aliases and declarations on separate elements remain valid`() {
        val raw = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:m="urn:metadata" xmlns:a="urn:alias" xmlns:b="urn:alias">
              <body xmlns:m="urn:other"><p begin="1s" xmlns="http://www.w3.org/ns/ttml" xmlns:m="urn:metadata">First<span m:role="x-translation">译文</span></p><p begin="2s" xmlns:m="urn:metadata">Second</p></body>
            </tt>
        """.trimIndent()
        val document = assertNotNull(parseCachedLyrics(MANAGED_LRCAPI_SOURCE_ID, raw))

        assertEquals(listOf("First", "Second"), document.lines.map { it.text })
        assertEquals("译文", assertNotNull(parseEnhancedLyricsPresentation(raw, document)).lines.first().translationText)
    }

    @Test
    fun `namespace lookalikes in comments CDATA processing instructions and attribute values are ignored`() {
        val raw = """
            <?lyrics xmlns:m="urn:a" xmlns:m="urn:b"?>
            <!-- <ignored xmlns:m="urn:a" xmlns:m="urn:b"/> -->
            <tt xmlns:m="urn:metadata" note='xmlns:m="urn:a" > xmlns:m="urn:b"'>
              <body><p begin="1s"><![CDATA[<example xmlns:m="urn:a" xmlns:m="urn:b"/>]]></p></body>
            </tt>
        """.trimIndent()
        val document = assertNotNull(parseCachedLyrics(MANAGED_LRCAPI_SOURCE_ID, raw))

        assertEquals("""<example xmlns:m="urn:a" xmlns:m="urn:b"/>""", document.lines.single().text)
        assertEquals(1_000L, document.lines.single().timestampMs)
    }

    @Test
    fun `prefixed root and BOM parse clock seconds and milliseconds and repair missing ends`() {
        val raw = "\uFEFF" + """
            <?xml version="1.0" encoding="UTF-8"?>
            <!-- lyrics -->
            <t:tt xmlns:t="http://www.w3.org/ns/ttml"><t:body>
              <t:p begin="00:12.000" end="13s">Next</t:p>
              <t:p begin="00:10.000"><t:span begin="10">One</t:span> <t:span begin="10500ms">two</t:span></t:p>
            </t:body></t:tt>
        """.trimIndent()
        val parsed = assertNotNull(parseTtmlLyricsPayload(raw))

        assertEquals(listOf("One two", "Next"), parsed.lines.map { it.text })
        assertEquals(listOf(10_000L, 12_000L), parsed.lines.map { it.timestampMs })
        assertEquals(12_000L, parsed.displayLines.first().lineEndTimeMs)
        assertEquals(listOf(10_500L, 12_000L), parsed.displayLines.first().segments.map { it.endTimeMs })
        assertNotNull(parseCachedLyrics(MANAGED_LRCAPI_SOURCE_ID, raw))
    }

    @Test
    fun `Unicode prefixes preserve word timing and translation on cache reads and embed as LRC`() {
        listOf("歌词", "t\u0301").forEach { prefix ->
            val raw = """
                <$prefix:tt xmlns:$prefix="http://www.w3.org/ns/ttml" xmlns:m="urn:metadata">
                  <$prefix:body><$prefix:p begin="1s" end="3s"><$prefix:span begin="1s" end="2s">One</$prefix:span> <$prefix:span begin="2s" end="3s">two</$prefix:span><$prefix:span m:role="x-translation">译文</$prefix:span></$prefix:p></$prefix:body>
                </$prefix:tt>
            """.trimIndent()
            val parsed = assertNotNull(parseTtmlLyricsPayload(raw))
            val document = assertNotNull(parseCachedLyrics(MANAGED_LRCAPI_SOURCE_ID, raw))
            val line = assertNotNull(parseEnhancedLyricsPresentation(raw, document)).lines.single()

            assertEquals(parsed.lines, document.lines)
            assertEquals("One two", document.lines.single().text)
            assertEquals(1_000L, document.lines.single().timestampMs)
            assertEquals(listOf(1_000L, 2_000L), line.segments.map { it.startTimeMs })
            assertEquals("译文", line.translationText)
            assertEquals(raw, document.rawPayload)
            assertEquals("[00:01.00]One two", preferredEmbeddedLyricsText(document))
        }
    }

    @Test
    fun `same time lines remain independent and missing ends use the next distinct line start`() {
        val raw = "<tt><body><p begin=\"1s\">First</p><p begin=\"1s\">Parallel</p><p begin=\"3s\">Next</p></body></tt>"
        val parsed = assertNotNull(parseTtmlLyricsPayload(raw))

        assertEquals(listOf("First", "Parallel", "Next"), parsed.lines.map { it.text })
        assertEquals(listOf(3_000L, 3_000L, null), parsed.displayLines.map { it.lineEndTimeMs })
    }

    @Test
    fun `line timing hint suppresses word timing but preserves translation`() {
        val raw = """<tt xmlns:i="urn:itunes" xmlns:m="urn:metadata" i:timing="Line"><body><p begin="01:02.5" end="00:01:04.000"><span begin="62.5s" end="63s">Line</span><span m:role="x-translation">译文</span></p></body></tt>"""
        val document = assertNotNull(parseCachedLyrics(MANAGED_LRCAPI_SOURCE_ID, raw))
        val line = assertNotNull(parseEnhancedLyricsPresentation(raw, document)).lines.single()

        assertEquals(62_500L, line.lineStartTimeMs)
        assertEquals(64_000L, line.lineEndTimeMs)
        assertEquals("译文", line.translationText)
        assertTrue(line.segments.isEmpty())
    }

    @Test
    fun `missing invalid or reversed word times retain readable line text`() {
        val rawValues = listOf(
            "<tt><body><p>Untimed line</p></body></tt>",
            "<tt><body><p><span begin=\"bad\">One</span> <span begin=\"2s\">two</span></p></body></tt>",
            "<tt><body><p begin=\"1s\"><span begin=\"3s\">One</span> <span begin=\"2s\">two</span></p></body></tt>",
            "<tt><body><p begin=\"1s\" end=\"3s\"><span begin=\"bad\" end=\"NaN\">One two</span></p></body></tt>",
        )
        rawValues.forEach { raw ->
            val parsed = assertNotNull(parseTtmlLyricsPayload(raw))
            assertTrue(parsed.displayLines.all { it.segments.isEmpty() })
            assertTrue(parsed.lines.single().text in listOf("Untimed line", "One two"))
        }
    }

    @Test
    fun `invalid or empty TTML cannot be displayed as markup`() {
        val invalid = listOf(
            "<tt><body><p>Broken",
            "<tt><body><p><![CDATA[Broken",
            "<tt><body><p>Broken</p></body></tt>garbage",
            "<tt><body><p>Broken</p></body></tt><!--",
            "<歌词:tt xmlns:歌词=\"http://www.w3.org/ns/ttml\"><歌词:body><歌词:p>Broken",
            "<tt><body><p>Broken</body></tt>",
            "<tt><body><p>&unknown;</p></body></tt>",
            "<tt><body><p> </p></body></tt>",
            "<tt/><tt/>",
            "<?xml version=\"1.0\"?><wrong><p>Not lyrics</p></wrong>",
            "<!DOCTYPE tt [<!ENTITY external SYSTEM \"file:///private/secret\">]><tt><body><p>&external;</p></body></tt>",
            "<!-- lyrics --><!DOCTYPE tt><tt><body><p>Rejected declaration</p></body></tt>",
        )
        invalid.forEach { raw ->
            assertNull(parseTtmlLyricsPayload(raw), raw)
            assertNull(parseCachedLyrics(MANAGED_LRCAPI_SOURCE_ID, raw), raw)
        }
        assertNull(parseTtmlLyricsPayload("<wrong><p>Not lyrics</p></wrong>"))
    }

    @Test
    fun `TTML size limit is measured in UTF8 bytes`() {
        val raw = "<tt><body><p>" + "词".repeat(5 * 1_024 * 1_024 / 3 + 1) + "</p></body></tt>"
        assertNull(parseTtmlLyricsPayload(raw))
    }

    @Test
    fun `cache versions include word timing and translation but ignore XML formatting`() {
        val raw = """<tt xmlns:m="urn:metadata"><body><p begin="1s" end="3s"><span begin="1s" end="2s">One</span> <span begin="2s" end="3s">two</span><span m:role="x-translation">译文</span></p></body></tt>"""
        fun version(value: String) = lyricsVersion(assertNotNull(parseCachedLyrics(MANAGED_LRCAPI_SOURCE_ID, value)))

        assertEquals(version(raw), version("\n$raw\n"))
        assertNotEquals(version(raw), version(raw.replace("begin=\"2s\"", "begin=\"2.5s\"")))
        assertNotEquals(version(raw), version(raw.replace("译文", "新译文")))
    }

    @Test
    fun `embedding TTML emits line LRC and leaves existing enhanced LRC unchanged`() {
        val ttml = "<tt><body><p begin=\"1s\">First</p><p begin=\"2s\">Second</p></body></tt>"
        val document = assertNotNull(parseCachedLyrics(MANAGED_LRCAPI_SOURCE_ID, ttml))
        assertEquals("[00:01.00]First\n[00:02.00]Second", preferredEmbeddedLyricsText(document))
        val lrc = "[offset:250]\n[00:01.00]<00:01.00>One <00:02.00>two"
        assertEquals(lrc, preferredEmbeddedLyricsText(assertNotNull(parseCachedLyrics("source", lrc))))
    }
}
