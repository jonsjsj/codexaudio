package no.bellaybestia.audex.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.offset
import kotlin.math.roundToInt
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import no.bellaybestia.audex.designsystem.CoverImage
import no.bellaybestia.audex.domain.playback.PlaybackState

private val SPEEDS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
private val SLEEP_MINUTES = listOf(0, 15, 30, 45, 60)

// A fixed, hand-tuned waveform silhouette (12 ticks) — decorative, from the
// mockup. Bars up to the current position glow accent; the rest are hairline.
private val WAVE = listOf(0.30f, 0.62f, 0.44f, 0.82f, 0.55f, 1.0f, 0.70f, 0.90f, 0.48f, 0.76f, 0.38f, 0.66f)

@Composable
fun PlayerScreen(
    modifier: Modifier = Modifier,
    onRead: (serverId: String, itemId: String, title: String) -> Unit = { _, _, _ -> },
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    if (!state.hasItem) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "Nothing playing — pick a book from your library and tap Play.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(32.dp),
            )
        }
        return
    }

    // Hoisted dialog state so the utility strip can trigger Go-to and Add
    // bookmark while the dialogs live at the bottom of the tree.
    var goToOpen by remember { mutableStateOf(false) }
    var addBookmark by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(0) } // 0 = Chapters, 1 = Bookmarks
    // Sleep cycle index: off → end of chapter → 15 → 30 → 45 → 60 → off …
    var sleepIdx by remember { mutableStateOf(0) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // The jump panel takes over the cover's own slot instead of a modal dialog —
        // the rest of the player (transport, chapters/bookmarks below) stays visible
        // and usable while it's open. Tapping "Jump" again (or the panel's own close)
        // swaps the cover back.
        val readTarget by viewModel.readEbookTarget.collectAsState()
        if (goToOpen) {
            PlayerJumpPanel(
                state = state,
                viewModel = viewModel,
                readTarget = readTarget,
                onRead = onRead,
                onClose = { goToOpen = false },
            )
        } else {
            PlayerHero(state = state)
        }

        Column(Modifier.padding(horizontal = 20.dp)) {
            // Listen/Read, directly above the scrubber — not on top of the cover
            // art, and not buried in the Jump panel (which has its own copy of
            // this switch for when it's already open).
            if (!goToOpen) {
                readTarget?.let { (sid, itemId) ->
                    ReadPill(onClick = { onRead(sid, itemId, state.title.orEmpty()) })
                    Spacer(Modifier.height(10.dp))
                }
            }
            val playerBookmarks by viewModel.bookmarks.collectAsState()
            ProgressSection(
                state = state,
                onSeek = viewModel::seekTo,
                bookmarkFractions = playerBookmarks.map {
                    (it.timeS * 1000f / state.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f)
                },
            )
            Spacer(Modifier.height(8.dp))
            TransportRow(state = state, viewModel = viewModel)
            Spacer(Modifier.height(20.dp))
            UtilityStrip(
                state = state,
                onSpeed = { viewModel.setSpeed(nextSpeed(state.speed)) },
                onSleep = {
                    sleepIdx = (sleepIdx + 1) % (SLEEP_MINUTES.size + 1)
                    if (sleepIdx == 1 && state.chapters.isNotEmpty()) {
                        viewModel.setSleepAtChapterEnd(true)
                    } else {
                        if (sleepIdx == 1) sleepIdx = 2 // no chapters: skip EOC
                        viewModel.setSleepTimer(SLEEP_MINUTES.getOrElse(sleepIdx - 1) { 0 })
                    }
                },
                onBookmark = { addBookmark = true },
                goToActive = goToOpen,
                onGoTo = { goToOpen = !goToOpen },
            )
            Spacer(Modifier.height(16.dp))
            PlayerTabs(tab = tab, onTab = { tab = it })
            Spacer(Modifier.height(4.dp))
        }

        if (tab == 0) {
            ChapterList(state = state, onJump = viewModel::seekToChapter)
        } else {
            BookmarkList(viewModel = viewModel)
        }
        Spacer(Modifier.height(24.dp))
    }

    if (addBookmark) AddBookmarkDialog(viewModel = viewModel, onDismiss = { addBookmark = false })
}

/**
 * Cover-image hero (the mockup's cover-gradient banner) — the real cover fills
 * the top, a vertical scrim fades it into the page background, and the title
 * block sits over the bottom in Space Grotesk. "NOW PLAYING" pins the top-left.
 */
