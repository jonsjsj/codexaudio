package no.bellaybestia.audex.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/**
 * Cover-art tile: the caller sizes it; a plain surfaceVariant block with a
 * small book glyph stands in when there's no URL or while the image loads.
 * A subtle 6dp corner (Codex's card radius) — the flat rule forbids pill
 * CONTROLS, not softened imagery. No elevation.
 *
 * [progress] (0..1) draws a thin accent ribbon pinned to the bottom edge — a
 * flat line, not a pill — so how far along a book is reads at a glance from the
 * cover itself. Null or 0 hides it.
 *
 * [hasAudio]/[hasEbook] add a small format badge to the top-right corner
 * (headphones / book glyphs) so you can tell at a glance whether a book has an
 * audiobook, an ebook, or both — without opening it. Both false hides it.
 */
@Composable
fun CoverImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    hasAudio: Boolean = false,
    hasEbook: Boolean = false,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.MenuBook,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Format badge, top-right: which formats this book has.
        if (hasAudio || hasEbook) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xB3000000))
                    .padding(horizontal = 4.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (hasAudio) {
                    Icon(
                        imageVector = Icons.Outlined.Headphones,
                        contentDescription = "Has audiobook",
                        tint = Color(0xFFF4F6FA),
                        modifier = Modifier.size(12.dp),
                    )
                }
                if (hasEbook) {
                    Icon(
                        imageVector = Icons.Outlined.MenuBook,
                        contentDescription = "Has ebook",
                        tint = Color(0xFFF4F6FA),
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
        val fraction = progress?.coerceIn(0f, 1f) ?: 0f
        if (fraction > 0f) {
            // Track + accent fill, both flat 3dp lines across the cover's foot.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color(0x99000000)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(3.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}
