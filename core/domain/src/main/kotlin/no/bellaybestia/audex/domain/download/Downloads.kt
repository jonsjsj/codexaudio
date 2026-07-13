package no.bellaybestia.audex.domain.download

import kotlinx.coroutines.flow.Flow

enum class DownloadFormat { AUDIO, EBOOK }

/** State of one item's offline download (state mirrors the WorkManager job). */
data class DownloadInfo(
    val serverId: String,
    val libraryItemId: String,
    val format: DownloadFormat,
    val state: String, // QUEUED | RUNNING | DONE | FAILED
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val title: String? = null,
) {
    val isComplete: Boolean get() = state == "DONE"
    val isActive: Boolean get() = state == "QUEUED" || state == "RUNNING"
}

/** Offline download control surface for the UI (impl in :core:data). */
interface Downloads {
    fun all(): Flow<List<DownloadInfo>>
    fun start(serverId: String, libraryItemId: String, format: DownloadFormat)
    suspend fun remove(serverId: String, libraryItemId: String, format: DownloadFormat)

    /** Absolute path to the downloaded ebook file, or null if not downloaded. */
    suspend fun localEbookPath(serverId: String, libraryItemId: String): String?
}
