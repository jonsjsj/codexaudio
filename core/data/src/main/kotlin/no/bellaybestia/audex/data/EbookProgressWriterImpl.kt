package no.bellaybestia.audex.data

import no.bellaybestia.audex.database.EbookProgressQueueDao
import no.bellaybestia.audex.database.PendingEbookProgressEntity
import no.bellaybestia.audex.database.ProgressDao
import no.bellaybestia.audex.database.ProgressEntity
import no.bellaybestia.audex.domain.reader.EbookProgressWriter
import no.bellaybestia.audex.domain.reader.SavedEbookPosition
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EbookProgressWriterImpl @Inject constructor(
    private val queueDao: EbookProgressQueueDao,
    private val progressDao: ProgressDao,
    private val workScheduler: WorkScheduler,
) : EbookProgressWriter {

    override suspend fun record(
        serverId: String,
        libraryItemId: String,
        location: String,
        progress: Double,
        isFinished: Boolean,
    ) {
        val now = System.currentTimeMillis()
        // Queue the write for ABS, and update the local mirror immediately so the
        // UI reflects the new position offline.
        queueDao.upsert(
            PendingEbookProgressEntity(
                serverId = serverId,
                libraryItemId = libraryItemId,
                ebookLocation = location,
                ebookProgress = progress,
                updatedAt = now,
            )
        )
        progressDao.upsertAll(
            listOf(
                ProgressEntity(
                    serverId = serverId,
                    libraryItemId = libraryItemId,
                    pct = if (isFinished) 1.0 else progress,
                    ebookLocation = location,
                    ebookProgress = progress,
                    isFinished = isFinished,
                    lastUpdate = now,
                    source = "LOCAL_READER",
                )
            )
        )
        workScheduler.uploadEbookProgressNow()
    }

    override suspend fun lastPosition(serverId: String, libraryItemId: String): SavedEbookPosition? =
        progressDao.get(serverId, libraryItemId)?.let {
            SavedEbookPosition(location = it.ebookLocation, progress = it.ebookProgress)
        }
}
