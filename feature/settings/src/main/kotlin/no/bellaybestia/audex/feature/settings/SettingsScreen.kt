package no.bellaybestia.audex.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import no.bellaybestia.audex.designsystem.FlatTabRow
import no.bellaybestia.audex.designsystem.ScreenHeader
import no.bellaybestia.audex.domain.model.ServerAccount
import no.bellaybestia.audex.domain.settings.AccentChoice
import no.bellaybestia.audex.domain.settings.HomeLook
import no.bellaybestia.audex.domain.settings.ProgressUnit
import no.bellaybestia.audex.domain.settings.ThemeMode
import no.bellaybestia.audex.domain.settings.UpdateChannel

/**
 * Settings: flat SERVERS list (small-caps section headers, hairline dividers,
 * a plain-text "needs login" tag — no chips), APPEARANCE (theme + accent),
 * WORD SYNC, and ABOUT (version → update page, report a problem).
 */
@Composable
fun SettingsScreen(
    onAddServer: () -> Unit,
    modifier: Modifier = Modifier,
    appVersion: String = "",
    onAbout: () -> Unit = {},
    onReport: () -> Unit = {},
    onStats: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val servers by viewModel.servers.collectAsState()
    val alignUrl by viewModel.alignServiceUrl.collectAsState()
    val codexUrl by viewModel.codexUrl.collectAsState()
    val codexToken by viewModel.codexToken.collectAsState()
    val codexEnabled by viewModel.codexEnabled.collectAsState()
    val skipSilence by viewModel.skipSilence.collectAsState()
    val themePrefs by viewModel.themePrefs.collectAsState()
    val updateChannel by viewModel.updateChannel.collectAsState()
    val listState = rememberLazyListState()

    // Tabs (item 8): the long settings list split into sections so each screen
    // stays short. Servers folds in Codex sync (both are connections); Playback
    // folds in Word sync (it's read-along playback); About gathers stats,
    // updates, version + report.
    val tabs = listOf("Servers", "Appearance", "Playback", "About")
    var tab by rememberSaveable { mutableStateOf(0) }

    LazyColumn(state = listState, modifier = modifier.fillMaxSize()) {
        item(key = "screen-title") { ScreenHeader(title = "Settings") }
        item(key = "settings-tabs") {
            FlatTabRow(tabs = tabs, selectedIndex = tab, onSelect = { tab = it })
        }

        // ---- SERVERS ----
        if (tab == 0) {
        itemsIndexed(servers, key = { _, server -> server.serverId }) { index, server ->
            ServerRow(server, onRemove = { viewModel.removeServer(server.serverId) })
            if (index < servers.lastIndex) FlatDivider()
        }
        item(key = "add-server") {
            if (servers.isNotEmpty()) FlatDivider()
            Text(
                text = "Add server",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onAddServer)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }
        item(key = "codex-header") { SectionHeader("Codex sync") }
        item(key = "codex-fields") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Push progress to Codex",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (codexEnabled) "On" else "Off",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (codexEnabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clickable { viewModel.setCodexEnabled(!codexEnabled) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
                CodexField(
                    label = "Codex URL",
                    placeholder = "https://codex.bellaybestia.no",
                    value = codexUrl,
                    onChange = viewModel::setCodexUrl,
                )
                CodexField(
                    label = "API token",
                    placeholder = "Codex → Settings → generate an API token",
                    value = codexToken,
                    onChange = viewModel::setCodexToken,
                )
                Text(
                    text = "When you listen, Audex sends your position to Codex right away " +
                        "(via its Audiobookshelf webhook) so it doesn't wait for Codex's " +
                        "periodic sync. Create the token in Codex; blank or Off disables it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        }

        // ---- APPEARANCE ----
        if (tab == 1) {
        item(key = "appearance-theme") {
            ChoiceRow(
                label = "Theme",
                options = listOf("System", "Light", "Dark"),
                selectedIndex = themePrefs.mode.ordinal,
                onSelect = { viewModel.setThemeMode(ThemeMode.entries[it]) },
            )
        }
        item(key = "appearance-look") {
            ChoiceRow(
                label = "Look",
                options = listOf("Nightfall", "Stacks"),
                selectedIndex = themePrefs.look.ordinal,
                onSelect = { viewModel.setLook(HomeLook.entries[it]) },
            )
        }
        item(key = "appearance-accent") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(text = "Accent", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = if (themePrefs.accent == AccentChoice.COVER) {
                        "Colours follow the cover of the book you're on."
                    } else {
                        "Pick Cover to let each book colour the app."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Row(
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    AccentChoice.entries.forEach { choice ->
                        val selected = themePrefs.accent == choice
                        val dot = when (choice) {
                            // The live derived accent — the swatch literally shows
                            // the colour the current cover is tinting the app with.
                            AccentChoice.COVER -> MaterialTheme.colorScheme.primary
                            AccentChoice.MONO -> Color(0xFFEDEDED)
                            AccentChoice.BLUE -> Color(0xFF5A9FE6)
                            AccentChoice.GOLD -> Color(0xFFDCA85F)
                            AccentChoice.CYAN -> Color(0xFF4DD0FF)
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clickable { viewModel.setAccent(choice) }
                                .padding(vertical = 4.dp),
                        ) {
                            Box(Modifier.size(14.dp).clip(CircleShape).background(dot))
                            Text(
                                text = choice.name.lowercase().replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.labelLarge,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 5.dp),
                            )
                        }
                    }
                }
            }
        }
        }

        // ---- PLAYBACK ----
        if (tab == 2) {
        item(key = "playback-skipsilence") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Skip silence",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (skipSilence) "On" else "Off",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (skipSilence) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clickable { viewModel.setSkipSilence(!skipSilence) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
                Text(
                    text = "Shortens long pauses in the narration so audiobooks play a little faster.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item(key = "playback-gotounit") {
            Column {
                ChoiceRow(
                    label = "Go to uses",
                    options = listOf("Percent", "Time & pages"),
                    selectedIndex = themePrefs.progressUnit.ordinal,
                    onSelect = { viewModel.setProgressUnit(ProgressUnit.entries[it]) },
                )
                Text(
                    text = "How the \"Go to…\" jump takes a target: a percentage, or the " +
                        "audiobook's timestamp and the ebook's page number.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        item(key = "wordsync-header") { SectionHeader("Word sync") }
        item(key = "wordsync-url") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    text = "Alignment service URL",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Box(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    if (alignUrl.isEmpty()) {
                        Text(
                            text = "http://192.168.68.212:8590 (blank = off)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    BasicTextField(
                        value = alignUrl,
                        onValueChange = viewModel::setAlignServiceUrl,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    text = "Self-hosted audex-align service that prepares audio↔text " +
                        "sync maps for precise read-along.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                if (alignUrl.isEmpty()) {
                    Text(
                        text = "Use default (http://192.168.68.212:8590)",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable {
                                viewModel.setAlignServiceUrl("http://192.168.68.212:8590")
                            }
                            .padding(vertical = 8.dp),
                    )
                }
            }
        }
        }

        // ---- ABOUT ----
        if (tab == 3) {
        item(key = "stats-row") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onStats)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Your listening", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "View",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            FlatDivider()
        }
        item(key = "updates-header") { SectionHeader("Updates") }
        item(key = "updates-channel") {
            Column {
                ChoiceRow(
                    label = "Channel",
                    options = listOf("Stable", "Beta"),
                    selectedIndex = updateChannel.ordinal,
                    onSelect = { viewModel.setUpdateChannel(UpdateChannel.entries[it]) },
                )
                Text(
                    text = "Beta gets fixes for in-app reports before they reach a release.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        item(key = "about-header") { SectionHeader("About") }
        item(key = "about-version") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onAbout)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "Version ${appVersion.ifBlank { "?" }}",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "What's new, all releases",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "View",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        item(key = "about-report") {
            FlatDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onReport)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "Report a problem",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "Bug, idea or feedback — goes straight to the developer",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "Open",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        }
    }
}

/** Flat labelled text field (borderless, placeholder when empty) — Codex sync. */
@Composable
private fun CodexField(
    label: String,
    placeholder: String,
    value: String,
    onChange: (String) -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 10.dp),
    )
    Box(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Flat inline single-choice row: label left, options right, active = accent. */
@Composable
private fun ChoiceRow(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        options.forEachIndexed { index, option ->
            Text(
                text = option,
                style = MaterialTheme.typography.labelLarge,
                color = if (index == selectedIndex) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable { onSelect(index) }
                    .padding(start = 14.dp, top = 4.dp, bottom = 4.dp),
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun ServerRow(
    server: ServerAccount,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Two-tap confirm, flat style: "Remove" arms it, "Remove?" fires it.
    var armed by remember(server.serverId) { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = server.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = server.baseUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Both states are named (mockup 2e), but only the actionable one is loud:
        // "Connected" sits back in the muted tone, "Needs login" shouts in error.
        Text(
            text = if (server.needsLogin) "Needs login" else "Connected",
            style = MaterialTheme.typography.labelSmall,
            color = if (server.needsLogin) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp),
        )
        Text(
            text = if (armed) "Remove?" else "Remove",
            style = MaterialTheme.typography.labelLarge,
            color = if (armed) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clickable { if (armed) onRemove() else armed = true }
                .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        )
    }
}

@Composable
private fun FlatDivider() {
    HorizontalDivider(
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}
