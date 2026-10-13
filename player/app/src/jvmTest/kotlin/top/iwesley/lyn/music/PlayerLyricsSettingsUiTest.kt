package top.iwesley.lyn.music

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import kotlin.test.*
import java.nio.file.Files
import top.iwesley.lyn.music.core.model.*
import top.iwesley.lyn.music.platform.lyricsSharePreviewFontFamily

class PlayerLyricsSettingsUiTest {
    @Test fun `corrupt imported font falls back before text layout`() {
        val file = Files.createTempFile("invalid-lyrics-font", ".ttf").toFile()
        try {
            file.writeText("not a font")
            assertNull(lyricsSharePreviewFontFamily("imported:broken", "Broken", file.absolutePath))
        } finally { file.delete() }
    }

    @Test fun `seconds parsing rejects partial invalid and out-of-range input`() {
        assertEquals(100L, parsePlayerLyricsOffsetSeconds("+0.1"))
        assertEquals(-30000L, parsePlayerLyricsOffsetSeconds("-30"))
        assertEquals(250L, parsePlayerLyricsOffsetSeconds("0,25"))
        for (value in listOf("", "-", "+", "NaN", "Infinity", "30.001", "-31", "one")) assertNull(parsePlayerLyricsOffsetSeconds(value), value)
        for (offset in listOf(-30_000L, -50L, 0L, 125L, 30_000L)) assertEquals(offset, parsePlayerLyricsOffsetSeconds(formatPlayerLyricsOffset(offset)))
    }

    @Test fun `font scaling preserves line-height hierarchy and default typography`() {
        val original = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontFamily = FontFamily.SansSerif)
        assertEquals(original, original.withPlayerLyricsStyle(PlayerLyricsStyle(), null))
        val changed = original.withPlayerLyricsStyle(PlayerLyricsStyle(fontScale = 1.75f), FontFamily.Monospace)
        assertEquals(35.sp, changed.fontSize)
        assertEquals(49.sp, changed.lineHeight)
        assertEquals(FontFamily.Monospace, changed.fontFamily)
    }

    @Test fun `stored style rejects nonimported fonts and corrupt scale`() {
        assertNull(PlayerLyricsStyle(fontKey = "Serif").normalized().fontKey)
        assertEquals(1f, PlayerLyricsStyle(fontScale = Float.NaN).normalized().fontScale)
        val value = PlayerLyricsStyle(0xffabc123.toInt(), 1.25f, "imported:abc")
        assertEquals(value, decodePlayerLyricsStyle(encodePlayerLyricsStyle(value)))
        assertEquals(PlayerLyricsStyle(), decodePlayerLyricsStyle(null))
    }
}
