package no.bellaybestia.audex.feature.library

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import no.bellaybestia.audex.domain.local.LocalLibrary
import no.bellaybestia.audex.domain.model.Author
import no.bellaybestia.audex.domain.model.Series
import no.bellaybestia.audex.domain.model.Work
import no.bellaybestia.audex.domain.repository.CatalogRepository

/** Sort orders for the All tab. Author = the canonical shelf order from the DAO. */
enum class WorkSort { AUTHOR, TITLE, RECENT }

/** Quick filters for the All tab (Codex's browse filters, Audex flavors). */
enum class WorkFilter { ALL, AUDIO, EBOOK, IN_PROGRESS }

@HiltViewModel
class LibraryViewModel @Inject constructor(
    catalogRepository: CatalogRepository,
    private val localLibrary: LocalLibrary,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** True while local files are being imported (drives a small progress hint). */
    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    /** Last import result: how many files were added (null until an import runs). */
    private val _lastImportCount = MutableStateFlow<Int?>(null)
    val lastImportCount: StateFlow<Int?> = _lastImportCount.asStateFlow()

    /** Add files picked from device storage. They're referenced in place and appear in
     *  the library once the graph rebuilds. */
    fun importLocal(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _importing.value = true
            val n = runCatching { localLibrary.import(uris.map { it.toString() }) }.getOrDefault(0)
            _lastImportCount.value = n
            _importing.value = false
        }
    }

    fun clearImportResult() { _lastImportCount.value = null }

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

    /** All-tab sort order, process-death safe (stored as ordinal). */
    val sort: StateFlow<Int> = savedStateHandle.getStateFlow(KEY_SORT, WorkSort.AUTHOR.ordinal)

    fun setSort(value: WorkSort) {
        savedStateHandle[KEY_SORT] = value.ordinal
    }

    /** All-tab quick filter, process-death safe (stored as ordinal). */
    val filter: StateFlow<Int> = savedStateHandle.getStateFlow(KEY_FILTER, WorkFilter.ALL.ordinal)

    fun setFilter(value: WorkFilter) {
        savedStateHandle[KEY_FILTER] = value.ordinal
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
        combine(catalogRepository.works(), query, sort, filter) { works, q, sortOrdinal, filterOrdinal ->
            val searched = if (q.isBlank()) works else works.filter { work ->
                work.title.contains(q, ignoreCase = true) ||
                    work.authorName?.contains(q, ignoreCase = true) == true ||
                    work.seriesName?.contains(q, ignoreCase = true) == true
            }
            val filtered = when (WorkFilter.entries.getOrElse(filterOrdinal) { WorkFilter.ALL }) {
                WorkFilter.ALL -> searched
                WorkFilter.AUDIO -> searched.filter { it.hasAudio }
                WorkFilter.EBOOK -> searched.filter { it.hasEbook }
                WorkFilter.IN_PROGRESS -> searched.filter {
                    (it.listenFraction > 0.0 && it.listenFraction < 0.99) ||
                        (it.readFraction > 0.0 && it.readFraction < 0.99)
                }
            }
            when (WorkSort.entries.getOrElse(sortOrdinal) { WorkSort.AUTHOR }) {
                // AUTHOR keeps the DAO's canonical shelf order (author → series → position).
                WorkSort.AUTHOR -> filtered
                WorkSort.TITLE -> filtered.sortedBy { it.title.lowercase() }
                WorkSort.RECENT -> filtered.sortedByDescending { it.updatedAt ?: 0L }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private companion object {
        const val KEY_SELECTED_TAB = "library_selected_tab"
        const val KEY_QUERY = "library_query"
        const val KEY_SORT = "library_sort"
        const val KEY_FILTER = "library_filter"
    }
}
