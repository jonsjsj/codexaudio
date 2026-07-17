package no.bellaybestia.audex.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A library "poster": a full-bleed 2:3 cover (with an optional progress ribbon)
 * over a title and subtitle. The shelf/grid unit that makes the library read
 * like a media app instead of a settings list. Generic on purpose — takes
 * primitives, not a domain model, so :core:designsystem stays leaf.
 */
@Composable
fun PosterTile(
    coverUrl: String?,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    hasAudio: Boolean = false,
    hasEbook: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val base = if (onClick != null) modifier.clickable(onClick = onClick) else modifier
    Column(
        modifier = base.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CoverImage(
            url = coverUrl,
            contentDescription = title,
            progress = progress,
            hasAudio = hasAudio,
            hasEbook = hasEbook,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}
