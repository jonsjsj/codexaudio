package no.bellaybestia.codexaudio.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The Codex look: flat, dark-first, one accent color, hairline dividers.
 * No pill/bubble shapes anywhere — see docs/02-codex-learnings.md.
 */
private val Accent = Color(0xFF4C9EEB)

private val DarkColors = darkColorScheme(
    primary = Accent,
    secondary = Accent,
    background = Color(0xFF101216),
    surface = Color(0xFF101216),
    surfaceVariant = Color(0xFF171A20),
    outlineVariant = Color(0xFF262B33),
)

private val LightColors = lightColorScheme(
    primary = Accent,
    secondary = Accent,
)

@Composable
fun CodexAudioTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
