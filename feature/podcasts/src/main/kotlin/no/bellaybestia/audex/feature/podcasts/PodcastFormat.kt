package no.bellaybestia.audex.feature.podcasts

import android.text.format.DateUtils
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/** "1h 23m" / "42m" from a duration in seconds; null when unknown. */
internal fun formatDuration(seconds: Double?): String? {
    val s = seconds?.toLong() ?: return null
    if (s <= 0) return null
    val h = s / 3600
    val m = (s % 3600) / 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m"
        else -> "${s}s"
    }
}

/** A short relative date ("3 days ago") from the parsed epoch, else the raw pubDate. */
internal fun relativeDate(publishedAt: Long?, pubDate: String?): String? {
    if (publishedAt != null && publishedAt > 0) {
        return DateUtils.getRelativeTimeSpanString(
            publishedAt,
            System.currentTimeMillis(),
            DateUtils.DAY_IN_MILLIS,
        ).toString()
    }
    return pubDate?.takeIf { it.isNotBlank() }
}

/** The app's hairline divider (matches the flat lists in Library/Downloads). */
@Composable
internal fun FlatDivider() {
    HorizontalDivider(
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}
