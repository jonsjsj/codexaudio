package no.bellaybestia.audex.feature.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import no.bellaybestia.audex.domain.download.DownloadInfo
import no.bellaybestia.audex.domain.download.Downloads
import no.bellaybestia.audex.domain.repository.CatalogRepository
import no.bellaybestia.audex.domain.repository.ServerRepository
import javax.inject.Inject

/** A download row enriched with what's needed to OPEN the book (its work + author). */
data class DownloadDisplay(
    val info: DownloadInfo,
    val workId: String?,
    val author: String?,
)

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloads: Downloads,
    private val catalogRepository: CatalogRepository,
    serverRepository: ServerRepository,
) : ViewModel() {

    // Enrich each download with its work id + author so tapping a row can open
    // the book's detail (open-from-everywhere) instead of being view-only.
    val items: StateFlow<List<DownloadDisplay>> = downloads.all()
        .map { list ->
            list.map { info ->
                val workId = catalogRepository.workIdForItem(info.serverId, info.libraryItemId)
                val author = workId?.let { catalogRepository.work(it).first()?.authorName }
                DownloadDisplay(info, workId, author)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * "2.1 GB on device · Main + Backup" (mockup 2d): how much is actually saved,
     * and which servers it came from. Only completed downloads count toward the
     * size — an in-flight one hasn't taken its full space yet.
     */
    val summary: StateFlow<String?> = combine(
        downloads.all(),
        serverRepository.servers(),
    ) { list, servers ->
        val done = list.filter { it.isComplete }
        if (done.isEmpty()) return@combine null
        val bytes = done.sumOf { if (it.bytesTotal > 0) it.bytesTotal else it.bytesDone }
        val names = done.map { it.serverId }.distinct()
            .mapNotNull { id -> servers.firstOrNull { it.serverId == id }?.name }
            .distinct()
        listOfNotNull(
            "${formatBytes(bytes)} on device",
            names.takeIf { it.isNotEmpty() }?.joinToString(" + "),
        ).joinToString(" · ")
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun remove(info: DownloadInfo) {
        viewModelScope.launch { downloads.remove(info.serverId, info.libraryItemId, info.format) }
    }
}

/** Bytes → "2.1 GB" / "342 MB" — one decimal for GB, whole numbers below. */
internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format("%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "${bytes / 1_000_000} MB"
    bytes >= 1_000L -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}
