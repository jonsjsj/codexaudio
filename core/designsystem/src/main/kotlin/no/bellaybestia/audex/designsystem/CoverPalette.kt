package no.bellaybestia.audex.designsystem

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cover → hue. The whole "Evolved" look hangs off one number per book: the hue
 * of its cover art, which [audexPalette] expands into the eight tokens.
 *
 * Swatch preference mirrors what the mockup's hand-picked palettes look like —
 * a saturated, characteristic colour rather than the literal average (book
 * covers are mostly dark ink, so the dominant swatch is often near-black and
 * carries no usable hue).
 */
suspend fun coverHue(context: Context, url: String?, loader: ImageLoader): Float? {
    if (url.isNullOrBlank()) return null
    return withContext(Dispatchers.IO) {
        runCatching {
            val req = ImageRequest.Builder(context)
                .data(url)
                .allowHardware(false) // Palette needs to read pixels back
                .size(160) // a thumbnail is plenty for a hue, and keeps it cheap
                .build()
            val bmp = (loader.execute(req) as? SuccessResult)
                ?.drawable
                ?.let { it as? BitmapDrawable }
                ?.bitmap
                ?: return@runCatching null
            hueOf(bmp)
        }.getOrNull()
    }
}

/**
 * The cover's overall hue: a circular mean across every swatch, weighted by
 * population × saturation² — colourful AND common.
 *
 * NOT Palette's vibrantSwatch. Vibrant chases the most saturated colour, which
 * on real covers is often a small badge: Rage World (a blue/teal space scene)
 * tinted the whole app YELLOW off its little "ONLY FROM audible" corner flash.
 * Weighting by population drowns that out; squaring saturation still lets a
 * genuinely colourful cover beat a large dull background. A circular mean
 * (rather than averaging degrees) keeps hues near the 0°/360° wrap honest.
 */
internal fun hueOf(bitmap: Bitmap): Float? {
    val p = Palette.from(bitmap).clearFilters().maximumColorCount(32).generate()
    var x = 0.0
    var y = 0.0
    var total = 0.0
    val hsl = FloatArray(3)
    for (sw in p.swatches) {
        ColorUtils.colorToHSL(sw.rgb, hsl)
        val h = hsl[0]
        val s = hsl[1]
        val l = hsl[2]
        // Greys carry no hue; near-black/white is ink and paper, not colour.
        if (s < 0.15f || l < 0.08f || l > 0.95f) continue
        val w = sw.population.toDouble() * s * s
        val r = Math.toRadians(h.toDouble())
        x += kotlin.math.cos(r) * w
        y += kotlin.math.sin(r) * w
        total += w
    }
    // Nothing colourful enough to tint from — keep the default hue.
    if (total <= 0.0) return null
    val deg = Math.toDegrees(kotlin.math.atan2(y, x))
    return (if (deg < 0) deg + 360.0 else deg).toFloat()
}

/**
 * The live palette for [coverUrl], animated so switching books glides between
 * palettes instead of snapping (the mockup re-tints on book change).
 */
@Composable
fun rememberCoverPalette(
    coverUrl: String?,
    dark: Boolean,
    enabled: Boolean = true,
    fallbackHue: Float = STEEL_HUE,
): AudexPalette {
    val context = LocalContext.current
    val hue by produceState(fallbackHue, coverUrl, enabled) {
        value = if (!enabled) fallbackHue
        else coverHue(context, coverUrl, singletonLoader(context)) ?: fallbackHue
    }
    val target = audexPalette(hue, dark)
    // Cross-fade every token; 500ms reads as a deliberate re-tint, not a flash.
    val spec = androidx.compose.animation.core.tween<androidx.compose.ui.graphics.Color>(500)
    val bg by animateColorAsState(target.bg, spec, label = "bg")
    val sf by animateColorAsState(target.sf, spec, label = "sf")
    val sf2 by animateColorAsState(target.sf2, spec, label = "sf2")
    val line by animateColorAsState(target.line, spec, label = "line")
    val acc by animateColorAsState(target.acc, spec, label = "acc")
    val onAcc by animateColorAsState(target.onAcc, spec, label = "onAcc")
    val tx by animateColorAsState(target.tx, spec, label = "tx")
    val mut by animateColorAsState(target.mut, spec, label = "mut")
    return AudexPalette(bg, sf, sf2, line, acc, onAcc, tx, mut)
}

private var loaderRef: ImageLoader? = null

/** Reuse one loader so cover art isn't decoded twice just to read a hue. */
internal fun singletonLoader(context: Context): ImageLoader =
    loaderRef ?: coil.Coil.imageLoader(context).also { loaderRef = it }
