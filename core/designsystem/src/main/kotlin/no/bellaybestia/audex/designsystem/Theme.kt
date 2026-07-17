package no.bellaybestia.audex.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The Audex look ("Evolved", from the redesign): a dark-first, flat, hairline
 * design whose ENTIRE palette is derived from the current book's cover — see
 * [AudexPalette]. Accent is no longer a fixed choice by default; it comes from
 * what you're reading, and re-tints the whole app.
 *
 * The fixed accents stay as manual overrides for anyone who wants the app to
 * hold still (Settings → Appearance → Accent).
 */
enum class AudexAccent(val hue: Float?, val accent: Color, val onAccent: Color) {
    /** Derive everything from the cover — the default. */
    COVER(null, Color(0xFF82AEDA), Color(0xFF0C1219)),
    MONO(null, Color(0xFFEDEDED), Color(0xFF0B0B0C)),
    BLUE(214f, Color(0xFF5A9FE6), Color(0xFF0B0D12)),
    GOLD(38f, Color(0xFFDCA85F), Color(0xFF15130F)),
    CYAN(193f, Color(0xFF4DD0FF), Color(0xFF04121A)),
}

/**
 * [coverUrl] drives the palette when [accent] is COVER; the fixed accents pin
 * the hue instead (MONO stays neutral — a greyscale palette).
 */
@Composable
fun AudexTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accent: AudexAccent = AudexAccent.COVER,
    coverUrl: String? = null,
    content: @Composable () -> Unit,
) {
    val palette = when (accent) {
        AudexAccent.COVER -> rememberCoverPalette(coverUrl = coverUrl, dark = darkTheme)
        AudexAccent.MONO -> monoPalette(darkTheme)
        else -> audexPalette(accent.hue ?: STEEL_HUE, darkTheme)
    }
    MaterialTheme(
        colorScheme = palette.toColorScheme(darkTheme),
        typography = AudexTypography,
        content = content,
    )
}

/** Mono: the same token structure with the colour taken out. */
private fun monoPalette(dark: Boolean): AudexPalette = if (dark) {
    AudexPalette(
        bg = Color(0xFF0B0B0C),
        sf = Color(0xFF141416),
        sf2 = Color(0xFF2A2A2C),
        line = Color(0xFF3A3A3D),
        acc = Color(0xFFEDEDED),
        onAcc = Color(0xFF0B0B0C),
        tx = Color(0xFFF2F2F3),
        mut = Color(0xFF9A9A9E),
    )
} else {
    AudexPalette(
        bg = Color(0xFFF7F7F8),
        sf = Color.White,
        sf2 = Color(0xFFECECEE),
        line = Color(0xFFDBDBDE),
        acc = Color(0xFF1A1A1C),
        onAcc = Color(0xFFF7F7F8),
        tx = Color(0xFF17171A),
        mut = Color(0xFF6D6D72),
    )
}
