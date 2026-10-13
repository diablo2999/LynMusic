package top.iwesley.lyn.music.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NavidromeEnhancedLyricsTest {
    @Test
    fun `full and split eslrc values preserve millisecond timing and offset`() {
        listOf(
            "[00:08.921]Talk [00:09.670]to [00:10.150]me[00:10.631]",
            "Talk [00:09.670]to [00:10.150]me[00:10.631]",
        ).forEach { value ->
            val payload = structuredLyrics(8_921L to value, offsetMs = 125L)
            val document = assertNotNull(parseCachedLyrics(NAVIDROME_LYRICS_SOURCE_ID, payload))
            val presentation = assertNotNull(parseEnhancedLyricsPresentation(payload, document))
            val line = presentation.lines.single()

            assertEquals("Talk to me", document.lines.single().text)
            assertEquals(8_921L, document.lines.single().timestampMs)
            assertEquals(125L, document.offsetMs)
            assertEquals(125L, presentation.offsetMs)
            assertEquals(listOf("Talk ", "to ", "me"), line.segments.map { it.text })
            assertEquals(listOf(8_921L, 9_670L, 10_150L), line.segments.map { it.startTimeMs })
            assertEquals(listOf(9_670L, 10_150L, 10_631L), line.segments.map { it.endTimeMs })
            assertEquals(10_631L, line.lineEndTimeMs)
        }
    }

    @Test
    fun `full eslrc value can supply a missing line start`() {
        val payload = structuredLyrics(null to "[00:01.234]你[00:01.567]好[00:01.890]")
        val document = assertNotNull(parseCachedLyrics(NAVIDROME_LYRICS_SOURCE_ID, payload))
        val presentation = assertNotNull(parseEnhancedLyricsPresentation(payload, document))

        assertEquals(1_234L, document.lines.single().timestampMs)
        assertEquals("你好", document.lines.single().text)
        assertEquals(listOf(1_234L, 1_567L), presentation.lines.single().segments.map { it.startTimeMs })
        assertEquals(1_890L, presentation.lines.single().lineEndTimeMs)
    }

    @Test
    fun `structured eslrc merges adjacent translations with the same start`() {
        val payload = structuredLyrics(
            8_920L to "Talk [00:09.67]to [00:10.15]me[00:10.63]",
            8_920L to "向[00:08.92]我[00:08.92]倾[00:08.92]诉[00:08.92]吧[00:08.92]",
            13_000L to "Spill [00:13.73]the [00:14.28]secrets[00:15.11]",
            13_000L to "吐[00:13.00]露[00:13.00]秘[00:13.00]密[00:13.00]",
        )
        val document = assertNotNull(parseCachedLyrics(NAVIDROME_LYRICS_SOURCE_ID, payload))
        val presentation = assertNotNull(parseEnhancedLyricsPresentation(payload, document))

        assertEquals(listOf("Talk to me", "Spill the secrets"), document.lines.map { it.text })
        assertEquals(listOf("向我倾诉吧", "吐露秘密"), presentation.lines.map { it.translationText })
        assertEquals(listOf(8_920L, 13_000L), document.lines.map { it.timestampMs })
        assertEquals(listOf(10_630L, 15_110L), presentation.lines.map { it.lineEndTimeMs })
    }

    @Test
    fun `cue indices survive translation merging and omitted blank lines`() {
        val payload = structuredLyrics(
            8_920L to "Talk [00:09.67]to [00:10.15]me[00:10.63]",
            8_920L to "向[00:08.92]我[00:08.92]倾[00:08.92]诉[00:08.92]吧[00:08.92]",
            12_000L to "",
            13_000L to "Wrong [00:14.00]words[00:15.00]",
            cueLines = cueLines(index = 3),
        )
        val document = assertNotNull(parseCachedLyrics(NAVIDROME_LYRICS_SOURCE_ID, payload))
        val presentation = assertNotNull(parseEnhancedLyricsPresentation(payload, document))

        assertEquals(listOf("Talk to me", "正确"), document.lines.map { it.text })
        assertEquals(listOf("向我倾诉吧", null), presentation.lines.map { it.translationText })
        assertEquals(listOf("正", "确"), presentation.lines.last().segments.map { it.text })
        assertEquals(listOf(13_100L, 13_500L), presentation.lines.last().segments.map { it.startTimeMs })
        assertEquals(14_000L, presentation.lines.last().lineEndTimeMs)
    }

    @Test
    fun `cue lines override square timestamp text and timing`() {
        val payload = structuredLyrics(
            12_000L to "[00:12.00]错[00:12.50]误[00:13.00]",
            cueLines = cueLines(index = 0),
        )
        val document = assertNotNull(parseCachedLyrics(NAVIDROME_LYRICS_SOURCE_ID, payload))
        val presentation = assertNotNull(parseEnhancedLyricsPresentation(payload, document))

        assertEquals("正确", document.lines.single().text)
        assertEquals(13_100L, document.lines.single().timestampMs)
        assertEquals(listOf(13_100L, 13_500L), presentation.lines.single().segments.map { it.startTimeMs })
        assertEquals(14_000L, presentation.lines.single().lineEndTimeMs)
    }

    @Test
    fun `cue overrides preserve eslrc translation merging for either line`() {
        listOf(listOf(0), listOf(1), listOf(0, 1)).forEach { overriddenIndices ->
            val payload = structuredLyrics(
                13_100L to "Wrong [00:13.30]text[00:14.80]",
                13_100L to "错[00:13.10]误[00:13.10]",
                cueLines = JsonArray(overriddenIndices.flatMap { index ->
                    cueLines(
                        index = index,
                        words = if (index == 0) listOf("Correct ", "words") else listOf("正", "确"),
                    )
                }),
            )
            val document = assertNotNull(parseCachedLyrics(NAVIDROME_LYRICS_SOURCE_ID, payload))
            val presentation = assertNotNull(parseEnhancedLyricsPresentation(payload, document))
            val primaryOverridden = 0 in overriddenIndices
            val expectedWords = if (primaryOverridden) listOf("Correct ", "words") else listOf("Wrong ", "text")
            val line = presentation.lines.single()

            assertEquals(expectedWords.joinToString(""), document.lines.single().text)
            assertEquals(13_100L, document.lines.single().timestampMs)
            assertEquals(document.lines.single().text, line.text)
            assertEquals(if (1 in overriddenIndices) "正确" else "错误", line.translationText)
            assertEquals(expectedWords, line.segments.map { it.text })
            assertEquals(listOf(13_100L, if (primaryOverridden) 13_500L else 13_300L), line.segments.map { it.startTimeMs })
            assertEquals(
                if (primaryOverridden) listOf(13_500L, 14_000L) else listOf(13_300L, 14_800L),
                line.segments.map { it.endTimeMs },
            )
            assertEquals(if (primaryOverridden) 14_000L else 14_800L, line.lineEndTimeMs)
        }
    }

    @Test
    fun `malformed eslrc and literal brackets retain the ordinary fallback`() {
        val values = listOf("第一句", "Plain [aside] text", "Talk [00:09.67]to [aside] me[00:10.63]", "你[00:01.30]好")
        val payload = structuredLyrics(*values.mapIndexed { index, value -> index * 1_000L to value }.toTypedArray())
        val document = assertNotNull(parseCachedLyrics(NAVIDROME_LYRICS_SOURCE_ID, payload))

        assertEquals(values, document.lines.map { it.text })
        assertNull(parseEnhancedLyricsPresentation(payload, document))
    }

    @Test
    fun `unsynced line start is not used to invent eslrc word timing`() {
        val value = "Talk [00:09.67]to [00:10.15]me[00:10.63]"
        val payload = structuredLyrics(8_920L to value, synced = false)
        val document = assertNotNull(parseCachedLyrics(NAVIDROME_LYRICS_SOURCE_ID, payload))

        assertEquals(value, document.lines.single().text)
        assertNull(document.lines.single().timestampMs)
        assertNull(parseEnhancedLyricsPresentation(payload, document))
    }
}

