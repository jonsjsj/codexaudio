package no.bellaybestia.codexaudio.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import no.bellaybestia.codexaudio.domain.model.Author
import no.bellaybestia.codexaudio.domain.model.Series
import no.bellaybestia.codexaudio.domain.model.Work
import no.bellaybestia.codexaudio.domain.repository.CatalogRepository

@HiltViewModel
class LibraryViewModel @Inject constructor(
    catalogRepository: CatalogRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** Selected tab index (0 = Authors, 1 = Series, 2 = All), process-death safe. */
    val selectedTab: StateFlow<Int> = savedStateHandle.getStateFlow(KEY_SELECTED_TAB, 0)

    fun selectTab(index: Int) {
        savedStateHandle[KEY_SELECTED_TAB] = index
    }

    val authors: StateFlow<List<Author>> = catalogRepository.authors()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val series: StateFlow<List<Series>> = catalogRepository.series()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val works: StateFlow<List<Work>> = catalogRepository.works()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private companion object {
        const val KEY_SELECTED_TAB = "library_selected_tab"
    }
}
