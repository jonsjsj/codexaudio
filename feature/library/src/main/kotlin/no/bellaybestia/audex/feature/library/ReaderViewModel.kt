package no.bellaybestia.audex.feature.library

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import no.bellaybestia.audex.domain.download.Downloads
import no.bellaybestia.audex.domain.model.Edition
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

/**
 * How far ahead (fraction of the book) the audiobook must be past your last-read
 * ebook page before opening the ebook jumps to the audiobook's spot. Avoids a jump
 * on tiny rounding differences when the two are effectively level.
 */
private const val CROSS_FORMAT_MARGIN = 0.005

/**
 * At/after this fraction the book counts as finished (the tail covers end credits/outro),
 * so cross-format sync stops being enforced — a completed book never reopens trapped at
 * the end; re-reading starts free.
 */
private const val BOOK_DONE_THRESHOLD = 0.97

/** A jump larger than this fraction of the book auto-drops a "you were here" bookmark. */
private const val JUMP_BOOKMARK_THRESHOLD = 0.015
private const val AUTO_BOOKMARK_PREFIX = "Left off · "
private const val MAX_AUTO_BOOKMARKS = 5

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
 * Audio-ebook sync: your read position is authoritative — the page never
 * auto-jumps to the audiobook. When the SAME WORK's audio edition is loaded,
 * [audioCompanion] carries its live position so the narrated sentence can be
 * highlighted as you read; positions also mirror across formats so switching
 * between listening and reading resumes in the right place.
 */
