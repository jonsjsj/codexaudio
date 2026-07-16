package no.bellaybestia.audex.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import no.bellaybestia.audex.domain.download.DownloadFormat
import no.bellaybestia.audex.domain.download.DownloadInfo
import no.bellaybestia.audex.domain.download.Downloads
import no.bellaybestia.audex.domain.model.Edition
import no.bellaybestia.audex.domain.model.Format
import no.bellaybestia.audex.domain.model.Work
import no.bellaybestia.audex.domain.playback.PlaybackController
import no.bellaybestia.audex.domain.playback.PlaybackState
import no.bellaybestia.audex.domain.reader.AlignmentRepository
import no.bellaybestia.audex.domain.reader.WordSyncStatus
import no.bellaybestia.audex.domain.repository.CatalogRepository
import javax.inject.Inject

@HiltViewModel
class WorkDetailViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val playbackController: PlaybackController,
    private val downloads: Downloads,
    private val alignmentRepository: AlignmentRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

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

    /** Full description, fetched live once an edition is known (null offline). */
    private val _description = MutableStateFlow<String?>(null)
    val description: StateFlow<String?> = _description.asStateFlow()

    /**
     * Furthest audio position ever reached (seconds), from ABS session history —
     * the durable bookmark that survives progress-field resets. Null offline
     * or when there's no audio edition / no sessions.
     */
    private val _furthestS = MutableStateFlow<Double?>(null)
    val furthestS: StateFlow<Double?> = _furthestS.asStateFlow()

    val playback: StateFlow<PlaybackState> = playbackController.state

    val downloadStates: StateFlow<List<DownloadInfo>> = downloads.all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Word-sync (docs/10) status; UNAVAILABLE when no service URL or no dual-format. */
    private val _wordSync = MutableStateFlow(WordSyncStatus.UNAVAILABLE)
    val wordSync: StateFlow<WordSyncStatus> = _wordSync.asStateFlow()

    init {
        viewModelScope.launch {
            editions.collect { eds ->
                val audio = eds.firstOrNull { it.format == Format.AUDIO }
                val ebook = eds.firstOrNull { it.format == Format.EBOOK }
                _wordSync.value = if (audio == null || ebook == null) {
                    WordSyncStatus.UNAVAILABLE
                } else {
                    alignmentRepository.status(audio.serverId, audio.libraryItemId)
                }
                // One live fetch is enough; ebook items usually carry the
                // richer metadata, so prefer that edition.
                if (_description.value == null) {
                    val source = ebook ?: audio ?: eds.firstOrNull()
                    if (source != null) {
                        _description.value =
                            catalogRepository.description(source.serverId, source.libraryItemId)
                    }
                }
                if (_furthestS.value == null && audio != null) {
                    _furthestS.value =
                        catalogRepository.furthestPositionS(audio.serverId, audio.libraryItemId)
                }
            }
        }
    }

    /** Resume the audio edition at the furthest position ever reached. */
    fun jumpToFurthest() {
        val audio = editions.value.firstOrNull { it.format == Format.AUDIO } ?: return
        val target = _furthestS.value ?: return
        viewModelScope.launch {
            playbackController.play(audio.serverId, audio.libraryItemId, title, author)
            playbackController.seekTo((target * 1000).toLong())
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
            playbackController.play(edition.serverId, edition.libraryItemId, title, author)
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
