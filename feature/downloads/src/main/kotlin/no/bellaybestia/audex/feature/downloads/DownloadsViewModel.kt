package no.bellaybestia.audex.feature.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import no.bellaybestia.audex.domain.download.DownloadInfo
import no.bellaybestia.audex.domain.download.Downloads
import javax.inject.Inject

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloads: Downloads,
) : ViewModel() {

    val items: StateFlow<List<DownloadInfo>> = downloads.all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun remove(info: DownloadInfo) {
        viewModelScope.launch { downloads.remove(info.serverId, info.libraryItemId, info.format) }
    }
}
