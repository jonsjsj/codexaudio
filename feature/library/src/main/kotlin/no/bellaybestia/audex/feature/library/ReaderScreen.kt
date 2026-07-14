package no.bellaybestia.audex.feature.library

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commitNow
import androidx.hilt.navigation.compose.hiltViewModel
import kotlin.math.abs
import kotlin.math.roundToInt
import no.bellaybestia.audex.domain.reader.ReaderPrefs
import no.bellaybestia.audex.domain.reader.ReaderTheme
import no.bellaybestia.audex.domain.reader.SyncMap
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url

private const val READER_FRAGMENT_TAG = "audex_epub_reader"

/**
 * Ebook reader: renders the downloaded EPUB with Readium's
 * [EpubNavigatorFragment] (embedded via FragmentContainerView) and reports each
 * locator change to [ReaderViewModel.onLocatorChanged] (debounced → offline-safe
 * ebook queue → ABS ebookProgress). The last Readium position is restored.
 */
@Composable
fun ReaderScreen(
    modifier: Modifier = Modifier,
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    when (val s = state) {
        ReaderUiState.Loading -> ReaderMessage(viewModel.title, "Opening…", modifier)
        ReaderUiState.NoEbook -> ReaderMessage(
            viewModel.title,
            "Download this ebook first (from its detail screen) to open it here.",
            modifier,
        )
        is ReaderUiState.Error -> ReaderMessage(viewModel.title, s.message, modifier)
        is ReaderUiState.Ready -> EpubReader(s, viewModel, modifier)
    }
}

@OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
@Composable
private fun EpubReader(
    ready: ReaderUiState.Ready,
    viewModel: ReaderViewModel,
    modifier: Modifier = Modifier,
) {
    val activity = LocalContext.current.findFragmentActivity() ?: return
    var navigator by remember { mutableStateOf<EpubNavigatorFragment?>(null) }

    val companion by viewModel.audioCompanion.collectAsState()
    val followAudio by viewModel.followAudio.collectAsState()
    val currentProgression by viewModel.currentProgression.collectAsState()
    val syncMap by viewModel.syncMap.collectAsState()
    val prefs by viewModel.readerPrefs.collectAsState()
    var showAppearance by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        AppearanceBar(
            prefs = prefs,
            expanded = showAppearance,
            onToggle = { showAppearance = !showAppearance },
            onFontDelta = viewModel::adjustFontSize,
            onCycleTheme = viewModel::cycleTheme,
        )
        // Read-along bar (docs/09): only when this work's audio edition is loaded.
        // With a word-sync map (docs/10) the audio position maps through real
        // alignment anchors; otherwise it falls back to the proportional guess.
        companion?.let { audio ->
            val audioProgression = syncMap?.progressionAt(audio.positionS) ?: audio.fraction
            ReadAlongBar(
                audio = audio,
                precise = syncMap != null,
                following = followAudio,
                showJump = !followAudio &&
                    abs((currentProgression ?: 0.0) - audioProgression) > 0.02,
                onToggleFollow = { viewModel.setFollowAudio(!followAudio) },
                onJump = {
                    locatorForFraction(ready.positions, audioProgression)
                        ?.let { navigator?.go(it) }
                },
            )
        }

        Box(Modifier.fillMaxSize()) {
            // Turn one page-position forward/back via navigator.go(locator).
            // The WebView's own swipe paging is unreliable: readium-css can
            // leave a few px of sub-page overflow in a resource, and its JS
            // then "handles" every swipe without moving OR handing over to the
            // pager (runtime-verified: scrollRight()==true forever at 364px
            // scrollWidth on a 360px viewport). Position locators cross
            // resource boundaries through scrollToLocator instead.
            fun turnPage(delta: Int) {
                val current = navigator?.currentLocator?.value ?: return
                val position = current.locations.position ?: return // 1-based
                val target = ready.positions.getOrNull(position - 1 + delta) ?: return
                navigator?.go(target)
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    FragmentContainerView(context).apply { id = R.id.reader_container }
                },
                update = { container ->
                    val fm = activity.supportFragmentManager
                    // The factory must be in place before the fragment is
                    // instantiated. Known limitation: if the process dies while
                    // the reader is open, restoration happens before this runs —
                    // the nav route re-opens the book from scratch instead.
                    fun attachReader() {
                        if (fm.findFragmentByTag(READER_FRAGMENT_TAG) != null) return
                        fm.fragmentFactory = ready.navigatorFactory.createFragmentFactory(
                            initialLocator = ready.initialLocator,
                        )
                        fm.commitNow {
                            setReorderingAllowed(true)
                            add(
                                R.id.reader_container,
                                EpubNavigatorFragment::class.java,
                                Bundle.EMPTY,
                                READER_FRAGMENT_TAG,
                            )
                        }
                        navigator = fm.findFragmentByTag(READER_FRAGMENT_TAG) as? EpubNavigatorFragment
                    }
                    val existing = fm.findFragmentByTag(READER_FRAGMENT_TAG) as? EpubNavigatorFragment
                    when {
                        existing != null -> if (navigator == null) navigator = existing
                        // Attach only after the container has its REAL size:
                        // committing during the first (unsized) layout pass made
                        // Readium's JS compute the column grid against a wrong
                        // viewport — pages misaligned and resource-boundary
                        // crossing dead (runtime-verified on the emulator).
                        container.isLaidOut -> attachReader()
                        else -> container.doOnLayout { attachReader() }
                    }
                },
            )

            // Edge tap zones (left = back, right = forward), the reliable page
            // turn. Center stays untouched for the WebView (links, selection).
            Row(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .weight(0.22f)
                        .fillMaxHeight()
                        .pointerInput(Unit) { detectTapGestures { turnPage(-1) } },
                )
                Spacer(Modifier.weight(0.56f))
                Box(
                    Modifier
                        .weight(0.22f)
                        .fillMaxHeight()
                        .pointerInput(Unit) { detectTapGestures { turnPage(1) } },
                )
            }
        }
    }

    // Stream locator changes (page turns, chapter jumps) into the debounced sync.
    LaunchedEffect(navigator) {
        navigator?.currentLocator?.collect { viewModel.onLocatorChanged(it) }
    }

    // Apply persisted appearance whenever it (or the navigator) changes.
    LaunchedEffect(prefs, navigator) {
        navigator?.submitPreferences(
            EpubPreferences(
                fontSize = prefs.fontSizePct / 100.0,
                theme = when (prefs.theme) {
                    ReaderTheme.LIGHT -> Theme.LIGHT
                    ReaderTheme.SEPIA -> Theme.SEPIA
                    ReaderTheme.DARK -> Theme.DARK
                },
            ),
        )
    }

    // Follow-audio: audio is the master clock; jump the page only when the
    // target PAGE changes, so second-by-second ticks don't thrash the
    // navigator. Sync-map progression when aligned, proportional otherwise.
    val audioNow = companion
    val followTargetIndex = if (followAudio && audioNow?.isPlaying == true) {
        val progression = syncMap?.progressionAt(audioNow.positionS) ?: audioNow.fraction
        targetPositionIndex(ready.positions, progression)
    } else {
        null
    }

    // Sentence highlighting (map v1.1): tint the anchor currently being
    // narrated. Keyed on the anchor's char offset so a highlight is applied
    // once per sentence, not per playback tick.
    val narrationAnchor = if (audioNow?.isPlaying == true) {
        syncMap?.anchorAt(audioNow.positionS)?.takeIf { !it.text.isNullOrBlank() }
    } else {
        null
    }
    LaunchedEffect(narrationAnchor?.c0, navigator) {
        val decorable = navigator as? DecorableNavigator ?: return@LaunchedEffect
        val map = syncMap
        val decorations = if (narrationAnchor != null && map != null) {
            narrationLocator(ready.publication, map, narrationAnchor)?.let { locator ->
                listOf(
                    Decoration(
                        id = "narration",
                        locator = locator,
                        style = Decoration.Style.Highlight(tint = 0x66FFC107.toInt()),
                    ),
                )
            } ?: emptyList()
        } else {
            emptyList()
        }
        decorable.applyDecorations(decorations, group = "readalong")
    }
    LaunchedEffect(followTargetIndex, navigator) {
        val index = followTargetIndex ?: return@LaunchedEffect
        ready.positions.getOrNull(index)?.let { navigator?.go(it) }
    }

    // Leaving the screen tears the fragment down; the VM keeps the publication.
    DisposableEffect(Unit) {
        onDispose {
            val fm = activity.supportFragmentManager
            fm.findFragmentByTag(READER_FRAGMENT_TAG)?.let { fragment ->
                if (!fm.isStateSaved) {
                    fm.commitNow { remove(fragment) }
                }
            }
            navigator = null
        }
    }
}

