package no.bellaybestia.codexaudio.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import no.bellaybestia.codexaudio.domain.model.Work
import no.bellaybestia.codexaudio.domain.repository.CatalogRepository

@HiltViewModel
class HomeViewModel @Inject constructor(
    catalogRepository: CatalogRepository,
) : ViewModel() {

    /** In-progress works: 0 < max(listen, read) < 0.99. */
    val continueWorks: StateFlow<List<Work>> = catalogRepository.works()
        .map { works ->
            works.filter { work ->
                val progress = maxOf(work.listenFraction, work.readFraction)
                progress > 0.0 && progress < 0.99
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
