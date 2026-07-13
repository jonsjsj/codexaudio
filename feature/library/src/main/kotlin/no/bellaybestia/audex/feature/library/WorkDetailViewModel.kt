package no.bellaybestia.audex.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import no.bellaybestia.audex.domain.download.DownloadFormat
import no.bellaybestia.audex.domain.download.DownloadInfo
import no.bellaybestia.audex.domain.download.Downloads
import no.bellaybestia.audex.domain.model.Edition
import no.bellaybestia.audex.domain.model.Format
import no.bellaybestia.audex.domain.playback.PlaybackController
import no.bellaybestia.audex.domain.playback.PlaybackState
import no.bellaybestia.audex.domain.repository.CatalogRepository
import javax.inject.Inject

@HiltViewModel
class WorkDetailViewModel @Inject constructor(
    catalogRepository: CatalogRepository,
    private val playbackController: PlaybackController,
    private val downloads: Downloads,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val workId: String = checkNotNull(savedStateHandle["id"]) {
        "WorkDetailViewModel requires an \"id\" nav argument"
    }
    val title: String = savedStateHandle.get<String>("title")?.takeIf { it.isNotBlank() } ?: "Work"
    val author: String? = savedStateHandle.get<String>("author")?.takeIf { it.isNotBlank() }

    val editions: StateFlow<List<Edition>> = catalogRepository.editionsForWork(workId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val playback: StateFlow<PlaybackState> = playbackController.state

    val downloadStates: StateFlow<List<DownloadInfo>> = downloads.all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
