package no.bellaybestia.audex.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Live update state for the About page, mapped from the app-module
 * AboutViewModel in AppNav (kept feature-local so this module needn't depend on
 * :app).
 */
sealed interface UpdateUi {
    data object Checking : UpdateUi
    data object UpToDate : UpdateUi
    data object Unreachable : UpdateUi
    data class Available(val version: String, val notes: String) : UpdateUi
    data class Downloading(val progress: Float) : UpdateUi
}

/** One release entry parsed from the bundled CHANGELOG.md. */
private data class Release(val version: String, val notes: List<String>)

/**
 * Update/About page: every release with its version number and notes, newest
 * first, the installed one tagged. Data comes from the CHANGELOG.md bundled
 * into assets at build time (single source of truth — the same file drives the
 * OTA manifest notes), so this can never drift from the repo changelog.
 */
@Composable
fun AboutScreen(
    appVersion: String,
    update: UpdateUi,
    onCheck: () -> Unit,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val releases by produceState(initialValue = emptyList<Release>()) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open("CHANGELOG.md").bufferedReader().readText()
            }.getOrDefault("").let(::parseChangelog)
        }
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item(key = "title") {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)) {
                Text(text = "Audex", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "Version $appVersion · alpha",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item(key = "update-card") {
            UpdateCard(update = update, onCheck = onCheck, onInstall = onInstall)
        }
        item(key = "updates-header") {
            Text(
                text = "UPDATES",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
            )
        }
        if (releases.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = "Release notes unavailable in this build.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        releases.forEachIndexed { index, release ->
            item(key = "rel-${release.version}") {
                if (index > 0) {
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = release.version,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (release.version == appVersion) {
                            Text(
                                text = "installed",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 10.dp),
                            )
                        }
                    }
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 6.dp),
                    ) {
                        release.notes.forEach { note ->
                            Row {
                                Text(
                                    text = "–",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = note,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Live update status — FLAT (no rounded card, no pill buttons; the design rule
 * forbids bubble controls). A single hairline-bounded row: a line of status on
 * the left, a flat accent text-action on the right.
 */
@Composable
private fun UpdateCard(
    update: UpdateUi,
    onCheck: () -> Unit,
    onInstall: () -> Unit,
) {
    val (headline, sub, actionLabel, action) = when (update) {
        is UpdateUi.Checking -> UpdateLine("Checking for updates…", null, null, null)
        is UpdateUi.UpToDate -> UpdateLine("You're on the latest version", null, "Check again", onCheck)
        is UpdateUi.Unreachable ->
            UpdateLine("Couldn't reach the update server", "Check your connection.", "Try again", onCheck)
        is UpdateUi.Available ->
            UpdateLine("Version ${update.version} is available", update.notes.takeIf { it.isNotBlank() }, "Update", onInstall)
        is UpdateUi.Downloading ->
            UpdateLine("Downloading update… ${(update.progress * 100).roundToInt()}%", null, null, null)
    }
    val accent = update is UpdateUi.Available
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = headline,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (accent) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                )
                sub?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (actionLabel != null && action != null) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable(onClick = action)
                        .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                )
            }
        }
        if (update is UpdateUi.Downloading) {
            LinearProgressIndicator(
                progress = { update.progress },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

private data class UpdateLine(
    val headline: String,
    val sub: String?,
    val actionLabel: String?,
    val action: (() -> Unit)?,
)

/** "## x.y.z" headers start a release; "- " lines are its notes (wrapped lines joined). */
private fun parseChangelog(text: String): List<Release> {
    val releases = mutableListOf<Release>()
    var version: String? = null
    var notes = mutableListOf<String>()
    fun flush() {
        version?.let { releases.add(Release(it, notes.toList())) }
        notes = mutableListOf()
    }
    for (raw in text.lines()) {
        val line = raw.trimEnd()
        when {
            line.startsWith("## ") -> {
                flush()
                version = line.removePrefix("## ").trim()
            }
            line.startsWith("- ") && version != null ->
                notes.add(line.removePrefix("- ").trim())
            line.startsWith("  ") && version != null && notes.isNotEmpty() ->
                notes[notes.lastIndex] = notes.last() + " " + line.trim()
        }
    }
    flush()
    return releases
}
