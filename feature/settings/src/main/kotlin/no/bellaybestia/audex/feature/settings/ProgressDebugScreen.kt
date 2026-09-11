package no.bellaybestia.audex.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import no.bellaybestia.audex.domain.model.ProgressDebugRow

/**
 * Raw dump of the local `progress` table (title, format, pct, currentTimeS,
 * ebookProgress, source, lastUpdate) — most recently touched first. Exists so a
 * "position reset" report can be pinned to the actual DB values (a genuinely
 * wrong write vs. a display-only misread) instead of guessing from behavior
 * alone. No editing here, read-only.
 */
@Composable
fun ProgressDebugScreen(
    modifier: Modifier = Modifier,
    viewModel: ProgressDebugViewModel = hiltViewModel(),
) {
    val rows by viewModel.rows.collectAsState()
    val fmt = remember { SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault()) }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item(key = "header") {
            Column(Modifier.padding(16.dp)) {
                Text(text = "Progress debug", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "${rows.size} rows, most recently touched first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(rows, key = { "${it.serverId}|${it.libraryItemId}" }) { row ->
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            ProgressDebugRowItem(row, fmt)
        }
        item(key = "tail") { Column(Modifier.padding(16.dp)) {} }
    }
}

@Composable
private fun ProgressDebugRowItem(row: ProgressDebugRow, fmt: SimpleDateFormat) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row {
            Text(
                text = row.title ?: "(no title — orphaned row)",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            row.format?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = "source=${row.source}  lastUpdate=${fmt.format(Date(row.lastUpdate))}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "pct=${"%.4f".format(row.pct)}  currentTimeS=${row.currentTimeS ?: "null"}  " +
                "ebookProgress=${row.ebookProgress ?: "null"}  isFinished=${row.isFinished}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = "${row.serverId} / ${row.libraryItemId}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
