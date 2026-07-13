package no.bellaybestia.audex.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlin.math.roundToInt
import no.bellaybestia.audex.domain.model.Edition
import no.bellaybestia.audex.domain.model.Format

/**
 * Work detail: title/author header + a flat "editions" card — one row per
 * edition (format, position %, and a Play/Pause or Read action), modeled on
 * Codex's EditionSyncCard (docs/02). Audio Play opens an ABS playback session
 * through the Media3 service; ebook Read arrives with the reader in Phase 2.
 */
@Composable
fun WorkDetailScreen(
    onOpenReader: (serverId: String, libraryItemId: String, title: String) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier,
    viewModel: WorkDetailViewModel = hiltViewModel(),
) {
    val editions by viewModel.editions.collectAsState()
    val playback by viewModel.playback.collectAsState()
    val downloadStates by viewModel.downloadStates.collectAsState()

    Column(modifier.fillMaxSize()) {
        Text(
            text = viewModel.title,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 2.dp),
        )
        viewModel.author?.let { author ->
            Text(
                text = author,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        Text(
            text = "EDITIONS",
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
        )
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

        if (editions.isEmpty()) {
            Text(
                text = "No editions yet — connect a server and sync your library.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            editions.forEachIndexed { index, edition ->
                val isThis = playback.libraryItemId == edition.libraryItemId
                val download = downloadStates.firstOrNull {
                    it.serverId == edition.serverId &&
                        it.libraryItemId == edition.libraryItemId &&
                        it.format.name == edition.format.name
                }
                EditionRow(
                    edition = edition,
                    isPlayingThis = isThis && playback.isPlaying,
                    isLoadingThis = isThis && playback.isLoading,
                    download = download,
                    onPlay = { viewModel.play(edition) },
                    onTogglePlayPause = { viewModel.togglePlayPause() },
                    onDownload = { viewModel.download(edition) },
                    onRemoveDownload = { viewModel.removeDownload(edition) },
                    onRead = { onOpenReader(edition.serverId, edition.libraryItemId, viewModel.title) },
                )
                if (index < editions.lastIndex) {
                    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun EditionRow(
    edition: Edition,
    isPlayingThis: Boolean,
    isLoadingThis: Boolean,
    download: no.bellaybestia.audex.domain.download.DownloadInfo?,
    onPlay: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
    onRead: () -> Unit,
) {
    val isAudio = edition.format == Format.AUDIO
    val icon = if (isAudio) Icons.Outlined.Headphones else Icons.Outlined.MenuBook
    val label = if (isAudio) "Audiobook" else "Ebook"
    val percent = (edition.fraction.coerceIn(0.0, 1.0) * 100).roundToInt()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer16()
                Text(text = label, style = MaterialTheme.typography.bodyLarge)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(edition.fraction.coerceIn(0.0, 1.0).toFloat())
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
                Spacer16()
                Text(
                    text = "$percent%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer16()

        // Actions (flat accent text — no filled buttons per design rules).
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (isAudio) {
                val actionLabel = when {
                    isLoadingThis -> "Loading…"
                    isPlayingThis -> "Pause"
                    else -> "Play"
                }
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable(enabled = !isLoadingThis) {
                            if (isPlayingThis) onTogglePlayPause() else onPlay()
                        }
                        .padding(vertical = 4.dp, horizontal = 4.dp),
                )
            } else {
                // Ebook is readable once downloaded (offline reader).
                val readable = download?.isComplete == true
                Text(
                    text = "Read",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (readable) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .then(if (readable) Modifier.clickable(onClick = onRead) else Modifier)
                        .padding(vertical = 4.dp, horizontal = 4.dp),
                )
            }

            val downloadLabel = when {
                download == null -> "Download"
                download.isActive -> "Downloading…"
                download.isComplete -> "Saved ✓"
                else -> "Retry"
            }
            val downloadClick: (() -> Unit)? = when {
                download?.isActive == true -> null
                download?.isComplete == true -> onRemoveDownload
                else -> onDownload
            }
            Text(
                text = downloadLabel,
                style = MaterialTheme.typography.labelMedium,
                color = if (download?.isActive == true) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .then(if (downloadClick != null) Modifier.clickable(onClick = downloadClick) else Modifier)
                    .padding(vertical = 4.dp, horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun Spacer16() = androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
