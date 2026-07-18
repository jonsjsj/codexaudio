package no.bellaybestia.audex.feature.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import no.bellaybestia.audex.designsystem.CoverImage

/**
 * Compact now-playing bar shown above the bottom tabs whenever something is
 * loaded. Flat: a 1dp hairline top divider, title + play/pause, tap to expand
 * to the full player. Renders nothing when idle.
 */
@Composable
fun MiniPlayer(
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    if (!state.hasItem) return

    Column(modifier.fillMaxWidth()) {
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onExpand)
                .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverImage(
                url = state.coverUrl,
                contentDescription = null,
                modifier = Modifier.size(width = 32.dp, height = 48.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.title.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Where you ARE beats who wrote it (mockup 2a shows the chapter
                // under the title); fall back to the author when a book has no
                // chapter markers.
                (state.currentChapter?.title ?: state.author)?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Skip controls right on the mini bar — so you can nudge back/forward
            // without opening the full player. clickable(onClick) on the icons
            // themselves keeps taps off the row's expand handler.
            IconButton(onClick = viewModel::skipBackward) {
                Icon(
                    imageVector = Icons.Filled.Replay10,
                    contentDescription = "Back 10 seconds",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = viewModel::togglePlayPause) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = viewModel::skipForward) {
                Icon(
                    imageVector = Icons.Filled.Forward30,
                    contentDescription = "Forward 30 seconds",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
