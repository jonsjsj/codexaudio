package no.bellaybestia.audex.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
