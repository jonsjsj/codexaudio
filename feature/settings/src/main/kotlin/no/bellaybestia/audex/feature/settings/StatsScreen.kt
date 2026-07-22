package no.bellaybestia.audex.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import no.bellaybestia.audex.designsystem.CoverImage
import no.bellaybestia.audex.designsystem.ScreenHeader
import no.bellaybestia.audex.domain.settings.BookActivity
import no.bellaybestia.audex.domain.settings.KindStats

/**
 * "Your activity" — listening AND reading time, computed from Audex's own local
 * ledger. Tap a section to see the per-book breakdown that makes up its total.
 */
@Composable
fun StatsScreen(
    modifier: Modifier = Modifier,
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val stats by viewModel.stats.collectAsState()
    val listenBooks by viewModel.listenBooks.collectAsState()
    val readBooks by viewModel.readBooks.collectAsState()
    val loading by viewModel.loading.collectAsState()

    Column(modifier.fillMaxSize()) {
        ScreenHeader(title = "Your activity")
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

        when {
            loading -> Message("Loading…")
            stats == null -> Message("No activity yet — connect a server and start listening or reading.")
            else -> stats?.let { s ->
                LazyColumn(Modifier.fillMaxSize()) {
                    item(key = "listen") {
                        ActivitySection(
                            label = "Listened",
                            icon = Icons.Outlined.Headphones,
                            stats = s.listen,
                            books = listenBooks,
                        )
                    }
                    item(key = "read") {
                        ActivitySection(
                            label = "Read",
                            icon = Icons.Outlined.MenuBook,
                            stats = s.read,
                            books = readBooks,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivitySection(
    label: String,
    icon: ImageVector,
    stats: KindStats,
    books: List<BookActivity>,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        // Header: label + this-week / all-time, tappable to reveal the books.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text(text = label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "This week ${fmt(stats.weekSeconds)} · All time ${fmt(stats.totalSeconds)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Today ${fmt(stats.todaySeconds)} · ${stats.daysActive} days · ${stats.books} books",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Hide books" else "Show books",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            if (books.isEmpty()) {
                Text(
                    text = "Nothing here yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
            } else {
                books.forEach { book -> BookRow(book) }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun BookRow(book: BookActivity) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CoverImage(
            url = book.coverUrl,
            contentDescription = null,
            modifier = Modifier.size(width = 34.dp, height = 50.dp),
        )
        Text(
            text = book.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = fmt(book.seconds),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}

/** Seconds → "Xh Ym" / "Ym" / "<1m" / "0m". */
private fun fmt(seconds: Long): String {
    if (seconds <= 0) return "0m"
    val totalMin = seconds / 60
    if (totalMin < 1) return "<1m"
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
