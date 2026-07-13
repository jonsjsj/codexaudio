package no.bellaybestia.audex.data

import no.bellaybestia.audex.database.EbookProgressQueueDao
import no.bellaybestia.audex.database.ServerDao
import no.bellaybestia.audex.network.abs.AbsClientFactory
import no.bellaybestia.audex.network.abs.AbsEbookProgressBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drains the pending ebook-position queue per server via
 * PATCH /api/me/progress/{id} (ebookLocation/ebookProgress). Success/4xx →
 * dequeue; 5xx/network → keep for retry. Mirrors the audio SessionUploader
 * pattern; this PATCH is legitimate for ebook positions only.
 */
@Singleton
class EbookProgressUploader @Inject constructor(
    private val serverDao: ServerDao,
    private val queueDao: EbookProgressQueueDao,
    private val clientFactory: AbsClientFactory,
) {
    /** @return true when the queue is fully drained (worker success). */
    suspend fun flush(): Boolean {
        var allDrained = true
        val pending = queueDao.pending()
        for ((serverId, rows) in pending.groupBy { it.serverId }) {
            val server = serverDao.enabled().firstOrNull { it.serverId == serverId } ?: continue
            val api = clientFactory.api(serverId, server.baseUrl)
            for (row in rows) {
                val response = runCatching {
                    api.patchEbookProgress(
                        row.libraryItemId,
                        AbsEbookProgressBody(ebookLocation = row.ebookLocation, ebookProgress = row.ebookProgress),
                    )
                }.getOrNull()
                when {
                    response != null && response.isSuccessful ->
                        queueDao.delete(serverId, row.libraryItemId)
                    response != null && response.code() in 400..499 ->
                        queueDao.delete(serverId, row.libraryItemId) // client error: don't retry forever
                    else -> allDrained = false // network / 5xx: keep for retry
                }
            }
        }
        return allDrained
    }
}
