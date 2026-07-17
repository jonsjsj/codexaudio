package no.bellaybestia.audex.designsystem

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

/**
 * The mockup's pairing: Space Grotesk for display/headline/title (the
 * characterful face that gives the app its voice) over Roboto for body and
 * labels (the system face — keeps long text plain and readable).
 *
 * Space Grotesk ships as a variable font, so each weight is the one file with
 * a weight axis setting (minSdk 26 supports font variation settings).
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun spaceGrotesk(weight: Int) = Font(
    resId = R.font.space_grotesk,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val SpaceGrotesk = FontFamily(
    spaceGrotesk(400),
    spaceGrotesk(500),
    spaceGrotesk(700),
)

/**
 * Display/headline/title → Space Grotesk (tightened tracking: it's a display
 * face and looks loose at large sizes with default spacing). Body/label keep
 * Material's Roboto defaults.
 */
val AudexTypography: Typography = Typography().let { d ->
    d.copy(
        displayLarge = d.displayLarge.copy(fontFamily = SpaceGrotesk),
        displayMedium = d.displayMedium.copy(fontFamily = SpaceGrotesk),
        displaySmall = d.displaySmall.copy(fontFamily = SpaceGrotesk),
        headlineLarge = d.headlineLarge.copy(fontFamily = SpaceGrotesk),
        headlineMedium = d.headlineMedium.copy(fontFamily = SpaceGrotesk),
        headlineSmall = d.headlineSmall.copy(fontFamily = SpaceGrotesk),
        titleLarge = d.titleLarge.copy(fontFamily = SpaceGrotesk),
        titleMedium = d.titleMedium.copy(fontFamily = SpaceGrotesk),
        titleSmall = d.titleSmall.copy(fontFamily = SpaceGrotesk),
    )
}
