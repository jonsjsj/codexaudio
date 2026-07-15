package no.bellaybestia.audex.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The Codex look: flat, dark-first, hairline dividers, no pill/bubble shapes.
 * Palette lifted verbatim from Codex's own CSS tokens (its `:root`), so Audex
 * and Codex read as one product:
 *   --bg #0B0B0C  --text #F2F2F3  --text-muted #9A9A9E  --text-faint #636367
 *   --accent #EDEDED (monochrome)  --accent-contrast #0B0B0C
 * Surfaces (#141416 card / #2A2A2C raised / #3A3A3D border) are Codex's panel
 * scale. See docs/02-codex-learnings.md.
 */
private val Bg = Color(0xFF0B0B0C)
private val Card = Color(0xFF141416)
private val Raised = Color(0xFF2A2A2C)
private val Border = Color(0xFF3A3A3D)
private val TextPrimary = Color(0xFFF2F2F3)
private val TextMuted = Color(0xFF9A9A9E)
private val Accent = Color(0xFFEDEDED)
private val AccentContrast = Color(0xFF0B0B0C)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = AccentContrast,
    secondary = Accent,
    onSecondary = AccentContrast,
    background = Bg,
    onBackground = TextPrimary,
    surface = Card,
    onSurface = TextPrimary,
    surfaceVariant = Raised,
    onSurfaceVariant = TextMuted,
    outline = Border,
    outlineVariant = Border,
)

// Codex is dark-only (`color-scheme: dark`); the light scheme is a faithful
// inverse so the accent stays monochrome rather than reverting to Material blue.
private val LightColors = lightColorScheme(
    primary = Color(0xFF1A1A1C),
    onPrimary = Color(0xFFF7F7F8),
    secondary = Color(0xFF1A1A1C),
    onSecondary = Color(0xFFF7F7F8),
    outlineVariant = Color(0xFFDBDBDE),
)

@Composable
fun AudexTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
