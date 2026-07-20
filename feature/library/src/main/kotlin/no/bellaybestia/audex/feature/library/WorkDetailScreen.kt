package no.bellaybestia.audex.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlin.math.roundToInt
import no.bellaybestia.audex.designsystem.CoverImage
import no.bellaybestia.audex.designsystem.TintFromCover
import no.bellaybestia.audex.domain.model.Edition
import no.bellaybestia.audex.domain.model.Format
import no.bellaybestia.audex.domain.model.Work
import no.bellaybestia.audex.domain.playback.PlaybackState
import no.bellaybestia.audex.domain.reader.WordSyncStatus

/**
 * Work detail: title/author header + a flat "editions" card — one row per
 * edition (format, position %, and a Play/Pause or Read action), modeled on
 * Codex's EditionSyncCard (docs/02). Audio Play opens an ABS playback session
 * through the Media3 service; ebook Read arrives with the reader in Phase 2.
 */
@Composable
fun WorkDetailScreen(
    onOpenReader: (serverId: String, libraryItemId: String, title: String) -> Unit = { _, _, _ -> },
    onAuthorClick: (id: String, name: String) -> Unit = { _, _ -> },
    onSeriesClick: (id: String, name: String) -> Unit = { _, _ -> },
    onOpenWork: (Work) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: WorkDetailViewModel = hiltViewModel(),
) {
    val editions by viewModel.editions.collectAsState()
    val playback by viewModel.playback.collectAsState()
    val downloadStates by viewModel.downloadStates.collectAsState()
    val wordSync by viewModel.wordSync.collectAsState()
    val work by viewModel.work.collectAsState()
    val nextInSeries by viewModel.nextInSeries.collectAsState()
    val description by viewModel.description.collectAsState()
    val extras by viewModel.extras.collectAsState()
    val otherFormat by viewModel.otherFormat.collectAsState()
    val skipSeconds by viewModel.skipSeconds.collectAsState()
    val mergeProgress by viewModel.mergeProgress.collectAsState()
    val furthest by viewModel.furthestS.collectAsState()

    val cover = editions.firstNotNullOfOrNull { it.coverUrl }
    // Browsing a book re-tints the whole app around it (mockup 2c).
    TintFromCover(cover)

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // Top bar (mockup 2c): overflow menu at the right — back is system nav.
        // The "such things" (skip amount, merge, discard) live here, not in a row.
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DetailOverflowMenu(
                skipSeconds = skipSeconds,
                onSkip = viewModel::setSkipSeconds,
                merged = mergeProgress,
                onMerge = viewModel::setMergeProgress,
                canDiscard = editions.any { it.fraction > 0.001 } || (furthest ?: 0.0) > 0.0,
                onDiscard = viewModel::discardProgress,
            )
        }
        // Cover tile beside a metadata column (mockup 2c) — the real cover art,
        // series (clickable) / title / author (clickable) / narrator / meta.
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CoverImage(
                url = cover,
                contentDescription = viewModel.title,
                modifier = Modifier.size(width = 112.dp, height = 152.dp),
            )
            Column(
                Modifier.weight(1f).align(Alignment.CenterVertically),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                work?.seriesName?.let { series ->
                    val pos = work?.seriesPosition?.let { p ->
                        " · #" + if (p % 1.0 == 0.0) p.toInt().toString() else p.toString()
                    }.orEmpty()
                    val seriesId = work?.seriesId
                    Text(
                        text = (series + pos).uppercase(),
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (seriesId != null) {
                            Modifier.clickable { onSeriesClick(seriesId, series) }
                        } else {
                            Modifier
                        },
                    )
                }
                Text(
                    text = viewModel.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                val authorId = work?.authorId
                viewModel.author?.let { author ->
                    Text(
                        text = author,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (authorId != null) {
                            Modifier.clickable { onAuthorClick(authorId, author) }
                        } else {
                            Modifier
                        },
                    )
                }
                extras?.narrator?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = "Narrated by $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val meta = buildList {
                    work?.year?.let { add(it.toString()) }
                    editions.firstOrNull { it.format == Format.AUDIO }?.durationS?.let { s ->
                        add("${s / 3600}h ${(s % 3600) / 60}m")
                    }
                    buildList {
                        if (editions.any { it.format == Format.AUDIO }) add("Audio")
                        if (editions.any { it.format == Format.EBOOK }) add("EPUB")
                    }.joinToString(" + ").takeIf { it.isNotBlank() }?.let { add(it) }
                }.joinToString(" · ")
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Editions block. Normally one compact "sync" row per format; but when
        // "Merge progress" is on (Settings/overflow), a book's audio+ebook share
        // ONE progress bar with both Listen + Read — they follow each other, so a
        // single % is enough.
        val audioEdition = editions.firstOrNull { it.format == Format.AUDIO }
        val ebookEdition = editions.firstOrNull { it.format == Format.EBOOK }
        fun downloadOf(edition: Edition) = downloadStates.firstOrNull {
            it.serverId == edition.serverId &&
                it.libraryItemId == edition.libraryItemId &&
                it.format.name == edition.format.name
        }
        // Primary actions (mockup 2c): Resume (accent) + Read (outline). Detailed
        // per-format progress + download management stay in the editions rows below.
        if (audioEdition != null || ebookEdition != null) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                audioEdition?.let { ae ->
                    val playingThis = playback.libraryItemId == ae.libraryItemId && playback.isPlaying
                    PillButton(
                        text = if (playingThis) "Pause" else "Resume",
                        filled = true,
                        modifier = Modifier.weight(1f),
                        onClick = { if (playingThis) viewModel.togglePlayPause() else viewModel.play(ae) },
                    )
                }
                ebookEdition?.let { ee ->
                    PillButton(
                        text = "Read",
                        filled = false,
                        modifier = Modifier.weight(1f),
                        onClick = { onOpenReader(ee.serverId, ee.libraryItemId, viewModel.title) },
                    )
                }
            }
        }
        if (mergeProgress && audioEdition != null && ebookEdition != null) {
            MergedEditionRow(
                audio = audioEdition,
                ebook = ebookEdition,
                playback = playback,
                audioDownload = downloadOf(audioEdition),
                ebookDownload = downloadOf(ebookEdition),
                onListen = { viewModel.play(audioEdition) },
                onTogglePlayPause = { viewModel.togglePlayPause() },
                onRead = { onOpenReader(ebookEdition.serverId, ebookEdition.libraryItemId, viewModel.title) },
                onGetEbook = { viewModel.download(ebookEdition) },
            )
        } else {
            editions.forEach { edition ->
                val isThis = playback.libraryItemId == edition.libraryItemId
                CompactEditionRow(
                    edition = edition,
                    isPlayingThis = isThis && playback.isPlaying,
                    isLoadingThis = isThis && playback.isLoading,
                    download = downloadOf(edition),
                    onPlay = { viewModel.play(edition) },
                    onTogglePlayPause = { viewModel.togglePlayPause() },
                    onDownload = { viewModel.download(edition) },
                    onRemoveDownload = { viewModel.removeDownload(edition) },
                    onRead = { onOpenReader(edition.serverId, edition.libraryItemId, viewModel.title) },
                )
            }
        }

        // Compact one-liners for the extras (small text, no big rows).
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

        otherFormat?.let { other ->
            val pct = (other.fraction.coerceIn(0.0, 1.0) * 100).roundToInt()
            CompactActionLine(
                text = if (other.toAudio) "Listen from your reading spot ($pct%)"
                else "Read from your listening spot ($pct%)",
                action = if (other.toAudio) "Go" else "Go",
                onClick = {
                    if (other.toAudio) viewModel.continueInAudio(other)
                    else onOpenReader(other.edition.serverId, other.edition.libraryItemId, viewModel.title)
                },
            )
        }

        val furthestFraction = furthest?.let { f ->
            audioEdition?.durationS?.takeIf { it > 0 }?.let { (f / it).coerceIn(0.0, 1.0) }
        }
        if (audioEdition != null && furthestFraction != null &&
            furthestFraction > audioEdition.fraction + 0.01
        ) {
            CompactActionLine(
                text = "Furthest listened · ${(furthestFraction * 100).roundToInt()}%",
                action = "Jump",
                onClick = viewModel::jumpToFurthest,
            )
        }

        nextInSeries?.let { next ->
            val np = next.seriesPosition?.let { p ->
                if (p % 1.0 == 0.0) "#${p.toInt()} " else "#$p "
            }.orEmpty()
            CompactActionLine(
                text = "Next: $np${next.title}",
                action = "Open",
                onClick = { onOpenWork(next) },
            )
        }

        if (wordSync == WordSyncStatus.READY ||
            wordSync == WordSyncStatus.RUNNING ||
            wordSync == WordSyncStatus.NONE
        ) {
            WordSyncRow(status = wordSync, onPrepare = viewModel::requestWordSync)
        }

        description?.let { DescriptionBlock(it) }

        androidx.compose.foundation.layout.Spacer(Modifier.height(24.dp))
    }
}