@Composable
private fun PlayerHero(state: PlaybackState) {
    val bg = MaterialTheme.colorScheme.background
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(0.92f),
    ) {
        CoverImage(
            url = state.coverUrl,
            contentDescription = state.title,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.45f to bg.copy(alpha = 0.35f),
                        0.78f to bg.copy(alpha = 0.9f),
                        1.0f to bg,
                    ),
                ),
        )
        Text(
            text = "NOW PLAYING",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 2.sp,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(20.dp),
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text(
                text = state.title.orEmpty(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            state.author?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** Listen/Read quick-switch, sitting above the scrubber (not on the cover, not
 *  buried in the Jump panel) — a one-tap way into the ebook. */
@Composable
private fun ReadPill(onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(30.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.MenuBook,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "Read",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * Chapter label + scrubber + the 12-tick waveform + position/left/total — the
 * mockup's progress block. The waveform lights up to the play head.
 */
@Composable
private fun ProgressSection(
    state: PlaybackState,
    onSeek: (Long) -> Unit,
    bookmarkFractions: List<Float> = emptyList(),
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs.coerceAtLeast(1)
    val liveFraction = (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    val fraction = dragFraction ?: liveFraction

    Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        state.currentChapter?.let {
            Text(
                text = it.title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            // Material sliders inset the track by ~the thumb radius (10dp) each side.
            val density = androidx.compose.ui.platform.LocalDensity.current
            val inset = with(density) { 10.dp.toPx() }
            // Magnetic snap (matches the reader's scrubber): dragging within ~16dp of a
            // bookmark tick locks exactly onto it.
            val snapFrac = with(density) { 16.dp.toPx() } / (widthPx - 2 * inset).coerceAtLeast(1f)
            Slider(
                value = fraction,
                onValueChange = { raw ->
                    val nearest = bookmarkFractions.minByOrNull { kotlin.math.abs(it - raw) }
                    dragFraction = if (nearest != null && kotlin.math.abs(nearest - raw) <= snapFrac) nearest else raw
                },
                onValueChangeFinished = {
                    dragFraction?.let { onSeek((it * duration).toLong()) }
                    dragFraction = null
                },
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.outline,
                ),
            )
            // Bookmark ticks over the track — tap to jump there.
            bookmarkFractions.forEach { f ->
                val x = inset + f.coerceIn(0f, 1f) * (widthPx - 2 * inset)
                Box(
                    Modifier.align(androidx.compose.ui.Alignment.CenterStart)
                        .offset { androidx.compose.ui.unit.IntOffset((x - 5f).roundToInt(), 0) }
                        .size(10.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(MaterialTheme.colorScheme.tertiary)
                        .clickable { onSeek((f * duration).toLong()) },
                )
            }
        }
        Waveform(fraction = fraction)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(formatTime((fraction * duration).toLong()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("-" + formatTime((duration - (fraction * duration).toLong()).coerceAtLeast(0)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatTime(state.durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Waveform(fraction: Float) {
    val active = (fraction * WAVE.size).toInt()
    val accent = MaterialTheme.colorScheme.primary
    val line = MaterialTheme.colorScheme.outline
    Row(
        modifier = Modifier.fillMaxWidth().height(28.dp).padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WAVE.forEachIndexed { i, h ->
            Box(
                Modifier
                    .weight(1f)
                    .height((22.dp) * h)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (i <= active) accent else line),
            )
        }
    }
}

@Composable
private fun TransportRow(state: PlaybackState, viewModel: PlayerViewModel) {
    val skip by viewModel.skipSeconds.collectAsState()
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (skip == 10) Icons.Filled.Replay10 else Icons.Filled.Replay30,
            contentDescription = "Back $skip seconds",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClick = viewModel::skipBackward)
                .padding(2.dp),
        )
        Spacer(Modifier.width(36.dp))
        Box(
            Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable(onClick = viewModel::togglePlayPause),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (state.isPlaying) "Pause" else "Play",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(34.dp),
            )
        }
        Spacer(Modifier.width(36.dp))
        Icon(
            imageVector = if (skip == 10) Icons.Filled.Forward10 else Icons.Filled.Forward30,
            contentDescription = "Forward $skip seconds",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClick = viewModel::skipForward)
                .padding(2.dp),
        )
    }
}

/**
 * The mockup's four-cell utility strip (hairline top+bottom): each cell is a
 * value over a tiny letter-spaced label, divided by vertical hairlines.
 */
@Composable
private fun UtilityStrip(
    state: PlaybackState,
    onSpeed: () -> Unit,
    onSleep: () -> Unit,
    onBookmark: () -> Unit,
    goToActive: Boolean,
    onGoTo: () -> Unit,
) {
    val line = MaterialTheme.colorScheme.outline
    val sleepValue = when {
        state.sleepAtChapterEnd -> "EOC"
        state.sleepTimerRemainingMs != null -> formatTime(state.sleepTimerRemainingMs!!)
        else -> "Off"
    }
    val sleepActive = state.sleepAtChapterEnd || state.sleepTimerRemainingMs != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.surface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UtilityCell("${trimSpeed(state.speed)}×", "SPEED", active = state.speed != 1.0f, onClick = onSpeed, modifier = Modifier.weight(1f))
        Box(Modifier.width(1.dp).height(38.dp).background(line))
        UtilityCell(sleepValue, "SLEEP", active = sleepActive, onClick = onSleep, modifier = Modifier.weight(1f))
        Box(Modifier.width(1.dp).height(38.dp).background(line))
        UtilityCell("Add", "BOOKMARK", active = false, onClick = onBookmark, modifier = Modifier.weight(1f))
        Box(Modifier.width(1.dp).height(38.dp).background(line))
        UtilityCell(if (goToActive) "Close" else "Jump", "GO TO", active = goToActive, onClick = onGoTo, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun UtilityCell(value: String, label: String, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(2.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.5.sp,
            fontSize = 9.sp,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@Composable
private fun PlayerTabs(tab: Int, onTab: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        listOf("Chapters", "Bookmarks").forEachIndexed { i, label ->
            val selected = i == tab
            Column(
                Modifier
                    .clip(RoundedCornerShape(2.dp))
                    .clickable { onTab(i) }
                    .padding(vertical = 6.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .width(28.dp)
                        .height(2.dp)
                        .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
                )
            }
        }
    }
}

@Composable
private fun ChapterList(state: PlaybackState, onJump: (Int) -> Unit) {
    if (state.chapters.isEmpty()) {
        Text(
            text = "No chapters for this book.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
        return
    }
    Column(Modifier.fillMaxWidth()) {
        state.chapters.forEachIndexed { index, chapter ->
            val current = index == state.currentChapterIndex
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onJump(index) }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "%02d".format(index + 1),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(28.dp),
                )
                Text(
                    text = chapter.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatTime(chapter.startMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun BookmarkList(viewModel: PlayerViewModel) {
    val bookmarks by viewModel.bookmarks.collectAsState()
    if (bookmarks.isEmpty()) {
        Text(
            text = "No bookmarks yet — tap Bookmark above to save this moment.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
        return
    }
    Column(Modifier.fillMaxWidth()) {
        bookmarks.forEach { bookmark ->
            var armed by remember(bookmark.timeS) { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.seekTo(bookmark.timeS * 1000) }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(text = bookmark.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = formatTime(bookmark.timeS * 1000),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = if (armed) "Remove?" else "Remove",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (armed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(2.dp))
                        .clickable { if (armed) viewModel.removeBookmark(bookmark) else armed = true }
                        .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                )
            }
        }
    }
}

/** One entry in the jump panel's merged list — a chapter boundary or a bookmark,
 *  interleaved by time so the whole book's landmarks read as one timeline. */
private data class JumpEntry(val label: String, val isChapter: Boolean, val timeMs: Long, val onClick: () -> Unit)

/**
 * The jump panel, filling the cover's own slot (same [aspectRatio] as [PlayerHero]) so
 * swapping between them never reflows the rest of the screen. Unlike the old modal
 * dialog it replaced, everything below — transport, chapters/bookmarks — stays visible
 * and usable while this is open; "Jump"/"Close" on the utility strip toggles it. The
 * cover shows through a dark scrim behind the controls (an overlay ON the book, not a
 * flat card that replaces it) and chapters + bookmarks are merged into one
 * time-ordered list beneath the manual jump field, instead of living only in the
 * separate tabs below the transport.
 *
 * Unit follows Settings → Playback → "Go to uses" by default (a percentage or an exact
 * timestamp) but the %/time switch here is a local, in-context override, matching the
 * reader's Go-to sheet. When the book also has an ebook edition, a Listen/Read switch
 * lets you jump straight into reading it — the seamless audio↔reading handoff.
 */
@Composable
private fun PlayerJumpPanel(
    state: PlaybackState,
    viewModel: PlayerViewModel,
    readTarget: Pair<String, String>?,
    onRead: (serverId: String, itemId: String, title: String) -> Unit,
    onClose: () -> Unit,
) {
    val unitPref by viewModel.progressUnit.collectAsState()
    var byPercent by remember { mutableStateOf(unitPref == no.bellaybestia.audex.domain.settings.ProgressUnit.PERCENT) }
    val readingS by viewModel.readingAudioSeconds.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    var field by remember { mutableStateOf("") }
    val duration = state.durationMs.coerceAtLeast(1)

    fun display(ms: Long): String =
        if (byPercent) "${(ms * 100 / duration).coerceIn(0, 100)}%" else formatTime(ms)

    val merged = remember(state.chapters, bookmarks) {
        val chapterEntries = state.chapters.map {
            JumpEntry(it.title, isChapter = true, timeMs = it.startMs, onClick = { viewModel.seekTo(it.startMs) })
        }
        val bookmarkEntries = bookmarks.map {
            JumpEntry(it.title, isChapter = false, timeMs = it.timeS * 1000, onClick = { viewModel.seekTo(it.timeS * 1000) })
        }
        (chapterEntries + bookmarkEntries).sortedBy { it.timeMs }
    }

    Box(Modifier.fillMaxWidth().aspectRatio(0.92f)) {
        CoverImage(
            url = state.coverUrl,
            contentDescription = state.title,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.88f)),
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Go to",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onClose)
                        .padding(6.dp),
                )
            }
            readTarget?.let { (sid, itemId) ->
                Spacer(Modifier.height(14.dp))
                no.bellaybestia.audex.designsystem.FlatTabRow(
                    tabs = listOf("Listen", "Read"),
                    selectedIndex = 0,
                    onSelect = { if (it == 1) onRead(sid, itemId, state.title.orEmpty()) },
                )
            }
            Spacer(Modifier.height(16.dp))
            no.bellaybestia.audex.designsystem.FlatTabRow(
                tabs = listOf("%", "Time"),
                selectedIndex = if (byPercent) 0 else 1,
                onSelect = { byPercent = it == 0 },
            )
            Spacer(Modifier.height(14.dp))
            androidx.compose.material3.OutlinedTextField(
                value = field,
                onValueChange = { field = it },
                singleLine = true,
                label = { Text(if (byPercent) "Percent" else "Time") },
                placeholder = { Text(if (byPercent) "0–100" else "h:mm:ss") },
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    Text(
                        text = "Go",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable {
                                if (byPercent) {
                                    field.trim().toDoubleOrNull()?.let { viewModel.seekToFraction(it / 100.0) }
                                } else {
                                    parseTime(field)?.let { viewModel.seekTo(it) }
                                }
                                onClose()
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                },
            )
            readingS?.let { rs ->
                Spacer(Modifier.height(6.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.seekTo((rs * 1000).toLong()); onClose() }
                        .padding(vertical = 10.dp),
                ) {
                    Text(
                        text = "Where you're reading",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = display((rs * 1000).toLong()),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            if (merged.isNotEmpty()) {
                HorizontalDivider(
                    modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                Text(
                    text = "Chapters & bookmarks",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                )
                merged.forEach { entry ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { entry.onClick(); onClose() }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = if (entry.isChapter) "CHAPTER" else "BOOKMARK",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (entry.isChapter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                                letterSpacing = 1.sp,
                            )
                            Text(
                                text = entry.label,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Text(
                            text = display(entry.timeMs),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddBookmarkDialog(viewModel: PlayerViewModel, onDismiss: () -> Unit) {
    var note by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bookmark this moment") },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                placeholder = { Text("Note (optional)") },
                singleLine = true,
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { viewModel.addBookmark(note); onDismiss() },
            ) { Text("Save") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Parse "h:mm:ss", "m:ss", or plain seconds into milliseconds. Null if unparseable. */
private fun parseTime(raw: String): Long? {
    val parts = raw.trim().split(":").map { it.trim() }
    if (parts.isEmpty() || parts.any { it.isEmpty() || it.toLongOrNull() == null }) return null
    val nums = parts.map { it.toLong() }
    val seconds = when (nums.size) {
        1 -> nums[0]
        2 -> nums[0] * 60 + nums[1]
        3 -> nums[0] * 3600 + nums[1] * 60 + nums[2]
        else -> return null
    }
    return seconds * 1000
}

private fun nextSpeed(current: Float): Float {
    val i = SPEEDS.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
    return SPEEDS[(if (i < 0) SPEEDS.indexOf(1.0f) else i).plus(1) % SPEEDS.size]
}

private fun trimSpeed(s: Float): String = if (s % 1f == 0f) s.toInt().toString() else s.toString()

private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
