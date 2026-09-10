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
        absLocation: String?,
        progress: Double,
        isFinished: Boolean,
    ) {
        val now = System.currentTimeMillis()
        // The QUEUE (→ ABS) carries the ABS-compatible epubcfi (or blank = %-only, which
        // the uploader turns into a PATCH with no ebookLocation so ABS keeps its existing
        // page pointer). The MIRROR (→ our reader restore) keeps the exact Readium locator
        // JSON. Two formats, two stores — ABS + the official app work normally while our
        // reader stays exact.
        queueDao.upsert(
            PendingEbookProgressEntity(
                serverId = serverId,
                libraryItemId = libraryItemId,
                ebookLocation = absLocation.orEmpty(),
                ebookProgress = progress,
                updatedAt = now,
            )
        )
        // Merge onto the existing row (never build a bare replacement): the row is
        // shared with fields this call doesn't own — currentTimeS has no meaning for an
        // ebook edition's own row today, but the pattern of constructing a fresh entity
        // here previously discarded it by accident whenever something DID rely on it, and
        // silently reset isFinished back to false on every single page turn (this function
        // is never called with isFinished=true, so a book someone else had marked done
        // would un-finish itself the next time it was merely opened and read one page).
        val existing = progressDao.get(serverId, libraryItemId)
        progressDao.upsertAll(
            listOf(
                (existing ?: ProgressEntity(serverId = serverId, libraryItemId = libraryItemId)).copy(
                    pct = if (isFinished) 1.0 else progress,
                    ebookLocation = location,
                    ebookProgress = progress,
                    isFinished = isFinished || existing?.isFinished == true,
                    lastUpdate = now,
                    source = "LOCAL_READER",
                )
            )
        )
        workScheduler.uploadEbookProgressNow()
    }

    override suspend fun lastPosition(serverId: String, libraryItemId: String): SavedEbookPosition? =
        progressDao.get(serverId, libraryItemId)?.let {
            SavedEbookPosition(
                location = it.ebookLocation,
                progress = it.ebookProgress,
                source = it.source,
                isFinished = it.isFinished,
            )
        }
}
