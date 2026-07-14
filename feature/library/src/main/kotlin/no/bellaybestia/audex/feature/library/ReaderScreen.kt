package no.bellaybestia.audex.feature.library

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator

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

    Column(modifier.fillMaxSize()) {
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
        }
    }

    // Stream locator changes (page turns, chapter jumps) into the debounced sync.
    LaunchedEffect(navigator) {
        navigator?.currentLocator?.collect { viewModel.onLocatorChanged(it) }
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