/** Mockup 2c primary action: a filled (accent) or outlined flat pill. */
@Composable
private fun PillButton(
    text: String,
    filled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(6.dp)
    val shaped = modifier
        .clip(shape)
        .let {
            if (filled) it.background(MaterialTheme.colorScheme.primary)
            else it.border(1.dp, MaterialTheme.colorScheme.outline, shape)
        }
        .clickable(onClick = onClick)
        .padding(vertical = 13.dp)
    Box(shaped, contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * One sync, compact: format label, a thin full-width progress bar with its %,
 * and small text actions on the right (Play/Pause for audio, Read/Get for
 * ebook, plus download). Half the height of the old edition card.
 */
@Composable
private fun CompactEditionRow(
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
    val percent = (edition.fraction.coerceIn(0.0, 1.0) * 100).roundToInt()
    val primaryAction: () -> Unit = when {
        isAudio -> { { if (isPlayingThis) onTogglePlayPause() else onPlay() } }
        download?.isComplete == true -> onRead
        download?.isActive == true -> ({})
        else -> onDownload
    }
    val primaryLabel = when {
        isAudio && isLoadingThis -> "…"
        isAudio && isPlayingThis -> "Pause"
        isAudio -> "Listen"
        download?.isComplete == true -> "Read"
        download?.isActive == true -> "Fetching…"
        else -> "Get"
    }
    val downloadLabel = when {
        download == null -> "Save"
        download.isActive -> "…"
        download.isComplete -> "Saved ✓"
        else -> "Retry"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = primaryAction)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = if (isAudio) Icons.Outlined.Headphones else Icons.Outlined.MenuBook,
            contentDescription = if (isAudio) "Audiobook" else "Ebook",
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // thin progress bar takes the middle
        Box(
            Modifier
                .weight(1f)
                .height(3.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(edition.fraction.coerceIn(0.0, 1.0).toFloat())
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        Text(
            text = "$percent%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = primaryLabel,
            style = MaterialTheme.typography.labelLarge,
            color = if (primaryLabel == "Fetching…") MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(enabled = !isLoadingThis, onClick = primaryAction),
        )
        Text(
            text = downloadLabel,
            style = MaterialTheme.typography.labelSmall,
            color = if (download?.isActive == true) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable {
                if (download?.isComplete == true) onRemoveDownload() else if (download?.isActive != true) onDownload()
            },
        )
    }
}

/** A compact one-line secondary action: small text left, flat accent action right. */
@Composable
private fun CompactActionLine(text: String, action: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = action,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

/**
 * Top-right 3-dot menu on the detail hero — the home for "such things": the
 * playback skip amount, the merge-progress toggle, and Discard progress (moved
 * out of a prominent inline row into a confirm-gated menu item).
 */
@Composable
private fun DetailOverflowMenu(
    skipSeconds: Int,
    onSkip: (Int) -> Unit,
    merged: Boolean,
    onMerge: (Boolean) -> Unit,
    canDiscard: Boolean,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val check: @Composable (Boolean) -> Unit = { on ->
        if (on) Text("✓", color = MaterialTheme.colorScheme.primary)
    }
    Box(modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Text(
                text = "SKIP",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp),
            )
            DropdownMenuItem(
                text = { Text("Skip 10 seconds") },
                onClick = { onSkip(10) },
                trailingIcon = { check(skipSeconds == 10) },
            )
            DropdownMenuItem(
                text = { Text("Skip 30 seconds") },
                onClick = { onSkip(30) },
                trailingIcon = { check(skipSeconds == 30) },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Merge audio + ebook progress") },
                onClick = { onMerge(!merged) },
                trailingIcon = { check(merged) },
            )
            if (canDiscard) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Discard progress", color = MaterialTheme.colorScheme.error) },
                    onClick = { expanded = false; confirmDiscard = true },
                )
            }
        }
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard progress?") },
            text = {
                Text(
                    "Reset this book to the start on all your devices. Your " +
                        "furthest-listened bookmark is kept, so you can jump back.",
                )
            },
            confirmButton = {
                TextButton(onClick = { onDiscard(); confirmDiscard = false }) {
                    Text("Discard", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Cancel") } },
        )
    }
}

/**
 * Merged progress row (Merge progress on): the work's audio + ebook as ONE
 * progress bar (the further of the two) with both Listen and Read actions —
 * since the formats follow each other, a single % is enough.
 */
@Composable
private fun MergedEditionRow(
    audio: Edition,
    ebook: Edition,
    playback: PlaybackState,
    audioDownload: no.bellaybestia.audex.domain.download.DownloadInfo?,
    ebookDownload: no.bellaybestia.audex.domain.download.DownloadInfo?,
    onListen: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onRead: () -> Unit,
    onGetEbook: () -> Unit,
) {
    val merged = maxOf(audio.fraction, ebook.fraction).coerceIn(0.0, 1.0)
    val percent = (merged * 100).roundToInt()
    val isPlayingAudio = playback.libraryItemId == audio.libraryItemId && playback.isPlaying
    val ebookReady = ebookDownload?.isComplete == true
    val ebookFetching = ebookDownload?.isActive == true
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Outlined.Headphones, "Audiobook", Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Icons.Outlined.MenuBook, "Ebook", Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = "Audiobook + ebook", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(text = "$percent%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(
            Modifier.fillMaxWidth().height(3.dp).background(MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(merged.toFloat()).background(MaterialTheme.colorScheme.primary),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Text(
                text = if (isPlayingAudio) "Pause" else "Listen",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { if (isPlayingAudio) onTogglePlayPause() else onListen() },
            )
            Text(
                text = when {
                    ebookReady -> "Read"
                    ebookFetching -> "Fetching…"
                    else -> "Get + read"
                },
                style = MaterialTheme.typography.labelLarge,
                color = if (ebookFetching) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(enabled = !ebookFetching) { if (ebookReady) onRead() else onGetEbook() },
            )
        }
    }
}

/**
 * Full description with a flat expand/collapse — collapsed to a few lines so
 * the editions card stays above the fold (Codex rule: full description, HTML
 * already stripped by the repository).
 */
@Composable
private fun DescriptionBlock(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 14.dp),
        )
        Text(
            text = if (expanded) "Less" else "More",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(vertical = 6.dp),
        )
    }
}

/**
 * Word-sync row (docs/10): one flat line under the editions. Preparing runs
 * server-side on audex-align; READY means the reader follows narration
 * precisely instead of proportionally.
 */
@Composable
private fun WordSyncRow(status: WordSyncStatus, onPrepare: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = "Word sync", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = when (status) {
                    WordSyncStatus.READY ->
                        "Ready — play the audiobook, then open the ebook and turn on " +
                            "\"Follow audio\": the text highlights as it's narrated."
                    WordSyncStatus.RUNNING -> "Preparing on the server — this can take a while."
                    WordSyncStatus.NOT_CONFIGURED ->
                        "Aligns narration with the ebook text for precise read-along. " +
                            "Set the alignment service URL in Settings → Word sync to enable."
                    else -> "Align audio and text for precise read-along."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (status) {
            WordSyncStatus.READY -> Text(
                text = "Ready ✓",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            WordSyncStatus.RUNNING -> Text(
                text = "Preparing…",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            WordSyncStatus.NOT_CONFIGURED -> Unit
            else -> Text(
                text = "Prepare",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClick = onPrepare)
                    .padding(vertical = 4.dp, horizontal = 4.dp),
            )
        }
    }
}

