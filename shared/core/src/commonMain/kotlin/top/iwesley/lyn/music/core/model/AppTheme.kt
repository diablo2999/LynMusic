package top.iwesley.lyn.music.core.model

import kotlin.math.pow
import kotlin.math.roundToInt

enum class AppThemeId {
    Classic,
    Forest,
    Ocean,
    Sand,
    Custom,
}

enum class AppThemeTextPalette {
    White,
    Black,
}

data class AppThemeTokens(
    val backgroundArgb: Int,
    val accentArgb: Int,
    val focusArgb: Int,
)

data class AppThemeTextPalettePreferences(
    val classic: AppThemeTextPalette = AppThemeTextPalette.White,
    val forest: AppThemeTextPalette = AppThemeTextPalette.White,
    val ocean: AppThemeTextPalette = AppThemeTextPalette.White,
    val sand: AppThemeTextPalette = AppThemeTextPalette.White,
    val custom: AppThemeTextPalette = AppThemeTextPalette.White,
)

data class AppThemeSelection(
    val themeId: AppThemeId = AppThemeId.Ocean,
)

data class AppThemePalette(
    val backgroundArgb: Int,
    val onBackgroundArgb: Int,
    val surfaceArgb: Int,
    val onSurfaceArgb: Int,
    val surfaceVariantArgb: Int,
    val onSurfaceVariantArgb: Int,
    val primaryArgb: Int,
    val onPrimaryArgb: Int,
    val secondaryArgb: Int,
    val onSecondaryArgb: Int,
    val tertiaryArgb: Int,
    val onTertiaryArgb: Int,
    val outlineArgb: Int,
    val appGradientTopArgb: Int,
    val navContainerArgb: Int,
    val cardContainerArgb: Int,
    val cardBorderArgb: Int,
    val selectedContainerArgb: Int,
    val selectedBorderArgb: Int,
    val secondaryTextArgb: Int,
    val heroGlowArgb: Int,
)

val CLASSIC_APP_THEME_TOKENS = AppThemeTokens(
    backgroundArgb = 0xFF120B0D.toInt(),
    accentArgb = 0xFFE03131.toInt(),
    focusArgb = 0xFFE03131.toInt(),
)

val FOREST_APP_THEME_TOKENS = AppThemeTokens(
    backgroundArgb = 0xFFF2F7F1.toInt(),
    accentArgb = 0xFF2F8F5B.toInt(),
    focusArgb = 0xFF8CCB5E.toInt(),
)

val OCEAN_APP_THEME_TOKENS = AppThemeTokens(
    backgroundArgb = 0xFFFFFFFF.toInt(),
    accentArgb = 0xFFE03131.toInt(),
    focusArgb = 0xFFE03131.toInt(),
)

val SAND_APP_THEME_TOKENS = AppThemeTokens(
    backgroundArgb = 0xFF17120D.toInt(),
    accentArgb = 0xFFC97A2B.toInt(),
    focusArgb = 0xFFF2C078.toInt(),
)

fun defaultCustomThemeTokens(): AppThemeTokens = CLASSIC_APP_THEME_TOKENS

fun defaultThemeTextPalettePreferences(): AppThemeTextPalettePreferences = AppThemeTextPalettePreferences(
    forest = AppThemeTextPalette.Black,
    ocean = AppThemeTextPalette.Black,
)

fun presetThemeTokens(themeId: AppThemeId): AppThemeTokens {
    return when (themeId) {
        AppThemeId.Classic -> CLASSIC_APP_THEME_TOKENS
        AppThemeId.Forest -> FOREST_APP_THEME_TOKENS
        AppThemeId.Ocean -> OCEAN_APP_THEME_TOKENS
        AppThemeId.Sand -> SAND_APP_THEME_TOKENS
        AppThemeId.Custom -> defaultCustomThemeTokens()
    }
}

fun resolveAppThemeTokens(
    themeId: AppThemeId,
    customThemeTokens: AppThemeTokens,
): AppThemeTokens {
    return if (themeId == AppThemeId.Custom) customThemeTokens else presetThemeTokens(themeId)
}

fun resolveAppThemeTextPalette(
    themeId: AppThemeId,
    preferences: AppThemeTextPalettePreferences,
): AppThemeTextPalette {
    return preferences.paletteFor(themeId)
}

fun AppThemeTextPalettePreferences.paletteFor(themeId: AppThemeId): AppThemeTextPalette {
    return when (themeId) {
        AppThemeId.Classic -> classic
        AppThemeId.Forest -> forest
        AppThemeId.Ocean -> ocean
        AppThemeId.Sand -> sand
        AppThemeId.Custom -> custom
    }
}

fun AppThemeTextPalettePreferences.withThemePalette(
    themeId: AppThemeId,
    palette: AppThemeTextPalette,
): AppThemeTextPalettePreferences {
    return when (themeId) {
        AppThemeId.Classic -> copy(classic = palette)
        AppThemeId.Forest -> copy(forest = palette)
        AppThemeId.Ocean -> copy(ocean = palette)
        AppThemeId.Sand -> copy(sand = palette)
        AppThemeId.Custom -> copy(custom = palette)
    }
}

