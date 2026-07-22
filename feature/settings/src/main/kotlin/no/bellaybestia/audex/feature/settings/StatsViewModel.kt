package no.bellaybestia.audex.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import no.bellaybestia.audex.domain.settings.ActivityKind
import no.bellaybestia.audex.domain.settings.ActivityStats
import no.bellaybestia.audex.domain.settings.ActivityStatsRepository
import no.bellaybestia.audex.domain.settings.BookActivity

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val activityStats: ActivityStatsRepository,
) : ViewModel() {

    private val _stats = MutableStateFlow<ActivityStats?>(null)
    val stats: StateFlow<ActivityStats?> = _stats.asStateFlow()

    private val _listenBooks = MutableStateFlow<List<BookActivity>>(emptyList())
    val listenBooks: StateFlow<List<BookActivity>> = _listenBooks.asStateFlow()

    private val _readBooks = MutableStateFlow<List<BookActivity>>(emptyList())
    val readBooks: StateFlow<List<BookActivity>> = _readBooks.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    init { refresh() }

    fun refresh() {
        _loading.value = true
        viewModelScope.launch {
            _stats.value = activityStats.stats()
            _listenBooks.value = activityStats.breakdown(ActivityKind.LISTEN)
            _readBooks.value = activityStats.breakdown(ActivityKind.READ)
            _loading.value = false
        }
    }
}
