package no.bellaybestia.audex.feature.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import no.bellaybestia.audex.domain.model.MediaDetail
import no.bellaybestia.audex.domain.settings.CodexSync

@HiltViewModel
class UpcomingDetailViewModel @Inject constructor(
    private val codexSync: CodexSync,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** Nav arguments from the "upcoming_detail/{mediaId}?title=…&cover=…&release=…" route. */
    private val mediaId: Int = checkNotNull(savedStateHandle["mediaId"]) {
        "UpcomingDetailViewModel requires a \"mediaId\" nav argument"
    }
    val initialTitle: String = savedStateHandle["title"] ?: ""
    val initialCoverUrl: String? = (savedStateHandle["cover"] as String?)?.takeIf { it.isNotBlank() }
    val initialReleaseDate: String? = (savedStateHandle["release"] as String?)?.takeIf { it.isNotBlank() }

    private val _detail = MutableStateFlow<MediaDetail?>(null)
    val detail: StateFlow<MediaDetail?> = _detail.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        viewModelScope.launch {
            _detail.value = codexSync.mediaDetail(mediaId, initialTitle)
            _isLoading.value = false
        }
    }
}
