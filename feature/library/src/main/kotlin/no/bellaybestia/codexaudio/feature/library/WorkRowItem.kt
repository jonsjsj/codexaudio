package no.bellaybestia.codexaudio.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import no.bellaybestia.codexaudio.domain.model.Work

/** "2.0" → "2", "2.5" → "2.5" — for "series #position" sublines. */
internal fun formatSeriesPosition(position: Double): String =
    if (position % 1.0 == 0.0) position.toInt().toString() else position.toString()

/**
 * Flat work row: title, "author · series #position · year" subline, and dual
 * progress — a thin 2dp listen bar (only when the work has an audio edition)
 * and a thin 2dp read bar (only when it has an ebook edition).
 */
@Composable
fun WorkRowItem(
    work: Work,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val rowModifier = if (onClick != null) {
        modifier.fillMaxWidth().clickable(onClick = onClick)
    } else {
        modifier.fillMaxWidth()
    }
    Column(
        modifier = rowModifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = work.title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val subline = buildList {
            work.authorName?.takeIf { it.isNotBlank() }?.let { add(it) }
            work.seriesName?.takeIf { it.isNotBlank() }?.let { series ->
                val position = work.seriesPosition
                    ?.let { " #${formatSeriesPosition(it)}" }
                    .orEmpty()
                add(series + position)
            }
            work.year?.let { add(it.toString()) }
        }.joinToString(" · ")
        if (subline.isNotEmpty()) {
            Text(
                text = subline,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (work.hasAudio) {
            FormatProgressBar(
                icon = Icons.Outlined.Headphones,
                contentDescription = "Listen progress",
                fraction = work.listenFraction,
            )
        }
        if (work.hasEbook) {
            FormatProgressBar(
                icon = Icons.Outlined.MenuBook,
                contentDescription = "Read progress",
                fraction = work.readFraction,
            )
        }
    }
}

@Composable
private fun FormatProgressBar(
    icon: ImageVector,
    contentDescription: String,
    fraction: Double,
) {
    val clamped = fraction.coerceIn(0.0, 1.0).toFloat()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .weight(1f)
                .height(2.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(clamped)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${(clamped * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
