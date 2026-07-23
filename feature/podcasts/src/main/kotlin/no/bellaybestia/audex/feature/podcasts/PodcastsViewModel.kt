package no.bellaybestia.audex.feature.podcasts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import no.bellaybestia.audex.domain.model.Podcast
import no.bellaybestia.audex.domain.repository.PodcastRepository
import javax.inject.Inject

@HiltViewModel
class PodcastsViewModel @Inject constructor(
    private val repository: PodcastRepository,
) : ViewModel() {

    val podcasts: StateFlow<List<Podcast>> = repository.podcasts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            runCatching { repository.refresh() }
            _refreshing.value = false
        }
    }
}
