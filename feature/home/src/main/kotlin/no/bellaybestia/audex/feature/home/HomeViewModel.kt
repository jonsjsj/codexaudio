package no.bellaybestia.audex.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import no.bellaybestia.audex.domain.model.Work
import no.bellaybestia.audex.domain.repository.CatalogRepository
import no.bellaybestia.audex.domain.repository.ServerRepository
import no.bellaybestia.audex.domain.settings.HomeLook
import no.bellaybestia.audex.domain.settings.ThemeSettings

@HiltViewModel
class HomeViewModel @Inject constructor(
    catalogRepository: CatalogRepository,
    serverRepository: ServerRepository,
    themeSettings: ThemeSettings,
) : ViewModel() {

    /** Which home layout to render (Settings → Appearance → Look). */
    val look: StateFlow<HomeLook> = themeSettings.prefs
        .map { it.look }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeLook.NIGHTFALL)

    /** Total book count, for the Stacks header. */
    val totalBooks: StateFlow<Int> = catalogRepository.works()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** "Synced · N servers" (mockup 2a) — how many servers are feeding the library. */
    val serverCount: StateFlow<Int> = serverRepository.servers()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * In-progress works (0 < max(listen, read) < 0.99), MOST RECENTLY LISTENED
     * FIRST — so the Home hero features the book you last had open, not an
     * arbitrary catalog-order pick. Works with no progress timestamp sort last.
     */
    val continueWorks: StateFlow<List<Work>> = catalogRepository.works()
        .map { works ->
            works.filter { work ->
                val progress = maxOf(work.listenFraction, work.readFraction)
                progress > 0.0 && progress < 0.99
            }.sortedByDescending { it.listenedAt ?: Long.MIN_VALUE }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Newest arrivals by remote updatedAt — fills Home before any progress exists. */
    val recentWorks: StateFlow<List<Work>> = catalogRepository.works()
        .map { works ->
            works.filter { it.updatedAt != null }
                .sortedByDescending { it.updatedAt }
                .take(15)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
