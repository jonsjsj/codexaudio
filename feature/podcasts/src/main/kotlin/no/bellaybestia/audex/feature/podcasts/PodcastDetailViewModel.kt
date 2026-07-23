package no.bellaybestia.audex.feature.podcasts

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
import no.bellaybestia.audex.domain.model.Episode
import no.bellaybestia.audex.domain.model.Podcast
import no.bellaybestia.audex.domain.playback.PlaybackController
import no.bellaybestia.audex.domain.repository.PodcastRepository
import javax.inject.Inject

@HiltViewModel
class PodcastDetailViewModel @Inject constructor(
    private val repository: PodcastRepository,
    private val playback: PlaybackController,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val serverId: String = savedStateHandle.get<String>("serverId").orEmpty()
    private val itemId: String = savedStateHandle.get<String>("itemId").orEmpty()

    val podcast: StateFlow<Podcast?> = repository.podcast(serverId, itemId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val episodes: StateFlow<List<Episode>> = repository.episodes(serverId, itemId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            runCatching { repository.refreshPodcast(serverId, itemId) }
            _refreshing.value = false
        }
    }

    fun playEpisode(episode: Episode) {
        viewModelScope.launch {
            playback.play(
                serverId = serverId,
                libraryItemId = itemId,
                title = episode.title,
                author = podcast.value?.author,
                resumeAtS = episode.currentTimeS?.takeIf { it > 0.0 },
                episodeId = episode.episodeId,
            )
        }
    }

    fun setAutoDownload(enabled: Boolean) {
        viewModelScope.launch {
            repository.setAutoDownload(serverId, itemId, enabled)
                .onFailure { _message.value = it.message ?: "Couldn't update auto-download." }
        }
    }

    fun checkNewEpisodes() {
        viewModelScope.launch {
            _refreshing.value = true
            repository.checkNewEpisodes(serverId, itemId)
            _refreshing.value = false
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}
