package no.bellaybestia.audex.feature.player

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import no.bellaybestia.audex.designsystem.CoverImage
import no.bellaybestia.audex.domain.playback.PlaybackState

private val SPEEDS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
private val SLEEP_MINUTES = listOf(0, 15, 30, 45, 60)

@Composable
fun PlayerScreen(
    modifier: Modifier = Modifier,
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        CoverImage(
            url = state.coverUrl,
            contentDescription = state.title,
            modifier = Modifier.size(width = 200.dp, height = 300.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = state.title.orEmpty(),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        state.author?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        state.currentChapter?.let {
            Text(
                text = it.title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        SeekBar(state = state, onSeek = viewModel::seekTo, modifier = Modifier.padding(top = 32.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = viewModel::skipBackward) {
                Icon(Icons.Filled.Replay10, contentDescription = "Back 10 seconds", modifier = Modifier.size(36.dp))
            }
            IconButton(onClick = viewModel::togglePlayPause) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = viewModel::skipForward) {
                Icon(Icons.Filled.Forward30, contentDescription = "Forward 30 seconds", modifier = Modifier.size(36.dp))
            }
        }

        // Sleep cycle: off → end of chapter → 15 → 30 → 45 → 60 → off …
        var sleepIdx by remember { mutableStateOf(0) }
        Row(
            modifier = Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Text(
                text = "${trimSpeed(state.speed)}×",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { viewModel.setSpeed(nextSpeed(state.speed)) }
                    .padding(8.dp),
            )
            val remaining = state.sleepTimerRemainingMs
            Text(
                text = when {
                    state.sleepAtChapterEnd -> "Sleep: chapter end"
                    remaining != null -> "Sleep ${formatTime(remaining)}"
                    else -> "Sleep off"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = if (remaining != null || state.sleepAtChapterEnd) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .clickable {
                        sleepIdx = (sleepIdx + 1) % (SLEEP_MINUTES.size + 1)
                        if (sleepIdx == 1 && state.chapters.isNotEmpty()) {
                            viewModel.setSleepAtChapterEnd(true)
                        } else {
                            // Index 0 = off; 2.. map onto the minute presets.
                            if (sleepIdx == 1) sleepIdx = 2 // no chapters: skip EOC
                            viewModel.setSleepTimer(
                                SLEEP_MINUTES.getOrElse(sleepIdx - 1) { 0 },
                            )
                        }
                    }
                    .padding(8.dp),
            )
        }

        GoToSection(state = state, viewModel = viewModel)

        if (state.chapters.isNotEmpty()) {
            ChapterSection(state = state, onJump = viewModel::seekToChapter)
        }

        BookmarksSection(viewModel = viewModel)
    }
}

/**
 * "Go to…" jump: a flat action that opens a small dialog to jump to a spot in
 * the audiobook. The input unit follows Settings → Playback → "Go to uses":
 * a percentage, or an exact timestamp (h:mm:ss / m:ss).
 */
@Composable
private fun GoToSection(state: PlaybackState, viewModel: PlayerViewModel) {
    val unit by viewModel.progressUnit.collectAsState()
    val byPercent = unit == no.bellaybestia.audex.domain.settings.ProgressUnit.PERCENT
    var open by remember { mutableStateOf(false) }
    var field by remember { mutableStateOf("") }

    Text(
        text = "Go to…",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clickable { field = ""; open = true }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )

    if (open) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(if (byPercent) "Go to percent" else "Go to time") },
            text = {
                Column {
                    androidx.compose.material3.OutlinedTextField(
                        value = field,
                        onValueChange = { field = it },
                        singleLine = true,
                        placeholder = { Text(if (byPercent) "0–100" else "h:mm:ss") },
                    )
                    Text(
                        text = if (byPercent) {
                            "Jump to a percentage of the book."
                        } else {
                            "Total length ${formatTime(state.durationMs)}."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        if (byPercent) {
                            field.trim().toDoubleOrNull()?.let { viewModel.seekToFraction(it / 100.0) }
                        } else {
                            parseTime(field)?.let { viewModel.seekTo(it) }
                        }
                        open = false
                    },
                ) { Text("Go") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { open = false }) { Text("Cancel") }
            },
        )
    }
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

/**
 * Manual bookmarks (server-side, syncs across ABS clients): "Add bookmark"
 * captures the current position with an optional note; rows seek on tap and
 * remove via the flat two-tap confirm.
 */
@Composable
private fun BookmarksSection(viewModel: PlayerViewModel) {
    val bookmarks by viewModel.bookmarks.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }

    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            text = "Add bookmark",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable { note = ""; adding = true }
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
        bookmarks.forEach { bookmark ->
            var armed by remember(bookmark.timeS) { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.seekTo(bookmark.timeS * 1000) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(text = bookmark.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    Text(
                        text = formatTime(bookmark.timeS * 1000),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = if (armed) "Remove?" else "Remove",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (armed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable { if (armed) viewModel.removeBookmark(bookmark) else armed = true }
                        .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                )
            }
        }
    }

    if (adding) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { adding = false },
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
                    onClick = { viewModel.addBookmark(note); adding = false },
                ) { Text("Save") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { adding = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun ChapterSection(state: PlaybackState, onJump: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
        Text(
            text = if (expanded) "Chapters (${state.chapters.size}) ▲" else "Chapters (${state.chapters.size}) ▼",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
        )
        if (expanded) {
            state.chapters.forEachIndexed { index, chapter ->
                val current = index == state.currentChapterIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onJump(index) }
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
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
}

@Composable
private fun SeekBar(state: PlaybackState, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs.coerceAtLeast(1)
    val liveFraction = (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    val fraction = dragFraction ?: liveFraction

    Column(modifier.fillMaxWidth()) {
        Slider(
            value = fraction,
            onValueChange = { dragFraction = it },
            onValueChangeFinished = {
                dragFraction?.let { onSeek((it * duration).toLong()) }
                dragFraction = null
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(formatTime((fraction * duration).toLong()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatTime(state.durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
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
