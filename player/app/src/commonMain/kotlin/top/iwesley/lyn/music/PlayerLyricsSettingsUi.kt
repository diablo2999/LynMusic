package top.iwesley.lyn.music

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import top.iwesley.lyn.music.core.model.formatThemeHexColor
import top.iwesley.lyn.music.core.model.PlayerLyricsStyle
import top.iwesley.lyn.music.feature.player.PlayerIntent
import top.iwesley.lyn.music.feature.player.PlayerState
import top.iwesley.lyn.music.platform.PlayerLyricsSettingsWindow
import top.iwesley.lyn.music.platform.lyricsSharePreviewFontFamily
import top.iwesley.lyn.music.resources.*
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
internal fun LyricsStyleButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.size(52.dp)) {
        Icon(Icons.Rounded.Palette, uiString(Res.string.player_lyrics_style), tint = Color.White)
    }
}

@Composable
internal fun rememberPlayerLyricsFont(state: PlayerState, onIntent: (PlayerIntent) -> Unit): FontFamily? {
    val key = state.lyricsStyle.fontKey
    val path = state.lyricsFontPath
    val family = remember(key, path) {
        if (key == null || path == null) null else lyricsSharePreviewFontFamily(key, displayName = null, fontFilePath = path)
    }
    LaunchedEffect(key, path, family) {
        if (key != null && path != null && family == null) onIntent(PlayerIntent.LyricsFontFailed(key))
    }
    return family
}

internal fun TextStyle.withPlayerLyricsStyle(style: PlayerLyricsStyle, family: FontFamily?): TextStyle = copy(
    fontSize = fontSize * style.fontScale,
    lineHeight = if (lineHeight.isSp) lineHeight * style.fontScale else lineHeight,
    fontFamily = family ?: fontFamily,
)

internal fun parsePlayerLyricsOffsetSeconds(value: String): Long? = value.trim().replace(',', '.')
    .toDoubleOrNull()?.takeIf { it.isFinite() && it in -30.0..30.0 }?.let { (it * 1_000).roundToLong() }

internal fun formatPlayerLyricsOffset(offsetMs: Long): String {
    val magnitude = kotlin.math.abs(offsetMs)
    return "${if (offsetMs < 0) "-" else "+"}${magnitude / 1000}.${(magnitude % 1000).toString().padStart(3, '0').trimEnd('0').ifEmpty { "0" }}"
}

