package no.bellaybestia.audex.feature.podcasts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import no.bellaybestia.audex.domain.model.PodcastFeedPreview
import no.bellaybestia.audex.domain.model.PodcastLibraryTarget
import no.bellaybestia.audex.domain.model.PodcastSearchResult
import no.bellaybestia.audex.domain.repository.PodcastRepository
import javax.inject.Inject

/** In-flight state for the subscribe flow: search → preview → confirm. */
data class AddPodcastUiState(
    val query: String = "",
    val targets: List<PodcastLibraryTarget> = emptyList(),
    val targetsLoaded: Boolean = false,
    val selectedTargetIndex: Int = 0,
    val searching: Boolean = false,
    val results: List<PodcastSearchResult> = emptyList(),
    val confirm: ConfirmState? = null,
    val subscribing: Boolean = false,
    val done: Boolean = false,
    val error: String? = null,
) {
    val selectedTarget: PodcastLibraryTarget?
        get() = targets.getOrNull(selectedTargetIndex) ?: targets.firstOrNull()
}

/** The chosen feed being confirmed before subscribing. */
data class ConfirmState(
    val feedUrl: String,
    val loadingPreview: Boolean = false,
    val preview: PodcastFeedPreview? = null,
    val autoDownload: Boolean = true,
)

@HiltViewModel
class AddPodcastViewModel @Inject constructor(
    private val repository: PodcastRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AddPodcastUiState())
    val state: StateFlow<AddPodcastUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val targets = runCatching { repository.subscribeTargets() }.getOrDefault(emptyList())
            _state.update { it.copy(targets = targets, targetsLoaded = true) }
        }
    }

    fun setQuery(value: String) = _state.update { it.copy(query = value) }

    fun selectTarget(index: Int) = _state.update { it.copy(selectedTargetIndex = index) }

    private fun currentServerId(): String? = _state.value.selectedTarget?.serverId

    /** Search by name, or preview directly if the query is an RSS URL. */
    fun submit() {
        val q = _state.value.query.trim()
        if (q.isBlank()) return
        if (q.startsWith("http://", ignoreCase = true) || q.startsWith("https://", ignoreCase = true)) {
            previewFeed(q)
        } else {
            search(q)
        }
    }

    private fun search(term: String) {
        val serverId = currentServerId() ?: run {
            _state.update { it.copy(error = NO_LIBRARY) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(searching = true, error = null, results = emptyList()) }
            val results = repository.search(serverId, term)
            _state.update {
                it.copy(
                    searching = false,
                    results = results,
                    error = if (results.isEmpty()) "No podcasts found for \"$term\"." else null,
                )
            }
        }
    }

    fun choose(result: PodcastSearchResult) = previewFeed(result.feedUrl)

    private fun previewFeed(feedUrl: String) {
        val serverId = currentServerId() ?: run {
            _state.update { it.copy(error = NO_LIBRARY) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(confirm = ConfirmState(feedUrl = feedUrl, loadingPreview = true), error = null) }
            val preview = repository.previewFeed(serverId, feedUrl)
            if (preview == null) {
                _state.update { it.copy(confirm = null, error = "Couldn't read that feed.") }
            } else {
                _state.update { it.copy(confirm = it.confirm?.copy(loadingPreview = false, preview = preview)) }
            }
        }
    }

    fun setAutoDownload(enabled: Boolean) =
        _state.update { it.copy(confirm = it.confirm?.copy(autoDownload = enabled)) }

    fun cancelConfirm() = _state.update { it.copy(confirm = null, error = null) }

    fun subscribe() {
        val current = _state.value
        val confirm = current.confirm ?: return
        val preview = confirm.preview ?: return
        val target = current.selectedTarget ?: return
        if (!target.canSubscribe) {
            _state.update { it.copy(error = "Your account doesn't have permission to add podcasts on ${target.serverName}.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(subscribing = true, error = null) }
            repository.subscribe(
                target = target,
                feedUrl = confirm.feedUrl,
                title = preview.title,
                author = preview.author,
                description = preview.description,
                imageUrl = preview.imageUrl,
                autoDownload = confirm.autoDownload,
            ).onSuccess {
                _state.update { it.copy(subscribing = false, done = true) }
            }.onFailure { e ->
                _state.update { it.copy(subscribing = false, error = e.message ?: "Subscription failed.") }
            }
        }
    }

    private companion object {
        const val NO_LIBRARY =
            "No podcast library found on your connected servers. Create one in Audiobookshelf first."
    }
}
