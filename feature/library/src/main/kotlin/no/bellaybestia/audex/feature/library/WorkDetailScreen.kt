package no.bellaybestia.audex.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlin.math.roundToInt
import no.bellaybestia.audex.designsystem.CoverImage
import no.bellaybestia.audex.designsystem.FlatTabRow
import no.bellaybestia.audex.designsystem.InfoDot
import no.bellaybestia.audex.designsystem.TintFromCover
import no.bellaybestia.audex.domain.model.Author
import no.bellaybestia.audex.domain.model.Edition
import no.bellaybestia.audex.domain.model.Format
import no.bellaybestia.audex.domain.model.Series
import no.bellaybestia.audex.domain.model.Work
import no.bellaybestia.audex.domain.playback.PlaybackState
import no.bellaybestia.audex.domain.reader.WordSyncProgress
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
    val wordSyncProgress by viewModel.wordSyncProgress.collectAsState()
    val work by viewModel.work.collectAsState()
    val nextInSeries by viewModel.nextInSeries.collectAsState()
    val description by viewModel.description.collectAsState()
    val extras by viewModel.extras.collectAsState()
    val otherFormat by viewModel.otherFormat.collectAsState()
    val skipSeconds by viewModel.skipSeconds.collectAsState()
    val mergeProgress by viewModel.mergeProgress.collectAsState()
    val furthest by viewModel.furthestS.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    val allAuthors by viewModel.allAuthors.collectAsState()
    val allSeries by viewModel.allSeries.collectAsState()
    var showFixMetadata by remember { mutableStateOf(false) }

    val cover = editions.firstNotNullOfOrNull { it.coverUrl }
    // Browsing a book re-tints the whole app around it (mockup 2c).
    TintFromCover(cover)

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        val hasAudioFmt = editions.any { it.format == Format.AUDIO }
        val hasEbookFmt = editions.any { it.format == Format.EBOOK }
        val wordSyncReady = wordSync == WordSyncStatus.READY || wordSync == WordSyncStatus.RUNNING
        val audioEdition = editions.firstOrNull { it.format == Format.AUDIO }
        val ebookEdition = editions.firstOrNull { it.format == Format.EBOOK }
        fun downloadOf(edition: Edition) = downloadStates.firstOrNull {
            it.serverId == edition.serverId &&
                it.libraryItemId == edition.libraryItemId &&
                it.format.name == edition.format.name
        }
        // Downloads live in the 3-dot menu now that the edition rows are gone.
        val downloadItems = buildList<Pair<String, () -> Unit>> {
            audioEdition?.let { ae ->
                val done = downloadOf(ae)?.isComplete == true
                add((if (done) "Remove audiobook download" else "Download audiobook") to {
                    if (done) viewModel.removeDownload(ae) else viewModel.download(ae)
                })
            }
            ebookEdition?.let { ee ->
                val done = downloadOf(ee)?.isComplete == true
                add((if (done) "Remove ebook download" else "Download ebook") to {
                    if (done) viewModel.removeDownload(ee) else viewModel.download(ee)
                })
            }
        }
        // Full-bleed cover header (redesign 4a): the real cover fades into the page;
        // series / title / author·narrator + FORMAT ICONS (headphones · book · W)
        // sit over the foot. No "Audio + EPUB" text — the icons carry it. The W
        // (word sync) only appears when it's actually ready for this book.
        Box(Modifier.fillMaxWidth().aspectRatio(0.96f)) {
            CoverImage(url = cover, contentDescription = viewModel.title, modifier = Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.5f to Color(0x55000000),
                        1.0f to Color(0xF00A0B0F),
                    ),
                ),
            )
            Box(Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp)) {
                DetailOverflowMenu(
                    skipSeconds = skipSeconds,
                    onSkip = viewModel::setSkipSeconds,
                    merged = mergeProgress,
                    onMerge = viewModel::setMergeProgress,
                    canDiscard = editions.any { it.fraction > 0.001 } || (furthest ?: 0.0) > 0.0,
                    onDiscard = viewModel::discardProgress,
                    canFixMetadata = work?.authorId != null || work?.seriesId != null,
                    onFixMetadata = { showFixMetadata = true },
                    downloadItems = downloadItems,
                    tint = Color(0xFFEAEEF5),
                )
            }
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                work?.seriesName?.let { series ->
                    val pos = work?.seriesPosition?.let { p ->
                        " · #" + if (p % 1.0 == 0.0) p.toInt().toString() else p.toString()
                    }.orEmpty()
                    val seriesId = work?.seriesId
                    Text(
                        text = (series + pos).uppercase(),
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFEAEEF5),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (seriesId != null) Modifier.clickable { onSeriesClick(seriesId, series) } else Modifier,
                    )
                }
                Text(
                    text = viewModel.title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF7F9FC),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                val authorId = work?.authorId
                val narrator = extras?.narrator?.takeIf { it.isNotBlank() }
                val author = viewModel.author
                val byline = buildString {
                    author?.let { append(it) }
                    narrator?.let { if (isNotEmpty()) append(" · "); append("Narrated by $it") }
                }
                if (byline.isNotBlank()) {
                    Text(
                        text = byline,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFC7CEDA),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (authorId != null && author != null) {
                            Modifier.clickable { onAuthorClick(authorId, author) }
                        } else {
                            Modifier
                        },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val meta = buildList {
                        work?.year?.let { add(it.toString()) }
                        editions.firstOrNull { it.format == Format.AUDIO }?.durationS?.let { s ->
                            add("${s / 3600}h ${(s % 3600) / 60}m")
                        }
                    }.joinToString(" · ")
                    if (meta.isNotBlank()) {
                        Text(meta, style = MaterialTheme.typography.labelSmall, color = Color(0xFFA9B2C0))
                    }
                    if (hasAudioFmt) Icon(Icons.Outlined.Headphones, "Audiobook", Modifier.size(17.dp), tint = Color(0xFFEAEEF5))
                    if (hasEbookFmt) Icon(Icons.Outlined.MenuBook, "Ebook", Modifier.size(17.dp), tint = Color(0xFFEAEEF5))
                    if (wordSyncReady) {
                        Text("W", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        // Primary actions (redesign 4a): Listen (accent) + Read (outline).
        if (audioEdition != null || ebookEdition != null) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                audioEdition?.let { ae ->
                    val playingThis = playback.libraryItemId == ae.libraryItemId && playback.isPlaying
                    PillButton(
                        text = if (playingThis) "Pause" else "Listen",
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
        // Combined progress bar (redesign 4a): ONE line — the ebook + audio fills
        // overlaid, bookmark ticks you can tap, and a book-% (furthest read) +
        // headphones-% (furthest listened) marker. Replaces the separate
        // Listening / Furthest-listened rows. Audio fill follows the live position.
        val livePlayingFraction: Double? = audioEdition?.let { ae ->
            if (playback.libraryItemId == ae.libraryItemId && playback.durationMs > 0) {
                (playback.positionMs.toDouble() / playback.durationMs).coerceIn(0.0, 1.0)
            } else {
                null
            }
        }
        if (audioEdition != null || ebookEdition != null) {
            CombinedProgressBar(
                audioFraction = livePlayingFraction ?: audioEdition?.fraction ?: 0.0,
                ebookFraction = ebookEdition?.fraction ?: 0.0,
                hasAudio = audioEdition != null,
                hasEbook = ebookEdition != null,
                durationS = audioEdition?.durationS,
                bookmarks = bookmarks,
                onSeekBookmark = { viewModel.playAudioAt(it) },
                onListenFurthest = { viewModel.jumpToFurthest() },
                onReadSpot = ebookEdition?.let { ee ->
                    { onOpenReader(ee.serverId, ee.libraryItemId, viewModel.title) }
                },
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

        description?.let { DescriptionBlock(it) }

        // Word-sync read-along — build/align option, live progress + ETA, aligned icon.
        if (wordSync != WordSyncStatus.UNAVAILABLE) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
            WordSyncRow(
                status = wordSync,
                progress = wordSyncProgress,
                onPrepare = { viewModel.requestWordSync() },
            )
        }

        androidx.compose.foundation.layout.Spacer(Modifier.height(24.dp))
    }

    if (showFixMetadata) {
        MetadataFixDialog(
            currentAuthorId = work?.authorId,
            currentAuthorName = viewModel.author,
            currentSeriesId = work?.seriesId,
            currentSeriesName = work?.seriesName,
            authors = allAuthors,
            series = allSeries,
            onMergeAuthor = { viewModel.mergeAuthorInto(it) },
            onMergeSeries = { viewModel.mergeSeriesInto(it) },
            onDismiss = { showFixMetadata = false },
        )
    }
}

/**
 * "Fix author / series" (docs/07 in-app metadata matching): fold this work's
 * mistyped/duplicate author or series into an existing one. Picking a target
 * writes a durable AUTHOR_MERGE / SERIES_MERGE override and rebuilds the graph,
 * so the correction sticks across every re-sync. The current author/series is
 * excluded from the list — you can't merge something into itself.
 */
@Composable
private fun MetadataFixDialog(
    currentAuthorId: String?,
    currentAuthorName: String?,
    currentSeriesId: String?,
    currentSeriesName: String?,
    authors: List<Author>,
    series: List<Series>,
    onMergeAuthor: (String) -> Unit,
    onMergeSeries: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val hasAuthor = currentAuthorId != null
    val hasSeries = currentSeriesId != null
    var authorMode by remember { mutableStateOf(hasAuthor) }
    var query by remember { mutableStateOf("") }
    // (id, displayName) of the chosen merge target; null until one is tapped.
    var selected by remember { mutableStateOf<Pair<String, String>?>(null) }

    val options: List<Pair<String, String>> =
        if (authorMode) {
            authors.filter { it.id != currentAuthorId }.map { it.id to it.name }
        } else {
            series.filter { it.id != currentSeriesId }.map { it.id to it.name }
        }
    val filtered = options.filter { (_, name) -> name.contains(query.trim(), ignoreCase = true) }
        .take(60)

    val subject = if (authorMode) currentAuthorName else currentSeriesName

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fix author / series") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (hasAuthor && hasSeries) {
                    FlatTabRow(
                        tabs = listOf("Author", "Series"),
                        selectedIndex = if (authorMode) 0 else 1,
                        onSelect = { idx ->
                            authorMode = idx == 0
                            selected = null
                            query = ""
                        },
                    )
                }
                Text(
                    text = "Merge " + (subject?.let { "“$it”" } ?: (if (authorMode) "this author" else "this series")) +
                        " into an existing " + (if (authorMode) "author" else "series") + ":",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("Search") },
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
                ) {
                    if (filtered.isEmpty()) {
                        item {
                            Text(
                                "No matches",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                    }
                    items(filtered, key = { it.first }) { (id, name) ->
                        val isSel = selected?.first == id
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selected = id to name }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected != null,
                onClick = {
                    selected?.let { (id, _) ->
                        if (authorMode) onMergeAuthor(id) else onMergeSeries(id)
                    }
                    onDismiss()
                },
            ) {
                Text(selected?.let { "Merge into ${it.second}" } ?: "Merge")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Redesign 4a — the single combined progress line: one 6dp bar with the ebook
 * fill (translucent) and audio fill (accent) overlaid + tappable bookmark ticks,
 * and below it a book-% (furthest read) and headphones-% (furthest listened)
 * marker. Replaces the separate Listening / Furthest-listened rows.
 */
@Composable
private fun CombinedProgressBar(
    audioFraction: Double,
    ebookFraction: Double,
    hasAudio: Boolean,
    hasEbook: Boolean,
    durationS: Long?,
    bookmarks: List<no.bellaybestia.audex.domain.playback.Bookmark>,
    onSeekBookmark: (Double) -> Unit,
    onListenFurthest: () -> Unit,
    onReadSpot: (() -> Unit)?,
) {
    val aF = audioFraction.coerceIn(0.0, 1.0).toFloat()
    val eF = ebookFraction.coerceIn(0.0, 1.0).toFloat()
    Column(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(14.dp)) {
            val fullW = maxWidth
            Box(
                Modifier.fillMaxWidth().height(6.dp).align(Alignment.CenterStart)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                if (hasEbook) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(eF).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)))
                }
                if (hasAudio) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(aF).background(MaterialTheme.colorScheme.primary))
                }
            }
            if (durationS != null && durationS > 0) {
                bookmarks.forEach { bm ->
                    val f = (bm.timeS.toDouble() / durationS).coerceIn(0.0, 1.0).toFloat()
                    Box(
                        Modifier.align(Alignment.CenterStart)
                            .offset(x = fullW * f - 1.dp)
                            .width(2.dp).height(14.dp)
                            .background(MaterialTheme.colorScheme.onSurface)
                            .clickable { onSeekBookmark(bm.timeS.toDouble()) },
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                if (hasEbook) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = (if (onReadSpot != null) Modifier.clickable(onClick = onReadSpot) else Modifier)
                            .padding(vertical = 2.dp),
                    ) {
                        Icon(Icons.Outlined.MenuBook, "Furthest read", Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${(eF * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Box {
                if (hasAudio) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.clickable(onClick = onListenFurthest).padding(vertical = 2.dp),
                    ) {
                        Text("${(aF * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Icon(Icons.Outlined.Headphones, "Furthest listened", Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
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
    liveFraction: Double?,
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
    val fraction = (liveFraction ?: edition.fraction).coerceIn(0.0, 1.0)
    val percent = (fraction * 100).roundToInt()
    val downloadLabel = when {
        download == null -> "Save"
        download.isActive -> "…"
        download.isComplete -> "Saved ✓"
        else -> "Retry"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
        // thin progress bar takes the middle (display only — Listen/Read are the
        // buttons above; this row just shows per-format progress + a Save toggle)
        Box(
            Modifier
                .weight(1f)
                .height(3.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction.toFloat())
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        Text(
            text = "$percent%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    canFixMetadata: Boolean = false,
    onFixMetadata: () -> Unit = {},
    downloadItems: List<Pair<String, () -> Unit>> = emptyList(),
    modifier: Modifier = Modifier,
    tint: Color? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val check: @Composable (Boolean) -> Unit = { on ->
        if (on) Text("✓", color = MaterialTheme.colorScheme.primary)
    }
    Box(modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant)
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
            if (canFixMetadata) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Fix author / series") },
                    onClick = { expanded = false; onFixMetadata() },
                )
            }
            if (downloadItems.isNotEmpty()) {
                HorizontalDivider()
                downloadItems.forEach { (label, action) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = { action(); expanded = false },
                    )
                }
            }
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
 * progress bar. While you're actually listening it tracks the LIVE audio
 * position ([liveAudioFraction]) so it moves as you go; otherwise it shows the
 * further of the two saved spots. Listen/Read are the buttons above.
 */
@Composable
private fun MergedEditionRow(
    audio: Edition,
    ebook: Edition,
    liveAudioFraction: Double?,
) {
    // The furthest across both formats, updated LIVE while you listen: it never
    // drops, and the moment your listening passes your reading spot it climbs.
    val merged = maxOf(liveAudioFraction ?: 0.0, audio.fraction, ebook.fraction).coerceIn(0.0, 1.0)
    val percent = (merged * 100).roundToInt()
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
        // Listen/Read are the buttons above; this merged row is display only.
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
private fun WordSyncRow(status: WordSyncStatus, progress: WordSyncProgress, onPrepare: () -> Unit) {
    val prog = progress.progress
    val pct = ((prog ?: 0f).coerceIn(0f, 1f) * 100).roundToInt()
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.GraphicEq, contentDescription = null, modifier = Modifier.size(20.dp),
                tint = if (status == WordSyncStatus.READY) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = when (status) {
                            WordSyncStatus.READY -> "Synced"
                            WordSyncStatus.NOT_CONFIGURED -> "Audio-ebook sync"
                            else -> "Audio-ebook sync"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    InfoDot(
                        text = when (status) {
                            WordSyncStatus.READY ->
                                "Open the ebook while the audiobook plays and the narrated text highlights as you read."
                            WordSyncStatus.RUNNING ->
                                "Building the audio↔text alignment map on the server. It'll highlight the narration once it's ready."
                            WordSyncStatus.NOT_CONFIGURED ->
                                "Set the alignment service URL in Settings → Audio-ebook sync to enable it."
                            else -> "Align the narration with the ebook text so the two stay in sync."
                        },
                    )
                }
                // Live build progress stays visible (it's status, not an explanation).
                if (status == WordSyncStatus.RUNNING) {
                    Text(
                        text = buildString {
                            append(progress.phase ?: "Preparing on the server")
                            progress.etaSeconds?.let { append(" · ~${formatEta(it)} left") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when (status) {
                WordSyncStatus.READY -> Icon(
                    Icons.Outlined.CheckCircle, contentDescription = "Aligned",
                    modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary,
                )
                WordSyncStatus.RUNNING -> Text(
                    text = "$pct%", style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                WordSyncStatus.NOT_CONFIGURED -> Unit
                else -> Text(
                    text = "Align", style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onPrepare).padding(vertical = 4.dp, horizontal = 8.dp),
                )
            }
        }
        if (status == WordSyncStatus.RUNNING) {
            Box(
                Modifier.fillMaxWidth().height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    Modifier.fillMaxHeight().fillMaxWidth((prog ?: 0.03f).coerceIn(0.03f, 1f))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

private fun formatEta(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

