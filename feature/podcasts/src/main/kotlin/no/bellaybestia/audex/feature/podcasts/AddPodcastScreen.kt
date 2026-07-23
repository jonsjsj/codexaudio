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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.LaunchedEffect
import no.bellaybestia.audex.designsystem.CoverImage
import no.bellaybestia.audex.designsystem.ScreenHeader
import no.bellaybestia.audex.domain.model.PodcastFeedPreview
import no.bellaybestia.audex.domain.model.PodcastLibraryTarget
import no.bellaybestia.audex.domain.model.PodcastSearchResult

/**
 * The subscribe flow (docs/03 §3.8): search the server's podcast index (or paste
 * an RSS URL) → preview the feed → confirm a target podcast library and
 * auto-download → POST /api/podcasts. Gated on a podcast library existing and
 * the account having upload permission.
 */
@Composable
fun AddPodcastScreen(
    onSubscribed: () -> Unit,
    viewModel: AddPodcastViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.done) {
        if (state.done) onSubscribed()
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = "Add podcast", subtitle = "Search or paste an RSS feed")

        val confirm = state.confirm
        when {
            state.targetsLoaded && state.targets.isEmpty() -> NoLibraryMessage()
            confirm != null -> ConfirmSection(
                target = state.selectedTarget,
                loadingPreview = confirm.loadingPreview,
                preview = confirm.preview,
                autoDownload = confirm.autoDownload,
                subscribing = state.subscribing,
                error = state.error,
                onToggleAutoDownload = viewModel::setAutoDownload,
                onCancel = viewModel::cancelConfirm,
                onSubscribe = viewModel::subscribe,
            )
            else -> SearchSection(
                query = state.query,
                searching = state.searching,
                results = state.results,
                targets = state.targets,
                selectedTargetIndex = state.selectedTargetIndex,
                error = state.error,
                onQueryChange = viewModel::setQuery,
                onSubmit = viewModel::submit,
                onSelectTarget = viewModel::selectTarget,
                onChoose = viewModel::choose,
            )
        }
    }
}

@Composable
private fun SearchSection(
    query: String,
    searching: Boolean,
    results: List<PodcastSearchResult>,
    targets: List<PodcastLibraryTarget>,
    selectedTargetIndex: Int,
    error: String?,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onSelectTarget: (Int) -> Unit,
    onChoose: (PodcastSearchResult) -> Unit,
) {
    SearchField(query = query, onQueryChange = onQueryChange, onSubmit = onSubmit)
    FlatDivider()
    if (targets.size > 1) {
        TargetSelector(targets = targets, selectedIndex = selectedTargetIndex, onSelect = onSelectTarget)
        FlatDivider()
    }
    if (error != null) {
        Text(
            text = error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
    if (searching) {
        LoadingRow("Searching…")
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(results, key = { it.feedUrl }) { result ->
            ResultRow(result = result, onClick = { onChoose(result) })
            FlatDivider()
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, onSubmit: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = "Podcast name or RSS URL",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            text = "Go",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable(onClick = onSubmit)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun TargetSelector(
    targets: List<PodcastLibraryTarget>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = "ADD TO",
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.6.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        targets.forEachIndexed { index, target ->
            val active = index == selectedIndex
            Text(
                text = "${target.serverName} · ${target.libraryName}" + if (!target.canSubscribe) "  (read-only)" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(index) }
                    .padding(vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun ResultRow(result: PodcastSearchResult, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(
            url = result.coverUrl,
            contentDescription = result.title,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = result.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            result.author?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun ConfirmSection(
    target: PodcastLibraryTarget?,
    loadingPreview: Boolean,
    preview: PodcastFeedPreview?,
    autoDownload: Boolean,
    subscribing: Boolean,
    error: String?,
    onToggleAutoDownload: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onSubscribe: () -> Unit,
) {
    if (loadingPreview || preview == null) {
        LoadingRow("Reading feed…")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(16.dp)) {
                CoverImage(
                    url = preview.imageUrl,
                    contentDescription = preview.title,
                    modifier = Modifier.size(88.dp),
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = preview.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    preview.author?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Text(
                        text = if (preview.numEpisodes == 1) "1 episode" else "${preview.numEpisodes} episodes",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        preview.description?.takeIf { it.isNotBlank() }?.let { desc ->
            item {
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        item {
            // Auto-download toggle (flat text switch).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Auto-download new episodes",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "The server checks the feed and pulls new episodes automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = if (autoDownload) "ON" else "OFF",
                    style = MaterialTheme.typography.titleSmall.copy(letterSpacing = 1.5.sp),
                    fontWeight = FontWeight.Bold,
                    color = if (autoDownload) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable { onToggleAutoDownload(!autoDownload) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        item {
            target?.let {
                Text(
                    text = "Adds to ${it.serverName} · ${it.libraryName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        if (error != null) {
            item {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        item {
            val canSubscribe = target?.canSubscribe == true
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Cancel",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable(enabled = !subscribing, onClick = onCancel)
                        .padding(vertical = 6.dp),
                )
                Text(
                    text = when {
                        subscribing -> "Subscribing…"
                        !canSubscribe -> "Read-only account"
                        else -> "Subscribe"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (canSubscribe && !subscribing) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable(enabled = canSubscribe && !subscribing, onClick = onSubscribe)
                        .padding(vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun LoadingRow(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NoLibraryMessage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "No podcast library",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "None of your connected servers has a podcast library. Create one in Audiobookshelf (with a folder), then come back to subscribe.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
