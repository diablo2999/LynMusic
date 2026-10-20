package top.iwesley.lyn.music.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LrcApiTtmlTest {
    private val config = buildManagedLrcApiConfig("https://lyrics.example/jsonapi")
    private val ttml = "<tt><body><p begin=\"1s\" end=\"3s\"><span begin=\"1s\" end=\"2s\">TTML</span> <span begin=\"2s\" end=\"3s\">line</span></p></body></tt>"

    @Test
    fun `existing built in configuration prefers TTML and preserves candidate metadata`() {
        val candidate = parseLyricsPayloadResults(config, item(ttml).toString()).single()

        assertEquals("TTML line", candidate.document.lines.single().text)
        assertEquals(ttml, candidate.document.rawPayload)
        assertEquals("song-1", candidate.itemId)
        assertEquals("Song", candidate.title)
        assertEquals("Artist", candidate.artistName)
        assertEquals("Album", candidate.albumTitle)
        assertEquals(180, candidate.durationSeconds)
        assertEquals("https://cover.example/song.jpg", candidate.artworkLocator)
        assertTrue(assertNotNull(parseEnhancedLyricsPresentation(ttml, candidate.document)).lines.single().segments.isNotEmpty())
    }

    @Test
    fun `missing null blank broken wrong root and empty TTML fall back to LRC per candidate`() {
        val values = listOf(null, "", "  ", "<tt><p>broken", "<wrong><p>wrong root</p></wrong>", "<tt><body/></tt>")
        val items = values.map { item(it) } + JsonObject(item(null).toMutableMap().apply { put("lrc_ttml", JsonNull) })
        val results = parseLyricsPayloadResults(config, JsonArray(items).toString())

        assertEquals(items.size, results.size)
        assertTrue(results.all { it.document.lines.single().text == "LRC fallback" })
        assertTrue(results.all { it.document.rawPayload == "[00:01.00]LRC fallback" })
    }

    @Test
    fun `over limit and DTD TTML fall back to LRC without discarding following candidates`() {
        val oversized = "<tt><body><p>" + "x".repeat(5 * 1_024 * 1_024) + "</p></body></tt>"
        val dtd = "<!DOCTYPE tt [<!ENTITY x 'expanded'>]><tt><body><p>&x;</p></body></tt>"
        val results = parseLyricsPayloadResults(config, JsonArray(listOf(item(oversized), item(dtd), item(ttml))).toString())

        assertEquals(listOf("LRC fallback", "LRC fallback", "TTML line"), results.map { it.document.lines.single().text })
    }

    @Test
    fun `malformed XML falls back per candidate and leaves following candidates usable`() {
        val brokenPayloads = listOf(
            "<tt><body><p>Broken",
            "<tt><body><p><![CDATA[Broken",
            "<tt><body><p>Broken</p></body></tt>garbage",
            "<tt><body><p>Broken</p></body></tt><!--",
            """<tt><body><p begin="1s" begin="2s">Ambiguous</p></body></tt>""",
            """<tt xmlns:a="urn:timing" xmlns:b="urn:timing"><body><p a:begin="1s" b:begin="2s">Ambiguous</p></body></tt>""",
            """<tt xmlns:m="urn:a" xmlns:m="urn:a"><body><p begin="1s">Repeated</p></body></tt>""",
            """<tt xmlns:m="urn:a" xmlns:m="urn:b"><body><p begin="1s">Repeated</p></body></tt>""",
            """<tt xmlns="http://www.w3.org/ns/ttml" xmlns="http://www.w3.org/ns/ttml"><body><p begin="1s">Repeated</p></body></tt>""",
            """<tt xmlns="urn:a" xmlns="urn:b"><body><p begin="1s">Repeated</p></body></tt>""",
        )
        brokenPayloads.forEach { broken ->
            val unusable = buildJsonObject { put("lrc_ttml", broken) }
            val results = parseLyricsPayloadResults(config, JsonArray(listOf(item(broken), unusable, item(ttml))).toString())

            assertEquals(listOf("LRC fallback", "TTML line"), results.map { it.document.lines.single().text })
            assertEquals(listOf("[00:01.00]LRC fallback", ttml), results.map { it.document.rawPayload })
        }
    }

    @Test
    fun `unusable candidates are skipped and valid line timed TTML needs no LRC`() {
        val invalid = buildJsonObject { put("lrc_ttml", "<tt><body><p>broken") }
        val valid = buildJsonObject { put("lrc_ttml", "<tt><body><p begin=\"1s\">TTML only</p></body></tt>") }
        val results = parseLyricsPayloadResults(config, JsonArray(listOf(invalid, valid)).toString())

        assertEquals("TTML only", results.single().document.lines.single().text)
        assertTrue(results.single().document.isSynced)
    }

    @Test
    fun `other direct sources keep using their configured LRC fields`() {
        val other = config.copy(id = "user-lrcapi", name = "LrcAPI")
        val candidate = parseLyricsPayloadResults(other, item(ttml).toString()).single()

        assertEquals("LRC fallback", candidate.document.lines.single().text)
    }

    private fun item(rawTtml: String?): JsonObject = buildJsonObject {
        put("id", "song-1")
        put("title", "Song")
        put("artist", "Artist")
        put("album", "Album")
        put("duration", 180)
        put("cover", "https://cover.example/song.jpg")
        put("lrc", "[00:01.00]LRC fallback")
        if (rawTtml != null) put("lrc_ttml", JsonPrimitive(rawTtml))
    }
}
