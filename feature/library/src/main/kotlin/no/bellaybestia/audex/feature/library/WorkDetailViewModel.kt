package no.bellaybestia.audex.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import no.bellaybestia.audex.domain.download.DownloadFormat
import no.bellaybestia.audex.domain.download.DownloadInfo
import no.bellaybestia.audex.domain.download.Downloads
import no.bellaybestia.audex.domain.model.Author
import no.bellaybestia.audex.domain.model.Edition
import no.bellaybestia.audex.domain.model.Format
import no.bellaybestia.audex.domain.model.Series
import no.bellaybestia.audex.domain.model.Work
import no.bellaybestia.audex.domain.playback.Bookmark
import no.bellaybestia.audex.domain.playback.BookmarksRepository
import no.bellaybestia.audex.domain.playback.PlaybackController
import no.bellaybestia.audex.domain.playback.PlaybackState
import no.bellaybestia.audex.domain.reader.AlignmentRepository
import no.bellaybestia.audex.domain.reader.WordSyncProgress
import no.bellaybestia.audex.domain.reader.WordSyncStatus
import no.bellaybestia.audex.domain.repository.BookExtras
import no.bellaybestia.audex.domain.repository.CatalogRepository
import no.bellaybestia.audex.domain.settings.PlaybackSettings
import javax.inject.Inject

/**
 * The offer behind "Continue in other format": [edition] is the one to switch
 * TO, at [fraction] (the position you're further along at). [toAudio] says which
 * way — true means start the audiobook, false means open the reader.
 */
data class OtherFormat(
    val toAudio: Boolean,
    val fraction: Double,
    val edition: Edition,
)

