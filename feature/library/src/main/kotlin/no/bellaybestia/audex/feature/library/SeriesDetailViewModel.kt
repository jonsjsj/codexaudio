package no.bellaybestia.audex.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import no.bellaybestia.audex.domain.model.Work
import no.bellaybestia.audex.domain.repository.CatalogRepository

@HiltViewModel
class SeriesDetailViewModel @Inject constructor(
    catalogRepository: CatalogRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** Nav argument from the "series/{id}?name={name}" route. */
    private val seriesId: String = checkNotNull(savedStateHandle["id"]) {
        "SeriesDetailViewModel requires an \"id\" nav argument"
    }

    val seriesName: String? = savedStateHandle["name"]

    val works: StateFlow<List<Work>> = catalogRepository.worksForSeries(seriesId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