@HiltViewModel
class ReaderViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloads: Downloads,
    private val ebookProgressWriter: EbookProgressWriter,
    private val catalogRepository: CatalogRepository,
    private val alignmentRepository: AlignmentRepository,
    private val bookmarksRepository: no.bellaybestia.audex.domain.playback.BookmarksRepository,
    private val readerSettings: ReaderSettingsStore,
    private val highlightsRepository: HighlightsRepository,
    private val activityRecorder: no.bellaybestia.audex.domain.settings.ActivityRecorder,
    themeSettings: no.bellaybestia.audex.domain.settings.ThemeSettings,
    private val playbackController: PlaybackController,
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

    /** Called by the reader screen's foreground ticker: adds real reading time
     * (this book, today) to the activity ledger that feeds "Your activity". */
    fun recordReadSeconds(seconds: Double) {
        if (seconds <= 0.0) return
        viewModelScope.launch {
            activityRecorder.record(
                serverId,
                libraryItemId,
                no.bellaybestia.audex.domain.settings.ActivityKind.READ,
                seconds,
            )
        }
    }

    /** Audio↔ebook bridging: itemIds of this work's AUDIO editions (any server). */
    private val audioItemKeys = MutableStateFlow<Set<String>>(emptySet())

    /** The work's primary AUDIO edition — for offline follow + cross-format sync. */
    private val _audioEdition = MutableStateFlow<Edition?>(null)

    /**
     * The audiobook's SAVED progress fraction. This is the follow target when the
     * audio isn't loaded in the player (companion == null): pressing "Follow
     * audio" then still jumps the reader to where the audiobook is, instead of
     * doing nothing.
     */
    val savedAudioFraction: StateFlow<Double?> = _audioEdition
        .map { it?.fraction }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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

    /** Bumped whenever bookmarks change (add/remove) so the flows below re-fetch. */
    private val _bookmarksRefresh = MutableStateFlow(0)

    /**
     * Audio bookmarks (including the auto "you were here" markers dropped on a big
     * skip) for this work's audio edition — surfaced in the reader's Go-to so you can
     * jump the TEXT to any of them (cross-format).
     */
    val audioBookmarks: StateFlow<List<no.bellaybestia.audex.domain.playback.Bookmark>> =
        combine(_audioEdition, _bookmarksRefresh) { ed, _ ->
            ed?.let {
                runCatching { bookmarksRepository.bookmarksFor(it.serverId, it.libraryItemId) }
                    .getOrDefault(emptyList())
            } ?: emptyList()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** A bookmark as a fraction (0..1) of the book, for scrubber ticks. */
    data class BookmarkTick(val fraction: Float, val label: String, val timeS: Long)

    /** Bookmarks positioned on the reading scrubber (time → fraction via audio duration). */
    val bookmarkTicks: StateFlow<List<BookmarkTick>> =
        combine(audioBookmarks, _audioEdition) { bms, ed ->
            val dur = ed?.durationS?.toDouble()?.takeIf { it > 0.0 } ?: return@combine emptyList()
            bms.map { BookmarkTick((it.timeS / dur).toFloat().coerceIn(0f, 1f), it.title, it.timeS) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Furthest point reached in EITHER format (0..1) — a marker on the scrubber to jump
     * back to the tip. Null once the book is done: past the end, the furthest is
     * irrelevant and re-reading is free.
     */
    val furthestFraction: StateFlow<Float?> =
        combine(_currentProgression, _audioEdition) { read, ed ->
            val audio = ed?.fraction ?: 0.0
            val furthest = maxOf(read ?: 0.0, audio)
            if (furthest <= 0.0 || furthest >= BOOK_DONE_THRESHOLD) null else furthest.toFloat()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Whether this work has an audiobook edition — drives the in-reader mini-player. */
    val hasAudio: StateFlow<Boolean> =
        _audioEdition.map { it != null }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Play/pause the audiobook from the reader; starts it if it isn't loaded yet. */
    fun toggleAudio() {
        if (audioCompanion.value != null) {
            playbackController.togglePlayPause()
            return
        }
        val ed = _audioEdition.value ?: return
        viewModelScope.launch { runCatching { playbackController.play(ed.serverId, ed.libraryItemId, title, null) } }
    }

    fun audioSkipBack() = playbackController.skipBackward()

    fun audioSkipForward() = playbackController.skipForward()

    /** Drop a bookmark at the current reading spot (kept on the server → Codex sees it). */
    fun addReadingBookmark() {
        val frac = _currentProgression.value ?: return
        val ed = _audioEdition.value ?: return
        val dur = ed.durationS ?: return
        viewModelScope.launch {
            runCatching {
                bookmarksRepository.add(
                    ed.serverId, ed.libraryItemId, (frac * dur).toLong(),
                    "Bookmark · ${(frac * 100).roundToInt()}%",
                )
            }
            _bookmarksRefresh.value++
        }
    }

    /**
     * Commit an explicit jump in the reader (scrubber release / tap / bookmark). Re-syncs
     * the audiobook to [toFraction] in BOTH directions (an explicit jump overrides the
     * forward-only listen mirror), and — if you moved more than ~a page — auto-drops a
     * "Left off" bookmark at [fromFraction] so an accidental jump never loses your place.
     */
    fun onReaderSeekEnd(fromFraction: Double, toFraction: Double) {
        val ed = _audioEdition.value ?: return
        val dur = ed.durationS
        viewModelScope.launch {
            runCatching { catalogRepository.setAudioFraction(ed.serverId, ed.libraryItemId, toFraction, dur) }
            if (dur != null && kotlin.math.abs(toFraction - fromFraction) > JUMP_BOOKMARK_THRESHOLD) {
                runCatching { autoBookmark(ed.serverId, ed.libraryItemId, dur, fromFraction) }
                _bookmarksRefresh.value++
            }
        }
    }

    /** Drop a "Left off" bookmark at [fraction] unless one is already near it, and keep
     *  the auto-bookmarks pruned to the most recent few (dedupe by closeness). */
    private suspend fun autoBookmark(serverId: String, itemId: String, durationS: Long, fraction: Double) {
        val marks = bookmarksRepository.bookmarksFor(serverId, itemId)
        val autos = marks.filter { it.title.startsWith(AUTO_BOOKMARK_PREFIX) }
        val near = autos.any { kotlin.math.abs(it.timeS / durationS.toDouble() - fraction) < JUMP_BOOKMARK_THRESHOLD }
        if (near) return
        bookmarksRepository.add(
            serverId, itemId, (fraction * durationS).toLong(),
            "$AUTO_BOOKMARK_PREFIX${(fraction * 100).roundToInt()}%",
        )
        val after = bookmarksRepository.bookmarksFor(serverId, itemId)
            .filter { it.title.startsWith(AUTO_BOOKMARK_PREFIX) }
        if (after.size > MAX_AUTO_BOOKMARKS) {
            after.sortedBy { it.createdAt }.take(after.size - MAX_AUTO_BOOKMARKS)
                .forEach { runCatching { bookmarksRepository.remove(it) } }
        }
    }

    /**
     * The audiobook's position in seconds — live when it's playing, else its saved
     * spot. The target for the reader Go-to's "Jump to the audiobook" (cross-format).
     */
    val audioPositionS: StateFlow<Double?> =
        combine(audioCompanion, _audioEdition) { comp, ed ->
            comp?.positionS ?: ed?.let { e -> e.durationS?.let { d -> e.fraction * d.toDouble() } }
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
                // Cross-format sync: your read position is authoritative. Nudge the
                // audiobook's saved position forward to match — proportionally — so
                // switching to listening resumes where you read. mirrorAudioProgress is
                // forward-only, so reading can never rewind the audiobook.
                _audioEdition.value?.let { audio ->
                    runCatching {
                        catalogRepository.mirrorAudioProgress(
                            audio.serverId, audio.libraryItemId, progress, audio.durationS,
                        )
                    }
                }
            }
        }
    }

    private suspend fun resolveAudioSiblings() {
        val workId = catalogRepository.workIdForItem(serverId, libraryItemId) ?: return
        catalogRepository.editionsForWork(workId).collect { editions ->
            val audio = editions.filter { it.format == Format.AUDIO }
            audioItemKeys.value = audio.map { "${it.serverId}|${it.libraryItemId}" }.toSet()
            // Prefer an audio edition that has a duration + some saved progress.
            _audioEdition.value = audio.maxByOrNull { it.fraction } ?: audio.firstOrNull()
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
            initialLocator = resolveInitialLocator(positions),
            positions = positions,
        )
    }

    /**
     * Cross-format resume — BOTH formats are authoritative, the furthest wins.
     * Open the ebook and it lands at whichever is further ahead: your exact last-read
     * page (restored precisely) OR, if you've listened past that, the audiobook's spot.
     * Reading mirrors forward to the audiobook too, so switching either way resumes at
     * the latest position. Audio→ebook is proportional (exact once a sync map is loaded);
     * ebook→ebook is always the exact Readium locator.
     */
    private suspend fun resolveInitialLocator(positions: List<Locator>): Locator? {
        val saved = ebookProgressWriter.lastPosition(serverId, libraryItemId)
        val ebookLocator = saved?.location?.let { parseSavedLocation(it) }
        val savedFraction = saved?.progress ?: 0.0
        val audioFraction = audioFractionForWork() ?: 0.0
        // A genuine READ position (not the audio→ebook mirror or a playback write).
        val genuineRead = saved?.source != "LOCAL_XFORMAT" && saved?.source != "LOCAL_PLAYBACK"
        // DONE: once the book is finished (or into the end credits), cross-format sync is
        // no longer enforced — "the sync is no longer important." So a finished book never
        // traps you at 100%: re-reading is free.
        val bookDone = saved?.isFinished == true ||
            savedFraction >= BOOK_DONE_THRESHOLD || audioFraction >= BOOK_DONE_THRESHOLD
        return when {
            bookDone -> {
                // Restore only a real, in-progress re-read; otherwise start free at the
                // beginning so a completed book doesn't reopen at the credits.
                val readFrac = ebookLocator?.locations?.totalProgression ?: 1.0
                if (genuineRead && readFrac < BOOK_DONE_THRESHOLD) ebookLocator else null
            }
            // Your CURRENT ebook page is authoritative — freely movable, never snapped
            // forward to a "furthest reached". Restore exactly where you last were.
            ebookLocator != null -> ebookLocator
            // Never opened the ebook but the audiobook has progress → start where you listened.
            audioFraction > 0.0 && positions.isNotEmpty() -> {
                val index = (audioFraction * (positions.size - 1)).roundToInt().coerceIn(0, positions.size - 1)
                positions[index]
            }
            else -> ebookLocator
        }
    }

    /** The work's furthest audiobook progress fraction (0..1), or null if none. */
    private suspend fun audioFractionForWork(): Double? {
        val workId = catalogRepository.workIdForItem(serverId, libraryItemId) ?: return null
        val editions = catalogRepository.editionsForWork(workId).first()
        return editions.filter { it.format == Format.AUDIO }.maxOfOrNull { it.fraction }?.takeIf { it > 0.0 }
    }

    /**
     * Parse a saved ebook position. Our own reader writes Readium locator JSON (exact
     * restore); an ABS `epubcfi` (read in the Audiobookshelf app, synced back) resolves
     * to its chapter so we resume roughly there instead of at the beginning.
     */
    private fun parseSavedLocation(saved: String): Locator? {
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
