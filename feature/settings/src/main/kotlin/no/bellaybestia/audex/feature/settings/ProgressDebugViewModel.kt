package no.bellaybestia.audex.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import no.bellaybestia.audex.domain.model.ProgressDebugRow
import no.bellaybestia.audex.domain.repository.CatalogRepository

@HiltViewModel
class ProgressDebugViewModel @Inject constructor(
    catalogRepository: CatalogRepository,
) : ViewModel() {
    val rows: StateFlow<List<ProgressDebugRow>> = catalogRepository.debugProgressRows()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
