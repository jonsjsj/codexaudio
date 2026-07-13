package no.bellaybestia.audex.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import no.bellaybestia.audex.database.DownloadDao
import no.bellaybestia.audex.domain.download.DownloadFormat
import no.bellaybestia.audex.domain.download.DownloadInfo
import no.bellaybestia.audex.domain.download.Downloads
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadsImpl @Inject constructor(
    private val downloadDao: DownloadDao,
    private val downloadManager: DownloadManager,
    private val workScheduler: WorkScheduler,
) : Downloads {

    override fun all(): Flow<List<DownloadInfo>> =
        downloadDao.observeAllWithTitle().map { rows ->
            rows.mapNotNull { row ->
                val format = runCatching { DownloadFormat.valueOf(row.kind) }.getOrNull() ?: return@mapNotNull null
                DownloadInfo(row.serverId, row.libraryItemId, format, row.state, row.bytesDone, row.bytesTotal, row.title)
            }
        }

    override fun start(serverId: String, libraryItemId: String, format: DownloadFormat) {
        workScheduler.downloadNow(serverId, libraryItemId, DownloadKind.valueOf(format.name))
    }

    override suspend fun remove(serverId: String, libraryItemId: String, format: DownloadFormat) {
        downloadManager.delete(serverId, libraryItemId, DownloadKind.valueOf(format.name))
    }
}
