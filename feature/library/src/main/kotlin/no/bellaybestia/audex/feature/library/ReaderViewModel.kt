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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import no.bellaybestia.audex.domain.download.Downloads
import no.bellaybestia.audex.domain.model.Format
import no.bellaybestia.audex.domain.playback.PlaybackController
import no.bellaybestia.audex.domain.reader.EbookProgressWriter
import no.bellaybestia.audex.domain.repository.CatalogRepository
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positions
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
        /** Page-level locators (Readium positions service) for fraction→page jumps. */
        val positions: List<Locator>,
    ) : ReaderUiState
}

/**
 * The same work's AUDIO edition as seen from the reader (docs/09 read-along):
 * present whenever playback has that edition loaded, playing or paused.
 */
data class AudioCompanion(
    val isPlaying: Boolean,
    /** Overall audio progress 0..1 (position / duration). */
    val fraction: Double,
)

/**
 * Opens the downloaded EPUB with the Readium streamer and exposes an
 * [EpubNavigatorFactory] for the screen to embed. Locator changes stream in via
 * [onLocatorChanged], are debounced (~1.2s of no page turns), and flow out
 * through the offline-safe ebook queue — never the audio path (docs/03).
 *
 * Read-along (docs/09 Tier 1/2, proportional — no timing data needed): when the
 * SAME WORK's audio edition is loaded in the player, [audioCompanion] carries
 * its live fraction; with [followAudio] on, the screen keeps the page in step
 * (audio stays the master clock). Word-level sync is Tier 3 (forced alignment).
 */
@HiltViewModel
class ReaderViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloads: Downloads,
    private val ebookProgressWriter: EbookProgressWriter,
    private val catalogRepository: CatalogRepository,
    playbackController: PlaybackController,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val serverId: String = checkNotNull(savedStateHandle["serverId"])
    private val libraryItemId: String = checkNotNull(savedStateHandle["itemId"])
    val title: String = savedStateHandle.get<String>("title")?.takeIf { it.isNotBlank() } ?: "Reader"

    private val _state = MutableStateFlow<ReaderUiState>(ReaderUiState.Loading)
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private val latestLocator = MutableStateFlow<Locator?>(null)
    private var publication: Publication? = null

    /** Where the reader currently is (totalProgression 0..1), for the jump chip. */
    private val _currentProgression = MutableStateFlow<Double?>(null)
    val currentProgression: StateFlow<Double?> = _currentProgression.asStateFlow()

    /** Audio↔ebook bridging: itemIds of this work's AUDIO editions (any server). */
    private val audioItemKeys = MutableStateFlow<Set<String>>(emptySet())

    /** Follow-audio toggle (off by default; the reader is manual until asked). */
    private val _followAudio = MutableStateFlow(false)
    val followAudio: StateFlow<Boolean> = _followAudio.asStateFlow()

    fun setFollowAudio(enabled: Boolean) {
        _followAudio.value = enabled
    }

    val audioCompanion: StateFlow<AudioCompanion?> =
        combine(playbackController.state, audioItemKeys) { playback, keys ->
            val playingKey = playback.serverId?.let { s -> playback.libraryItemId?.let { "$s|$it" } }
            if (playingKey != null && playingKey in keys && playback.durationMs > 0) {
                AudioCompanion(
                    isPlaying = playback.isPlaying,
                    fraction = (playback.positionMs.toDouble() / playback.durationMs).coerceIn(0.0, 1.0),
                )
            } else {
                null
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch { openBook() }
        viewModelScope.launch { resolveAudioSiblings() }
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

    private suspend fun resolveAudioSiblings() {
        val workId = catalogRepository.workIdForItem(serverId, libraryItemId) ?: return
        catalogRepository.editionsForWork(workId).collect { editions ->
            audioItemKeys.value = editions
                .filter { it.format == Format.AUDIO }
                .map { "${it.serverId}|${it.libraryItemId}" }
                .toSet()
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
            positions = publication.positions(),
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
        _currentProgression.value = locator.locations.totalProgression
    }

    override fun onCleared() {
        publication?.close()
        super.onCleared()
    }
}