private fun structuredLyrics(
    vararg lines: Pair<Long?, String>,
    offsetMs: Long = 0L,
    synced: Boolean = true,
    cueLines: JsonArray? = null,
): String = buildJsonObject {
    put("lyricsList", buildJsonObject {
        put("structuredLyrics", buildJsonArray {
            add(buildJsonObject {
                put("synced", synced)
                put("offset", offsetMs)
                put("line", buildJsonArray {
                    lines.forEach { (start, value) ->
                        add(buildJsonObject {
                            start?.let { put("start", it) }
                            put("value", value)
                        })
                    }
                })
                cueLines?.let { put("cueLine", it) }
            })
        })
    })
}.toString()

private fun cueLines(index: Int, words: List<String> = listOf("正", "确")): JsonArray = buildJsonArray {
    add(buildJsonObject {
        put("index", index)
        put("start", 13_100L)
        put("end", 14_000L)
        put("value", words.joinToString(""))
        put("cue", buildJsonArray {
            var byteStart = 0
            words.forEachIndexed { index, text ->
                val byteEndExclusive = byteStart + text.encodeToByteArray().size
                add(buildJsonObject {
                    put("start", 13_100L + index * 400L)
                    put("end", if (index == words.lastIndex) 14_000L else 13_100L + (index + 1) * 400L)
                    put("value", text)
                    put("byteStart", byteStart)
                    put("byteEnd", byteEndExclusive - 1)
                })
                byteStart = byteEndExclusive
            }
        })
    })
}
