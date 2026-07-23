package no.bellaybestia.audex.feature.podcasts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import no.bellaybestia.audex.designsystem.CoverImage
import no.bellaybestia.audex.designsystem.ScreenHeader
import no.bellaybestia.audex.domain.model.Podcast

/**
 * The Podcasts tab: subscribed podcasts as a flat hairline list, an "Add" row
 * that opens the subscribe flow, and a Refresh action. Podcasts are a pipeline
 * parallel to the book catalog — this screen never touches Authors/Series/Works.
 */
@Composable
fun PodcastsScreen(
    onAddClick: () -> Unit,
    onPodcastClick: (serverId: String, itemId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PodcastsViewModel = hiltViewModel(),
) {
    val podcasts by viewModel.podcasts.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    val listState = rememberLazyListState()

    Column(modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Podcasts",
            subtitle = when (podcasts.size) {
                0 -> null
                1 -> "1 subscription"
                else -> "${podcasts.size} subscriptions"
            },
        )
        ActionRow(
            refreshing = refreshing,
            onAddClick = onAddClick,
            onRefresh = viewModel::refresh,
        )
        FlatDivider()
        if (podcasts.isEmpty()) {
            EmptyState(onAddClick = onAddClick)
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(podcasts, key = { "${it.serverId}|${it.libraryItemId}" }) { podcast ->
                    PodcastRow(podcast = podcast, onClick = { onPodcastClick(podcast.serverId, podcast.libraryItemId) })
                    FlatDivider()
                }
            }
        }
    }
}

@Composable
private fun ActionRow(refreshing: Boolean, onAddClick: () -> Unit, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onAddClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Subscribe to a podcast",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = if (refreshing) "Refreshing…" else "Refresh",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clickable(enabled = !refreshing, onClick = onRefresh)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
        Icon(
            imageVector = Icons.Outlined.Refresh,
            contentDescription = "Refresh",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun PodcastRow(podcast: Podcast, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(
            url = podcast.coverUrl,
            contentDescription = podcast.title,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = podcast.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subline = buildList {
                podcast.author?.takeIf { it.isNotBlank() }?.let { add(it) }
                add(if (podcast.numEpisodes == 1) "1 episode" else "${podcast.numEpisodes} episodes")
            }.joinToString("  ·  ")
            Text(
                text = subline,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (podcast.autoDownload) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CloudDownload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "AUTO-DOWNLOAD",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(onAddClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.Podcasts,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "No podcasts yet",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Subscribe to a podcast and its episodes appear here. Auto-download lets the server pull new episodes for you.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Box(
            modifier = Modifier
                .clickable(onClick = onAddClick)
                .padding(vertical = 8.dp),
        ) {
            Text(
                text = "Subscribe to a podcast",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
