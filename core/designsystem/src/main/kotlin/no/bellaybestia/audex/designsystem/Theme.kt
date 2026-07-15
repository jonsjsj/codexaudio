package no.bellaybestia.audex.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The Codex look: flat, dark-first, hairline dividers, no pill/bubble shapes.
 * Base palette lifted verbatim from Codex's own CSS tokens (its `:root`):
 *   --bg #0B0B0C  --text #F2F2F3  --text-muted #9A9A9E  --text-faint #636367
 * Surfaces (#141416 card / #2A2A2C raised / #3A3A3D border) are Codex's panel
 * scale. Accents are Codex's palette THEMES (mono / blue / gold) with their
 * matching contrast colors. See docs/02-codex-learnings.md.
 */
private val Bg = Color(0xFF0B0B0C)
private val Card = Color(0xFF141416)
private val Raised = Color(0xFF2A2A2C)
private val Border = Color(0xFF3A3A3D)
private val TextPrimary = Color(0xFFF2F2F3)
private val TextMuted = Color(0xFF9A9A9E)

/** Accent themes, mirroring Codex's `--accent` palette variants. */
enum class AudexAccent(val accent: Color, val onAccent: Color) {
    MONO(Color(0xFFEDEDED), Color(0xFF0B0B0C)),
    BLUE(Color(0xFF5A9FE6), Color(0xFF0B0D12)),
    GOLD(Color(0xFFDCA85F), Color(0xFF15130F)),
}

private fun darkColors(accent: AudexAccent) = darkColorScheme(
    primary = accent.accent,
    onPrimary = accent.onAccent,
    secondary = accent.accent,
    onSecondary = accent.onAccent,
    background = Bg,
    onBackground = TextPrimary,
    surface = Card,
    onSurface = TextPrimary,
    surfaceVariant = Raised,
    onSurfaceVariant = TextMuted,
    outline = Border,
    outlineVariant = Border,
)

// Light mode keeps the same accent hues (darkened contrast handled by onPrimary)
// against a plain paper background; Codex itself is dark-only, so this is a
// faithful inversion rather than a separate design.
private fun lightColors(accent: AudexAccent) = lightColorScheme(
    primary = if (accent == AudexAccent.MONO) Color(0xFF1A1A1C) else accent.accent,
    onPrimary = if (accent == AudexAccent.MONO) Color(0xFFF7F7F8) else accent.onAccent,
    secondary = if (accent == AudexAccent.MONO) Color(0xFF1A1A1C) else accent.accent,
    onSecondary = if (accent == AudexAccent.MONO) Color(0xFFF7F7F8) else accent.onAccent,
    outlineVariant = Color(0xFFDBDBDE),
)

@Composable
fun AudexTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accent: AudexAccent = AudexAccent.BLUE,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) darkColors(accent) else lightColors(accent),
        content = content,
    )
}
