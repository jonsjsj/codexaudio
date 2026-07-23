package no.bellaybestia.audex.feature.podcasts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
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
import no.bellaybestia.audex.domain.model.Episode
import no.bellaybestia.audex.domain.model.Podcast

/**
 * A subscribed podcast: cover + title header, the server-side auto-download
 * ("subscription") toggle, a manual "check for new episodes", and the episode
 * list. Tapping an episode plays it through the shared player (episodeId flows
 * into the ABS session so listening is accounted per episode).
 */
@Composable
fun PodcastDetailScreen(
    onBack: () -> Unit = {},
    viewModel: PodcastDetailViewModel = hiltViewModel(),
) {
    val podcast by viewModel.podcast.collectAsState()
    val episodes by viewModel.episodes.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    val message by viewModel.message.collectAsState()
    val listState = rememberLazyListState()

    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
        item {
            Header(podcast = podcast)
        }
        item {
            podcast?.let {
                SubscriptionControls(
                    podcast = it,
                    refreshing = refreshing,
                    onToggleAutoDownload = viewModel::setAutoDownload,
                    onCheckNew = viewModel::checkNewEpisodes,
                )
            }
        }
        message?.let {
            item {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        item { FlatDivider() }
        if (episodes.isEmpty()) {
            item {
                Text(
                    text = if (refreshing) "Loading episodes…" else "No episodes yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            item { SectionLabel("EPISODES · ${episodes.size}") }
            items(episodes, key = { it.episodeId }) { episode ->
                EpisodeRow(episode = episode, onClick = { viewModel.playEpisode(episode) })
                FlatDivider()
            }
        }
    }
}

@Composable
private fun Header(podcast: Podcast?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 12.dp),
    ) {
        CoverImage(
            url = podcast?.coverUrl,
            contentDescription = podcast?.title,
            modifier = Modifier.size(96.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = podcast?.title ?: "Podcast",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            podcast?.author?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** The server-side "subscription" controls: auto-download toggle + check-new. */
@Composable
private fun SubscriptionControls(
    podcast: Podcast,
    refreshing: Boolean,
    onToggleAutoDownload: (Boolean) -> Unit,
    onCheckNew: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.CloudDownload,
            contentDescription = null,
            tint = if (podcast.autoDownload) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "Auto-download new episodes",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "The server pulls new episodes as they publish.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        // Flat text toggle (the design rules avoid pill/filled switches).
        Text(
            text = if (podcast.autoDownload) "ON" else "OFF",
            style = MaterialTheme.typography.titleSmall.copy(letterSpacing = 1.5.sp),
            fontWeight = FontWeight.Bold,
            color = if (podcast.autoDownload) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clickable { onToggleAutoDownload(!podcast.autoDownload) }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
    Text(
        text = if (refreshing) "Checking…" else "Check for new episodes",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clickable(enabled = !refreshing, onClick = onCheckNew)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.8.sp),
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun EpisodeRow(episode: Episode, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = episode.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (episode.isFinished) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                )
                val meta = buildList {
                    relativeDate(episode.publishedAt, episode.pubDate)?.let { add(it) }
                    formatDuration(episode.durationS)?.let { add(it) }
                    if (episode.isFinished) add("Played")
                    else if (episode.inProgress) add("In progress")
                }.joinToString("  ·  ")
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Icon(
                imageVector = if (episode.isFinished) Icons.Outlined.CheckCircle else Icons.Filled.PlayArrow,
                contentDescription = if (episode.isFinished) "Played" else "Play",
                tint = if (episode.isFinished) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp),
            )
        }
        if (episode.inProgress) {
            Spacer(Modifier.height(8.dp))
            ProgressLine(fraction = episode.progressFraction.toFloat())
        }
    }
}

/** A flat 3dp progress line (not a pill), matching the cover ribbon. */
@Composable
private fun ProgressLine(fraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(3.dp)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}