fun deriveAppThemePalette(
    tokens: AppThemeTokens,
    textPalette: AppThemeTextPalette,
): AppThemePalette {
    val background = opaqueArgb(tokens.backgroundArgb)
    val accent = opaqueArgb(tokens.accentArgb)
    val focus = opaqueArgb(tokens.focusArgb)
    val surface = lightenArgb(background, 0.06f)
    val surfaceVariant = lightenArgb(background, 0.12f)
    val primary = accent
    val secondary = focus
    val tertiary = blendArgb(accent, focus, 0.5f)
    val outline = blendArgb(focus, background, 0.34f)
    val textPrimary = when (textPalette) {
        AppThemeTextPalette.White -> 0xFF00F500.toInt()
        AppThemeTextPalette.Black -> 0xFF111111.toInt()
    }
    val textSecondary = when (textPalette) {
        AppThemeTextPalette.White -> 0xFFD6D1CD.toInt()
        AppThemeTextPalette.Black -> 0xFF4A4541.toInt()
    }
    val autoContentColor = contentColorFor(background)
    val onBackground = textPrimary
    val onSurface = textPrimary
    val onPrimary = contentColorFor(primary)
    val onSecondary = contentColorFor(secondary)
    val onTertiary = contentColorFor(tertiary)
    val onSurfaceVariant = textSecondary
    return AppThemePalette(
        backgroundArgb = background,
        onBackgroundArgb = onBackground,
        surfaceArgb = surface,
        onSurfaceArgb = onSurface,
        surfaceVariantArgb = surfaceVariant,
        onSurfaceVariantArgb = onSurfaceVariant,
        primaryArgb = primary,
        onPrimaryArgb = onPrimary,
        secondaryArgb = secondary,
        onSecondaryArgb = onSecondary,
        tertiaryArgb = tertiary,
        onTertiaryArgb = onTertiary,
        outlineArgb = outline,
        appGradientTopArgb = blendArgb(accent, background, 0.24f),
        navContainerArgb = blendArgb(surface, background, 0.74f),
        cardContainerArgb = blendArgb(surfaceVariant, background, 0.68f),
        cardBorderArgb = blendArgb(autoContentColor, background, 0.12f),
        selectedContainerArgb = blendArgb(focus, background, 0.20f),
        selectedBorderArgb = blendArgb(focus, background, 0.42f),
        secondaryTextArgb = textSecondary,
        heroGlowArgb = argbWithAlpha(accent, if (relativeLuminance(background) < 0.2f) 0.42f else 0.2f),
    )
}

fun parseThemeHexColor(input: String): Int? {
    val normalized = input.trim().removePrefix("#")
    if (normalized.length != 6) return null
    return normalized.toIntOrNull(radix = 16)?.let { rgb ->
        0xFF000000.toInt() or rgb
    }
}

fun formatThemeHexColor(argb: Int): String {
    return buildString(capacity = 7) {
        append('#')
        append(((argb ushr 16) and 0xFF).toString(16).padStart(2, '0').uppercase())
        append(((argb ushr 8) and 0xFF).toString(16).padStart(2, '0').uppercase())
        append((argb and 0xFF).toString(16).padStart(2, '0').uppercase())
    }
}

private fun opaqueArgb(argb: Int): Int = (argb and 0x00FFFFFF) or 0xFF000000.toInt()

private fun lightenArgb(argb: Int, amount: Float): Int {
    val clampedAmount = amount.coerceIn(0f, 1f)
    return blendArgb(0xFFFFFFFF.toInt(), opaqueArgb(argb), clampedAmount)
}

private fun blendArgb(foregroundArgb: Int, backgroundArgb: Int, foregroundWeight: Float): Int {
    val weight = foregroundWeight.coerceIn(0f, 1f)
    val backgroundWeight = 1f - weight
    val red = (((foregroundArgb ushr 16) and 0xFF) * weight + ((backgroundArgb ushr 16) and 0xFF) * backgroundWeight)
        .roundToInt()
        .coerceIn(0, 255)
    val green = (((foregroundArgb ushr 8) and 0xFF) * weight + ((backgroundArgb ushr 8) and 0xFF) * backgroundWeight)
        .roundToInt()
        .coerceIn(0, 255)
    val blue = (((foregroundArgb) and 0xFF) * weight + ((backgroundArgb) and 0xFF) * backgroundWeight)
        .roundToInt()
        .coerceIn(0, 255)
    return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
}

private fun contentColorFor(argb: Int): Int {
    return if (relativeLuminance(argb) > 0.56f) 0xFF111111.toInt() else 0xFFF7F5F3.toInt()
}

private fun relativeLuminance(argb: Int): Float {
    fun channel(value: Int): Float {
        val normalized = value / 255f
        return if (normalized <= 0.03928f) {
            normalized / 12.92f
        } else {
            ((normalized + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
        }
    }
    val red = channel((argb ushr 16) and 0xFF)
    val green = channel((argb ushr 8) and 0xFF)
    val blue = channel(argb and 0xFF)
    return (0.2126f * red) + (0.7152f * green) + (0.0722f * blue)
}