@Composable
internal fun PlayerLyricsSettingsOverlay(state: PlayerState, mobile: Boolean, onIntent: (PlayerIntent) -> Unit) {
    PlayerLyricsSettingsWindow(onDismiss = { onIntent(PlayerIntent.DismissLyricsStyle) }) {
        BoxWithConstraints(Modifier.fillMaxSize().imePadding().navigationBarsPadding().padding(16.dp)) {
            val style = state.lyricsStyle
            var paletteVisible by remember { mutableStateOf(false) }
            var fontsVisible by remember { mutableStateOf(false) }
            var hsv by remember { mutableStateOf(argbToThemePickerHsv(style.highlightArgb)) }
            LaunchedEffect(style.highlightArgb) {
                if (themePickerHsvToArgb(hsv) != style.highlightArgb) hsv = argbToThemePickerHsv(style.highlightArgb)
            }
            val changeColor: (Int) -> Unit = { onIntent(PlayerIntent.LyricsStyleChanged(style.copy(highlightArgb = it))) }
            Surface(
                modifier = Modifier.align(if (mobile) Alignment.BottomCenter else Alignment.CenterStart)
                    .widthIn(max = 420.dp).fillMaxWidth()
                    .heightIn(max = maxHeight * if (mobile) 0.55f else 0.92f),
                shape = RoundedCornerShape(24.dp),
                color = Color(0xFF15171C).copy(alpha = 0.82f),
                contentColor = Color.White,
                tonalElevation = 0.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(uiString(Res.string.player_lyrics_style), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onIntent(PlayerIntent.DismissLyricsStyle) }) {
                            Icon(Icons.Rounded.Close, uiString(Res.string.player_lyrics_close))
                        }
                    }
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(uiString(Res.string.player_lyrics_highlight))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            listOf(0xFFFFFFFF, 0xFFFFD166, 0xFFFF7B9C, 0xFFA78BFA, 0xFF73C7FF, 0xFF75E2BB).forEach { argb ->
                                val color = argb.toInt()
                                Box(Modifier.size(44.dp).clip(CircleShape).clickable { changeColor(color) }
                                    .semantics { contentDescription = formatThemeHexColor(color) }, contentAlignment = Alignment.Center) {
                                    Box(Modifier.size(28.dp).clip(CircleShape).background(Color(color))
                                        .border(if (style.highlightArgb == color) 3.dp else 0.dp, Color.White, CircleShape))
                                }
                            }
                        }
                        TextButton(onClick = { paletteVisible = !paletteVisible }) {
                            Text(uiString(if (paletteVisible) Res.string.player_lyrics_hide_palette else Res.string.player_lyrics_custom_color), color = Color.White)
                        }
                        if (paletteVisible) {
                            Text(formatThemeHexColor(style.highlightArgb))
                            ThemeSaturationValuePanel(hsv.hue, hsv.saturation, hsv.value, modifier = Modifier.widthIn(max = 190.dp).align(Alignment.CenterHorizontally), onSelectionChange = {
                                hsv = hsv.copy(saturation = it.saturation, value = it.value)
                                changeColor(themePickerHsvToArgb(hsv))
                            })
                            ThemeHueSlider(hsv.hue, onHueChange = {
                                hsv = hsv.copy(hue = it)
                                changeColor(themePickerHsvToArgb(hsv))
                            }, modifier = Modifier.padding(vertical = 8.dp))
                        }
                        Text("${uiString(Res.string.player_lyrics_size)}  ${(style.fontScale * 100).roundToInt()}%")
                        Slider(value = style.fontScale, onValueChange = {
                            val scale = (it * 20).roundToInt() / 20f
                            onIntent(PlayerIntent.LyricsStyleChanged(style.copy(fontScale = scale)))
                        }, valueRange = 0.75f..1.75f, steps = 19)
                        val selectedFont = state.availableLyricsFonts.firstOrNull { it.fontKey == style.fontKey }
                        TextButton(onClick = { fontsVisible = !fontsVisible }) {
                            Text("${uiString(Res.string.player_lyrics_font)} · ${selectedFont?.displayName ?: uiString(Res.string.player_lyrics_default_font)}", color = Color.White)
                        }
                        if (fontsVisible) {
                            if (state.isLyricsFontsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                            LyricsFontRow(uiString(Res.string.player_lyrics_default_font), style.fontKey == null, null) {
                                onIntent(PlayerIntent.LyricsStyleChanged(style.copy(fontKey = null)))
                            }
                            state.availableLyricsFonts.forEach { font ->
                                val family = remember(font.fontKey, font.fontFilePath) { lyricsSharePreviewFontFamily(font.fontKey, font.displayName, font.fontFilePath) }
                                LyricsFontRow(font.displayName, style.fontKey == font.fontKey, family) {
                                    onIntent(PlayerIntent.LyricsStyleChanged(style.copy(fontKey = font.fontKey)))
                                }
                            }
                        }
                        TextButton(onClick = { onIntent(PlayerIntent.LyricsStyleChanged(PlayerLyricsStyle())) }) {
                            Text(uiString(Res.string.player_lyrics_reset_style), color = Color.White)
                        }
                        HorizontalDivider(color = Color.White.copy(alpha = 0.18f))
                        Text(uiString(Res.string.player_lyrics_offset), style = MaterialTheme.typography.titleSmall)
                        Text(state.effectiveSnapshot.currentDisplayTitle, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        val trackId = state.effectiveSnapshot.currentTrack?.id
                        val version = state.lyricsVersion
                        val enabled = state.originalLyrics?.isSynced == true && version != null && trackId == state.lyricsTrackId
                        var input by remember(trackId, version, state.lyricsUserOffsetMs) { mutableStateOf(formatPlayerLyricsOffset(state.lyricsUserOffsetMs)) }
                        val applyOffset: (Long) -> Unit = { value ->
                            if (enabled && trackId != null && version != null) onIntent(PlayerIntent.LyricsOffsetChanged(trackId, version, value))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = input, onValueChange = { input = it }, enabled = enabled,
                                modifier = Modifier.weight(1f), singleLine = true,
                                label = { Text(uiString(Res.string.player_lyrics_seconds)) },
                                isError = enabled && parsePlayerLyricsOffsetSeconds(input) == null,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { parsePlayerLyricsOffsetSeconds(input)?.let(applyOffset) }),
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedLabelColor = Color.White, unfocusedLabelColor = Color.White.copy(alpha = 0.7f)))
                            TextButton(onClick = { parsePlayerLyricsOffsetSeconds(input)?.let(applyOffset) }, enabled = enabled && parsePlayerLyricsOffsetSeconds(input) != null) {
                                Text(uiString(Res.string.player_lyrics_apply_offset))
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { applyOffset(state.lyricsUserOffsetMs + 100) }, enabled = enabled && state.lyricsUserOffsetMs < 30_000) { Text(uiString(Res.string.player_lyrics_earlier)) }
                            TextButton(onClick = { applyOffset(0) }, enabled = enabled) { Text(uiString(Res.string.player_lyrics_reset_offset)) }
                            TextButton(onClick = { applyOffset(state.lyricsUserOffsetMs - 100) }, enabled = enabled && state.lyricsUserOffsetMs > -30_000) { Text(uiString(Res.string.player_lyrics_later)) }
                        }
                        Text(uiString(Res.string.player_lyrics_offset_hint), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricsFontRow(label: String, selected: Boolean, family: FontFamily?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = onClick)
        Text(label, fontFamily = family, modifier = Modifier.weight(1f))
    }
}
