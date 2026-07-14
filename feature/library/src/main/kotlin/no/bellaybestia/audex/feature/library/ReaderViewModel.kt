package no.bellaybestia.audex.feature.library

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import no.bellaybestia.audex.domain.download.Downloads
import no.bellaybestia.audex.domain.reader.EbookProgressWriter
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

/** What the reader screen should show. */
sealed interface ReaderUiState {
    data object Loading : ReaderUiState
    data object NoEbook : ReaderUiState
    data class Error(val message: String) : ReaderUiState
    data class Ready(
        val publication: Publication,
        val navigatorFactory: EpubNavigatorFactory,
        val initialLocator: Locator?,
    ) : ReaderUiState
}

/**
 * Opens the downloaded EPUB with the Readium streamer and exposes an
 * [EpubNavigatorFactory] for the screen to embed. Locator changes stream in via
 * [onLocatorChanged], are debounced (~1.2s of no page turns), and flow out
 * through the offline-safe ebook queue — never the audio path (docs/03).
 */
@HiltViewModel
class ReaderViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloads: Downloads,
    private val ebookProgressWriter: EbookProgressWriter,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val serverId: String = checkNotNull(savedStateHandle["serverId"])
    private val libraryItemId: String = checkNotNull(savedStateHandle["itemId"])
    val title: String = savedStateHandle.get<String>("title")?.takeIf { it.isNotBlank() } ?: "Reader"

    private val _state = MutableStateFlow<ReaderUiState>(ReaderUiState.Loading)
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private val latestLocator = MutableStateFlow<Locator?>(null)
    private var publication: Publication? = null

    init {
        viewModelScope.launch { openBook() }
        // Debounced position sync: each page turn resets the timer, so a burst
        // of flips writes once. collectLatest keeps this free of @FlowPreview.
        viewModelScope.launch {
            latestLocator.filterNotNull().collectLatest { locator ->
                delay(1_200)
                val progress = locator.locations.totalProgression ?: return@collectLatest
                ebookProgressWriter.record(
                    serverId = serverId,
                    libraryItemId = libraryItemId,
                    location = locator.toJSON().toString(),
                    progress = progress,
                )
            }
        }
    }

    private suspend fun openBook() {
        val path = downloads.localEbookPath(serverId, libraryItemId)
        if (path == null) {
            _state.value = ReaderUiState.NoEbook
            return
        }
        val httpClient = DefaultHttpClient()
        val assetRetriever = AssetRetriever(context.contentResolver, httpClient)
        val opener = PublicationOpener(
            publicationParser = DefaultPublicationParser(
                context,
                httpClient = httpClient,
                assetRetriever = assetRetriever,
                pdfFactory = null,
            ),
        )
        val asset = assetRetriever.retrieve(File(path)).getOrElse {
            _state.value = ReaderUiState.Error("Couldn't open the downloaded file (${it.message}).")
            return
        }
        val publication = opener.open(asset, allowUserInteraction = false).getOrElse {
            _state.value = ReaderUiState.Error("Couldn't parse this ebook (${it.message}).")
            return
        }
        this.publication = publication
        _state.value = ReaderUiState.Ready(
            publication = publication,
            navigatorFactory = EpubNavigatorFactory(publication),
            initialLocator = restoreLocator(),
        )
    }

    /**
     * Restore the last position. Only Readium locator JSON round-trips; a CFI
     * written by the ABS web reader doesn't parse here and falls back to the
     * beginning (docs/09 — position handoff between renderers is Tier 1 work).
     */
    private suspend fun restoreLocator(): Locator? {
        val saved = ebookProgressWriter.lastPosition(serverId, libraryItemId) ?: return null
        val json = saved.location?.takeIf { it.startsWith("{") } ?: return null
        return runCatching { Locator.fromJSON(JSONObject(json)) }.getOrNull()
    }

    /** Called by the screen on every navigator locator change. */
    fun onLocatorChanged(locator: Locator) {
        latestLocator.value = locator
    }

    override fun onCleared() {
        publication?.close()
        super.onCleared()
    }
}
