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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import no.bellaybestia.audex.domain.download.Downloads
import no.bellaybestia.audex.domain.model.Format
import no.bellaybestia.audex.domain.playback.PlaybackController
import no.bellaybestia.audex.domain.reader.AlignmentRepository
import no.bellaybestia.audex.domain.reader.EbookProgressWriter
import no.bellaybestia.audex.domain.reader.Highlight
import no.bellaybestia.audex.domain.reader.HighlightsRepository
import no.bellaybestia.audex.domain.reader.ReaderPrefs
import no.bellaybestia.audex.domain.reader.ReaderSettingsStore
import no.bellaybestia.audex.domain.reader.ReaderTheme
import no.bellaybestia.audex.domain.reader.SyncMap
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
    /** Absolute audio position in seconds — the sync-map lookup key. */
    val positionS: Double,
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
    private val alignmentRepository: AlignmentRepository,
    private val readerSettings: ReaderSettingsStore,
    private val highlightsRepository: HighlightsRepository,
    themeSettings: no.bellaybestia.audex.domain.settings.ThemeSettings,
    playbackController: PlaybackController,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** Which unit the reader's "Go to…" jump uses (Settings → Playback). */
    val progressUnit: StateFlow<no.bellaybestia.audex.domain.settings.ProgressUnit> =
        themeSettings.prefs
            .map { it.progressUnit }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                no.bellaybestia.audex.domain.settings.ProgressUnit.PERCENT,
            )

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

    /**
     * Follow-audio toggle, ON by default: opening the ebook of a book you're
     * listening to should land you where the narration is, without hunting for
     * a "Follow audio" button first (it's a no-op when no audio companion is
     * loaded). Turn it off to read ahead independently.
     */
    private val _followAudio = MutableStateFlow(true)
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
                    positionS = playback.positionMs / 1000.0,
                )
            } else {
                null
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Word-sync map for this work's audio (docs/10); null → proportional follow. */
    private val _syncMap = MutableStateFlow<SyncMap?>(null)
    val syncMap: StateFlow<SyncMap?> = _syncMap.asStateFlow()

    /** This book's saved highlights (newest first) — painted as decorations. */
    val highlights: StateFlow<List<Highlight>> =
        highlightsRepository.forItem(serverId, libraryItemId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Save the current text selection (its serialized locator + the passage). */
    fun addHighlight(locatorJson: String, text: String) {
        viewModelScope.launch { highlightsRepository.add(serverId, libraryItemId, locatorJson, text) }
    }

    fun removeHighlight(id: String) {
        viewModelScope.launch { highlightsRepository.remove(id) }
    }

    /** Persisted appearance (font %, theme); the screen submits it to Readium. */
    val readerPrefs: StateFlow<ReaderPrefs> = readerSettings.prefs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderPrefs())

    fun adjustFontSize(deltaPct: Int) {
        val current = readerPrefs.value
        viewModelScope.launch {
            readerSettings.set(current.copy(fontSizePct = current.fontSizePct + deltaPct))
        }
    }

    fun cycleTheme() {
        val current = readerPrefs.value
        val next = ReaderTheme.entries[(current.theme.ordinal + 1) % ReaderTheme.entries.size]
        viewModelScope.launch { readerSettings.set(current.copy(theme = next)) }
    }

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
                    absLocation = absCfiFor(locator),
                    progress = progress,
                )
            }
        }
    }

    private suspend fun resolveAudioSiblings() {
        val workId = catalogRepository.workIdForItem(serverId, libraryItemId) ?: return
        catalogRepository.editionsForWork(workId).collect { editions ->
            val audio = editions.filter { it.format == Format.AUDIO }
            audioItemKeys.value = audio.map { "${it.serverId}|${it.libraryItemId}" }.toSet()
            // Load the word-sync map once (first audio sibling that has one).
            if (_syncMap.value == null) {
                for (edition in audio) {
                    val map = alignmentRepository.syncMap(edition.serverId, edition.libraryItemId)
                    if (map != null) {
                        _syncMap.value = map
                        break
                    }
                }
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
        val positions = publication.positions()
        android.util.Log.i(
            "AudexReader",
            "openBook ready: readingOrder=${publication.readingOrder.size} positions=${positions.size} " +
                "file=${File(path).name} (${File(path).length()} bytes)",
        )
        _state.value = ReaderUiState.Ready(
            publication = publication,
            navigatorFactory = EpubNavigatorFactory(publication),
            initialLocator = restoreLocator(),
            positions = positions,
        )
    }

    /**
     * Restore the last position. Our own reader writes Readium locator JSON (exact
     * restore); an ABS `epubcfi` (read in the Audiobookshelf app, synced back) resolves
     * to its chapter so we resume roughly there instead of at the beginning.
     */
    private suspend fun restoreLocator(): Locator? {
        val saved = ebookProgressWriter.lastPosition(serverId, libraryItemId)?.location ?: return null
        if (saved.startsWith("{")) {
            return runCatching { Locator.fromJSON(JSONObject(saved)) }.getOrNull()
        }
        if (saved.startsWith("epubcfi(")) return locatorFromAbsCfi(saved)
        return null
    }

    /** Chapter-level Locator for an ABS-style epubcfi `epubcfi(/6/{2K}...)` → readingOrder[K-1]. */
    private fun locatorFromAbsCfi(cfi: String): Locator? {
        val pub = publication ?: return null
        val step = Regex("""/6/(\d+)""").find(cfi)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return null
        val link = pub.readingOrder.getOrNull(step / 2 - 1) ?: return null
        return pub.locatorFromLink(link)
    }

    /**
     * An Audiobookshelf/epub.js-compatible `epubcfi` for the CURRENT chapter, so the position
     * we upload to ABS can be read by the official ABS app (it stores epubcfi strings; a
     * Readium locator JSON there makes it reset to the title page). Coarse (chapter start) —
     * ABS's epub.js and Readium generate CFIs differently, so we anchor at the spine item and
     * let the % carry the fine position. Null when the href isn't in the reading order (rare)
     * → the upload becomes %-only, which leaves ABS's existing page pointer untouched.
     */
    private fun absCfiFor(locator: Locator): String? {
        val order = publication?.readingOrder ?: return null
        val target = locator.href.toString().substringBefore('#').substringAfterLast('/')
        val idx = order.indexOfFirst {
            it.href.toString().substringBefore('#').substringAfterLast('/') == target
        }
        if (idx < 0) return null
        // Include the filename as the CFI assertion, matching what the ABS app itself writes
        // (e.g. epubcfi(/6/66[c2VV.xhtml]!/4/1:0)); the /4/1:0 tail lands at the chapter start.
        return "epubcfi(/6/${2 * (idx + 1)}[$target]!/4/1:0)"
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
