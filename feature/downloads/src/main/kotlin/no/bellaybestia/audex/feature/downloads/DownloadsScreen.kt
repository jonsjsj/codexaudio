package no.bellaybestia.audex.feature.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import no.bellaybestia.audex.designsystem.CoverImage
import no.bellaybestia.audex.designsystem.ScreenHeader
import no.bellaybestia.audex.domain.download.DownloadInfo

@Composable
fun DownloadsScreen(
    onOpenWork: (workId: String, title: String, author: String?) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsState()
    val summary by viewModel.summary.collectAsState()
    val listState = rememberLazyListState()

    Column(modifier.fillMaxSize()) {
        // "2.1 GB on device · Main + Backup" (mockup 2d) — real size and the
        // servers it came from, rather than a bare count.
        ScreenHeader(title = "Downloads", subtitle = summary)
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No downloads yet — open a book and tap Download to keep it offline.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(32.dp),
                )
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(items, key = { "${it.info.serverId}|${it.info.libraryItemId}|${it.info.format}" }) { display ->
                    DownloadRow(
                        info = display.info,
                        coverUrl = display.coverUrl,
                        onRemove = { viewModel.remove(display.info) },
                        onOpen = display.workId?.let { wid ->
                            { onOpenWork(wid, display.info.title.orEmpty(), display.author) }
                        },
                    )
                    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(info: DownloadInfo, coverUrl: String?, onRemove: () -> Unit, onOpen: (() -> Unit)?) {
    val running = info.state == "RUNNING" && info.bytesTotal > 0
    val fraction = if (info.bytesTotal > 0) (info.bytesDone.toFloat() / info.bytesTotal).coerceIn(0f, 1f) else 0f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(
            url = coverUrl,
            contentDescription = null,
            modifier = Modifier.size(width = 44.dp, height = 60.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = info.title ?: info.libraryItemId,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // "Audio · 342 MB · Main" style subline (mockup 2d).
            val format = if (info.format.name == "AUDIO") "Audio" else "EPUB"
            val size = (if (info.bytesTotal > 0) info.bytesTotal else info.bytesDone)
                .takeIf { it > 0 }?.let { formatBytes(it) }
            Text(
                text = listOfNotNull(format, size).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Active transfer: a thin 2dp progress bar (mockup 2d).
            if (running) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
        // State on the right (mockup 2d): "On device" / "62%" / "Queued".
        // Remove is a two-tap confirm so a mis-tap can't delete a 300MB download.
        var armed by remember { mutableStateOf(false) }
        val state = when {
            info.isComplete -> "On device"
            info.state == "RUNNING" && info.bytesTotal > 0 ->
                "${((info.bytesDone.toDouble() / info.bytesTotal) * 100).roundToInt()}%"
            info.state == "RUNNING" -> "Downloading…"
            info.state == "QUEUED" -> "Queued"
            else -> "Failed"
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = state,
                style = MaterialTheme.typography.labelLarge,
                color = if (info.isComplete) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.primary,
            )
            Text(
                text = if (armed) "Remove?" else "Remove",
                style = MaterialTheme.typography.labelMedium,
                color = if (armed) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable { if (armed) onRemove() else armed = true }
                    .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
            )
        }
    }
}
