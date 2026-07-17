package no.bellaybestia.audex.domain.repository

import kotlinx.coroutines.flow.Flow
import no.bellaybestia.audex.domain.model.Author
import no.bellaybestia.audex.domain.model.Edition
import no.bellaybestia.audex.domain.model.Series
import no.bellaybestia.audex.domain.model.Work

/**
 * The bits of a book that only exist on ABS's expanded item endpoint — shown on
 * the detail page (narrator and ASIN come straight from the mockup's spec).
 */
data class BookExtras(
    val description: String? = null,
    val narrator: String? = null,
    val asin: String? = null,
)

/** Read side of the canonical graph — everything observable and offline-capable. */
interface CatalogRepository {
    fun authors(): Flow<List<Author>>
    fun series(): Flow<List<Series>>
    fun works(): Flow<List<Work>>
    fun worksForAuthor(authorId: String): Flow<List<Work>>
    fun worksForSeries(seriesId: String): Flow<List<Work>>
    fun editionsForWork(workId: String): Flow<List<Edition>>

    /** The work an item's edition belongs to — bridges audio↔ebook editions of one work. */
    suspend fun workIdForItem(serverId: String, libraryItemId: String): String?

    /** One work by id, observable (title/author/series/year/progress). */
    fun work(workId: String): Flow<Work?>

    /**
     * Detail-only metadata, fetched live from the server in ONE call — ABS
     * returns these solely on the expanded item endpoint, so they aren't in the
     * synced graph. Null offline. Description has its HTML stripped to plain text.
     */
    suspend fun bookExtras(serverId: String, libraryItemId: String): BookExtras?

    /**
     * The furthest position (seconds) ever reached in this audio item, from ABS
     * session history — durable even when the progress field gets reset. Null
     * offline or with no sessions.
     */
    suspend fun furthestPositionS(serverId: String, libraryItemId: String): Double?

    /**
     * Discard this item's progress: reset it to the start on the server
     * (PATCH /api/me/progress) and clear the local position so every surface
     * reflects it immediately. Best-effort against the server — the local reset
     * still applies offline (and re-syncs when the server is reachable).
     */
    suspend fun discardProgress(serverId: String, libraryItemId: String)

    /** Full deterministic recompute from remote items + overrides. */
    suspend fun rebuildGraph()
}
