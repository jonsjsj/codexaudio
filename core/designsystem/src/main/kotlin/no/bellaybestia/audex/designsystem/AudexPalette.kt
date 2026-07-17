package no.bellaybestia.audex.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/**
 * The "Evolved" token set, lifted from the redesign mockup: one palette derived
 * from the CURRENT BOOK'S COVER, re-tinting the whole app.
 *
 * Eight tokens, exactly as the mockup names them:
 *   bg   page background        sf   card/surface       sf2  raised surface
 *   line hairline/border        acc  accent             onAcc contrast on accent
 *   tx   primary text           mut  muted text
 *
 * Every palette in the mockup is a single HUE run through fixed saturation /
 * lightness steps — reverse-engineered from its three reference books:
 *
 *   Steel World  hue 214  bg #101419  sf #171d25  acc #82aeda  tx #eef2f7
 *   Dust World   hue  28  bg #171210  sf #201913  acc #dc9a5f  tx #f6f0ea
 *   Tech World   hue 161  bg #0e1513  sf #151f1c  acc #5fd0ab  tx #ecf5f1
 *
 * So one cover hue reproduces the reference palettes and generalises to any
 * cover — which is what makes "dynamic cover-derived accent everywhere" work.
 */
data class AudexPalette(
    val bg: Color,
    val sf: Color,
    val sf2: Color,
    val line: Color,
    val acc: Color,
    val onAcc: Color,
    val tx: Color,
    val mut: Color,
)

/** Steel World — the mockup's default, used until a cover hue is known. */
const val STEEL_HUE = 214f

/** HSL → Color. [h] degrees 0..360, [s]/[l] 0..1. */
internal fun hsl(h: Float, s: Float, l: Float): Color {
    val c = (1f - abs(2f * l - 1f)) * s
    val hp = ((h % 360f) + 360f) % 360f / 60f
    val x = c * (1f - abs(hp % 2f - 1f))
    val (r1, g1, b1) = when {
        hp < 1f -> Triple(c, x, 0f)
        hp < 2f -> Triple(x, c, 0f)
        hp < 3f -> Triple(0f, c, x)
        hp < 4f -> Triple(0f, x, c)
        hp < 5f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = l - c / 2f
    return Color((r1 + m).coerceIn(0f, 1f), (g1 + m).coerceIn(0f, 1f), (b1 + m).coerceIn(0f, 1f))
}

/**
 * Build the full token set for [hue]. The S/L constants are the mockup's —
 * changing them changes the whole app's character, so treat them as the design.
 */
fun audexPalette(hue: Float, dark: Boolean): AudexPalette = if (dark) {
    AudexPalette(
        bg = hsl(hue, 0.22f, 0.075f),
        sf = hsl(hue, 0.24f, 0.115f),
        sf2 = hsl(hue, 0.24f, 0.17f),
        line = hsl(hue, 0.21f, 0.21f),
        acc = hsl(hue, 0.55f, 0.68f),
        onAcc = hsl(hue, 0.32f, 0.07f),
        tx = hsl(hue, 0.35f, 0.95f),
        mut = hsl(hue, 0.14f, 0.62f),
    )
} else {
    AudexPalette(
        bg = hsl(hue, 0.40f, 0.96f),
        sf = Color.White,
        sf2 = hsl(hue, 0.33f, 0.92f),
        line = hsl(hue, 0.25f, 0.87f),
        acc = hsl(hue, 0.42f, 0.41f),
        onAcc = Color.White,
        tx = hsl(hue, 0.22f, 0.11f),
        mut = hsl(hue, 0.11f, 0.41f),
    )
}

/**
 * Map the tokens onto Material3 so every existing screen re-tints for free —
 * the app already reads these roles, so the palette flows everywhere without
 * touching call sites.
 */
fun AudexPalette.toColorScheme(dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = acc,
        onPrimary = onAcc,
        secondary = acc,
        onSecondary = onAcc,
        background = bg,
        onBackground = tx,
        surface = sf,
        onSurface = tx,
        surfaceVariant = sf2,
        onSurfaceVariant = mut,
        outline = line,
        outlineVariant = line,
    )
}