/**
 * Flat appearance strip: a collapsed "Aa" affordance expanding to font-size
 * steppers and a theme cycler. Values persist app-wide (ReaderSettingsStore).
 */
@Composable
private fun AppearanceBar(
    prefs: ReaderPrefs,
    expanded: Boolean,
    onToggle: () -> Unit,
    onFontDelta: (Int) -> Unit,
    onCycleTheme: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            if (expanded) {
                Text(
                    text = "A−",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { onFontDelta(-10) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
                Text(
                    text = "${prefs.fontSizePct}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "A+",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { onFontDelta(+10) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = when (prefs.theme) {
                        ReaderTheme.LIGHT -> "Light"
                        ReaderTheme.SEPIA -> "Sepia"
                        ReaderTheme.DARK -> "Dark"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable(onClick = onCycleTheme)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = "Aa",
                style = MaterialTheme.typography.titleMedium,
                color = if (expanded) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** Flat read-along strip: audio %, follow toggle, and a one-tap catch-up jump. */
@Composable
private fun ReadAlongBar(
    audio: AudioCompanion,
    precise: Boolean,
    following: Boolean,
    showJump: Boolean,
    onToggleFollow: () -> Unit,
    onJump: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val playGlyph = if (audio.isPlaying) "▶" else "⏸"
            Text(
                text = "Audio ${(audio.fraction * 100).roundToInt()}% $playGlyph" +
                    if (precise) " · synced" else "",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (showJump) {
                Text(
                    text = "Jump to audio",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable(onClick = onJump)
                        .padding(vertical = 4.dp),
                )
            }
            Text(
                text = if (following) "Following ✓" else "Follow audio",
                style = MaterialTheme.typography.labelLarge,
                color = if (following) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable(onClick = onToggleFollow)
                    .padding(vertical = 4.dp),
            )
        }
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

private fun targetPositionIndex(positions: List<Locator>, fraction: Double): Int? {
    if (positions.isEmpty()) return null
    return (fraction * (positions.size - 1)).roundToInt().coerceIn(0, positions.size - 1)
}

/**
 * Locator for a narration anchor: the anchor's chapter link + within-chapter
 * progression, with the anchored book string as the text highlight (Readium's
 * decorator resolves the DOM range by text-quote matching). Null when the map
 * predates v1.1 or the href can't be matched to the publication.
 */
private fun narrationLocator(
    publication: Publication,
    map: SyncMap,
    anchor: no.bellaybestia.audex.domain.reader.SyncAnchor,
): Locator? {
    val href = anchor.href ?: return null
    val text = anchor.text ?: return null
    val link = Url(href)?.let { publication.linkWithHref(it) }
        ?: publication.readingOrder.firstOrNull {
            it.href.toString().endsWith(href) || href.endsWith(it.href.toString())
        }
        ?: return null
    val base = publication.locatorFromLink(link) ?: return null
    return base.copy(
        locations = base.locations.copy(progression = map.chapterProgression(anchor)),
        text = Locator.Text(highlight = text),
    )
}

private fun locatorForFraction(positions: List<Locator>, fraction: Double): Locator? =
    targetPositionIndex(positions, fraction)?.let { positions[it] }

@Composable
private fun ReaderMessage(title: String, message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private fun Context.findFragmentActivity(): FragmentActivity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is FragmentActivity) return current
        current = current.baseContext
    }
    return null
}
