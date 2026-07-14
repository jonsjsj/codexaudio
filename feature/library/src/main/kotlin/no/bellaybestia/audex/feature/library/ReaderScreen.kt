package no.bellaybestia.audex.feature.library

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commitNow
import androidx.hilt.navigation.compose.hiltViewModel
import org.readium.r2.navigator.epub.EpubNavigatorFragment

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

    Box(modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                FragmentContainerView(context).apply { id = R.id.reader_container }
            },
            update = {
                val fm = activity.supportFragmentManager
                val existing = fm.findFragmentByTag(READER_FRAGMENT_TAG) as? EpubNavigatorFragment
                if (existing == null) {
                    // The factory must be in place before the fragment is
                    // instantiated. Known limitation: if the process dies while
                    // the reader is open, restoration happens before this runs —
                    // the nav route re-opens the book from scratch instead.
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
                } else if (navigator == null) {
                    navigator = existing
                }
            },
        )
    }

    // Stream locator changes (page turns, chapter jumps) into the debounced sync.
    LaunchedEffect(navigator) {
        navigator?.currentLocator?.collect { viewModel.onLocatorChanged(it) }
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
