package no.bellaybestia.audex.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import no.bellaybestia.audex.domain.model.Author
import no.bellaybestia.audex.domain.model.Series
import no.bellaybestia.audex.domain.model.Work
import no.bellaybestia.audex.domain.repository.CatalogRepository

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

    /** Search text, applied across all three tabs; process-death safe. */
    val query: StateFlow<String> = savedStateHandle.getStateFlow(KEY_QUERY, "")

    fun setQuery(value: String) {
        savedStateHandle[KEY_QUERY] = value
    }

    val authors: StateFlow<List<Author>> =
        combine(catalogRepository.authors(), query) { authors, q ->
            if (q.isBlank()) authors else authors.filter { it.name.contains(q, ignoreCase = true) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val series: StateFlow<List<Series>> =
        combine(catalogRepository.series(), query) { series, q ->
            if (q.isBlank()) series else series.filter { it.name.contains(q, ignoreCase = true) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val works: StateFlow<List<Work>> =
        combine(catalogRepository.works(), query) { works, q ->
            if (q.isBlank()) works else works.filter { work ->
                work.title.contains(q, ignoreCase = true) ||
                    work.authorName?.contains(q, ignoreCase = true) == true ||
                    work.seriesName?.contains(q, ignoreCase = true) == true
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private companion object {
        const val KEY_SELECTED_TAB = "library_selected_tab"
        const val KEY_QUERY = "library_query"
    }
}
