package top.iwesley.lyn.music.domain

import top.iwesley.lyn.music.core.model.LyricsDocument

/** Hash the unadjusted semantic document, not JSON formatting, artwork or cache timestamps. */
fun lyricsVersion(document: LyricsDocument): String = sha256Hex(buildString {
    fun field(value: Any?) {
        val text = value?.toString().orEmpty()
        append(text.length).append(':').append(text).append(';')
    }
    field(document.sourceId)
    field(document.offsetMs)
    document.lines.forEach { line ->
        field(line.timestampMs)
        field(line.text)
    }
    parseStructuredLyricsPayload(document.rawPayload)?.displayLines?.forEach { line ->
        field(line.lineStartTimeMs)
        field(line.lineEndTimeMs)
        field(line.translationText)
        line.segments.forEach { segment ->
            field(segment.text)
            field(segment.startTimeMs)
            field(segment.endTimeMs)
        }
    }
})