@HiltViewModel
class WorkDetailViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val playbackController: PlaybackController,
    private val bookmarksRepository: BookmarksRepository,
    private val downloads: Downloads,
    private val alignmentRepository: AlignmentRepository,
    private val playbackSettings: PlaybackSettings,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** Playback settings surfaced in the detail overflow menu. */
    val skipSeconds: StateFlow<Int> = playbackSettings.skipSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 30)
    val mergeProgress: StateFlow<Boolean> = playbackSettings.mergeProgress
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setSkipSeconds(seconds: Int) {
        viewModelScope.launch { playbackSettings.setSkipSeconds(seconds) }
    }

    fun setMergeProgress(merged: Boolean) {
        viewModelScope.launch { playbackSettings.setMergeProgress(merged) }
    }

    private val workId: String = checkNotNull(savedStateHandle["id"]) {
        "WorkDetailViewModel requires an \"id\" nav argument"
    }
    val title: String = savedStateHandle.get<String>("title")?.takeIf { it.isNotBlank() } ?: "Work"
    val author: String? = savedStateHandle.get<String>("author")?.takeIf { it.isNotBlank() }

    val editions: StateFlow<List<Edition>> = catalogRepository.editionsForWork(workId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The canonical work row — series/position/year for the hero header. */
    val work: StateFlow<Work?> = catalogRepository.work(workId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The next book in this work's series (by position), for continue-series
     * navigation. Null when the work isn't in a series or is the last book. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val nextInSeries: StateFlow<Work?> = work.flatMapLatest { w ->
        val seriesId = w?.seriesId
        if (seriesId == null) {
            flowOf(null)
        } else {
            catalogRepository.worksForSeries(seriesId).map { siblings ->
                val ordered = siblings.sortedBy { it.seriesPosition ?: Double.MAX_VALUE }
                val idx = ordered.indexOfFirst { it.id == w.id }
                ordered.getOrNull(idx + 1).takeIf { idx >= 0 }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** All authors/series in the catalog — the merge-target pickers for the
     * "Fix author / series" action (docs/07, in-app metadata matching). */
    val allAuthors: StateFlow<List<Author>> = catalogRepository.authors()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val allSeries: StateFlow<List<Series>> = catalogRepository.series()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Fold this work's author into an existing one, then the graph rebuilds and
     * every surface follows. Source is the work's current author. */
    fun mergeAuthorInto(targetAuthorId: String) {
        val source = work.value?.authorId ?: return
        viewModelScope.launch { catalogRepository.mergeAuthorInto(source, targetAuthorId) }
    }

    /** Fold this work's series into an existing one. */
    fun mergeSeriesInto(targetSeriesId: String) {
        val source = work.value?.seriesId ?: return
        viewModelScope.launch { catalogRepository.mergeSeriesInto(source, targetSeriesId) }
    }

    /** Description/narrator/ASIN, fetched live once an edition is known (null offline). */
    private val _extras = MutableStateFlow<BookExtras?>(null)
    val extras: StateFlow<BookExtras?> = _extras.asStateFlow()
    val description: StateFlow<String?> = _extras
        .map { it?.description }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Furthest audio position ever reached (seconds), from ABS session history —
     * the durable bookmark that survives progress-field resets. Null offline
     * or when there's no audio edition / no sessions.
     */
    private val _furthestS = MutableStateFlow<Double?>(null)
    val furthestS: StateFlow<Double?> = _furthestS.asStateFlow()

    /** Server-synced bookmarks for the audio edition — shown as ticks on the
     * combined progress bar (redesign 4a). Empty when there's no audio edition. */
    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks.asStateFlow()

    val playback: StateFlow<PlaybackState> = playbackController.state

    val downloadStates: StateFlow<List<DownloadInfo>> = downloads.all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Word-sync (docs/10) status; UNAVAILABLE when no service URL or no dual-format. */
    private val _wordSync = MutableStateFlow(WordSyncStatus.UNAVAILABLE)
    val wordSync: StateFlow<WordSyncStatus> = _wordSync.asStateFlow()

    /** Live progress + ETA while an alignment build is running (drives the row's bar). */
    private val _wordSyncProgress = MutableStateFlow(WordSyncProgress(WordSyncStatus.UNAVAILABLE))
    val wordSyncProgress: StateFlow<WordSyncProgress> = _wordSyncProgress.asStateFlow()

    init {
        // Poll live alignment progress + ETA while a build is running.
        viewModelScope.launch {
            while (isActive) {
                val audio = editions.value.firstOrNull { it.format == Format.AUDIO }
                if (audio != null && _wordSync.value == WordSyncStatus.RUNNING) {
                    val p = alignmentRepository.progress(
                        audio.serverId, audio.libraryItemId, audio.durationS?.toDouble(),
                    )
                    _wordSyncProgress.value = p
                    if (p.status == WordSyncStatus.READY) _wordSync.value = WordSyncStatus.READY
                    delay(5_000)
                } else {
                    _wordSyncProgress.value = WordSyncProgress(_wordSync.value)
                    delay(3_000)
                }
            }
        }
        viewModelScope.launch {
            editions.collect { eds ->
                val audio = eds.firstOrNull { it.format == Format.AUDIO }
                val ebook = eds.firstOrNull { it.format == Format.EBOOK }
                _wordSync.value = when {
                    audio == null || ebook == null -> WordSyncStatus.UNAVAILABLE
                    // Both formats exist — surface the feature even before the
                    // service is configured, or nobody ever discovers it.
                    !alignmentRepository.isConfigured() -> WordSyncStatus.NOT_CONFIGURED
                    else -> alignmentRepository.status(audio.serverId, audio.libraryItemId)
                }
                // One live fetch is enough; ebook items usually carry the
                // richer metadata, so prefer that edition. Narrator, though, only
                // exists on the audiobook — fall back to it when the ebook has none.
                if (_extras.value == null) {
                    val source = ebook ?: audio ?: eds.firstOrNull()
                    if (source != null) {
                        val primary = catalogRepository.bookExtras(source.serverId, source.libraryItemId)
                        val narrator = primary?.narrator
                            ?: audio?.takeIf { it !== source }
                                ?.let { catalogRepository.bookExtras(it.serverId, it.libraryItemId)?.narrator }
                        _extras.value = (primary ?: BookExtras()).copy(narrator = narrator)
                    }
                }
                if (_furthestS.value == null && audio != null) {
                    _furthestS.value =
                        catalogRepository.furthestPositionS(audio.serverId, audio.libraryItemId)
                }
                if (audio != null) {
                    _bookmarks.value = runCatching {
                        bookmarksRepository.bookmarksFor(audio.serverId, audio.libraryItemId)
                    }.getOrDefault(emptyList())
                }
            }
        }
    }

    /** Play the audio edition at [positionS] (tapping a bookmark on the bar). */
    fun playAudioAt(positionS: Double) {
        val audio = editions.value.firstOrNull { it.format == Format.AUDIO } ?: return
        viewModelScope.launch {
            playbackController.play(audio.serverId, audio.libraryItemId, title, author, resumeAtS = positionS)
        }
    }

    /** Resume the audio edition at the furthest position ever reached. Passing
     * the target INTO play (not a follow-up seek) makes it actually stick —
     * a seek after play raced the initial resume and reverted to the saved time. */
    fun jumpToFurthest() {
        val audio = editions.value.firstOrNull { it.format == Format.AUDIO } ?: return
        val target = _furthestS.value ?: return
        viewModelScope.launch {
            playbackController.play(audio.serverId, audio.libraryItemId, title, author, resumeAtS = target)
        }
    }

    /** Queue the alignment job for this work (audio item + same-server ebook item). */
    fun requestWordSync() {
        val eds = editions.value
        val audio = eds.firstOrNull { it.format == Format.AUDIO } ?: return
        val ebook = eds.firstOrNull { it.format == Format.EBOOK } ?: return
        viewModelScope.launch {
            _wordSync.value = WordSyncStatus.RUNNING
            val ebookItem = ebook.libraryItemId
                .takeIf { ebook.serverId == audio.serverId && it != audio.libraryItemId }
            alignmentRepository
                .requestAlignment(audio.serverId, audio.libraryItemId, ebookItem)
                .onFailure { _wordSync.value = WordSyncStatus.NONE }
        }
    }

    fun play(edition: Edition) {
        viewModelScope.launch {
            // Automatic cross-format follow (Merge progress on): if you're further
            // along in the EBOOK, resume the audio at that reading spot instead of
            // the older audio spot — otherwise you'd re-listen ground you already
            // read and the merged % would sit still until the audio caught up.
            // (The ebook→audio direction, opening the reader at the audio spot, is
            // handled when you tap Read.)
            var resumeAt: Double? = null
            if (edition.format == Format.AUDIO && mergeProgress.value) {
                val ebook = editions.value.firstOrNull { it.format == Format.EBOOK }
                val dur = edition.durationS
                if (ebook != null && dur != null && dur > 0 &&
                    ebook.fraction > edition.fraction + 0.01
                ) {
                    resumeAt = ebook.fraction * dur
                }
            }
            playbackController.play(edition.serverId, edition.libraryItemId, title, author, resumeAtS = resumeAt)
        }
    }

    /**
     * "Continue in other format" (mockup 2c): the position you're furthest along
     * in, carried across formats. Only offered on dual-format books where the two
     * formats have actually drifted apart — otherwise it's a no-op button.
     *
     * Which way it goes is decided by where you're further: ahead in the audio →
     * open the ebook there; ahead in the ebook → start the audio there.
     */
    val otherFormat: StateFlow<OtherFormat?> = editions
        .map { eds ->
            val audio = eds.firstOrNull { it.format == Format.AUDIO } ?: return@map null
            val ebook = eds.firstOrNull { it.format == Format.EBOOK } ?: return@map null
            val a = audio.fraction
            val e = ebook.fraction
            // Under a page or two apart isn't worth switching for.
            if (kotlin.math.abs(a - e) < 0.01) return@map null
            if (a > e) OtherFormat(toAudio = false, fraction = a, edition = ebook)
            else OtherFormat(toAudio = true, fraction = e, edition = audio)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Start the audio edition at the ebook's position (the audio→ebook direction
     * is handled by the screen, which opens the reader). */
    fun continueInAudio(target: OtherFormat) {
        val duration = target.edition.durationS ?: return
        viewModelScope.launch {
            playbackController.play(
                target.edition.serverId,
                target.edition.libraryItemId,
                title,
                author,
                resumeAtS = target.fraction * duration,
            )
        }
    }

    /**
     * Discard this book's progress across all its editions (audio + ebook) —
     * resets the position on the server and locally, so the book returns to
     * "not started". The furthest-listened bookmark (ABS session history) is
     * unaffected, so a jump-back is still possible if it was a mistake.
     */
    fun discardProgress() {
        val eds = editions.value
        viewModelScope.launch {
            // If this book is the one loaded in the player (even paused), stop it
            // first — the live player would otherwise write its position straight
            // back after the wipe, resurrecting the progress.
            val now = playbackController.state.value
            if (eds.any { it.serverId == now.serverId && it.libraryItemId == now.libraryItemId }) {
                playbackController.stop()
                // stop() finalizes its session asynchronously (a PENDING row) —
                // give it a beat so the discard's purge below catches it too.
                kotlinx.coroutines.delay(400)
            }
            eds.forEach { catalogRepository.discardProgress(it.serverId, it.libraryItemId) }
        }
    }

    fun togglePlayPause() = playbackController.togglePlayPause()

    fun download(edition: Edition) =
        downloads.start(edition.serverId, edition.libraryItemId, edition.format.toDownloadFormat())

    fun removeDownload(edition: Edition) {
        viewModelScope.launch {
            downloads.remove(edition.serverId, edition.libraryItemId, edition.format.toDownloadFormat())
        }
    }

    private fun Format.toDownloadFormat(): DownloadFormat =
        if (this == Format.AUDIO) DownloadFormat.AUDIO else DownloadFormat.EBOOK
}
