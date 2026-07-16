package no.bellaybestia.audex.domain.repository

import kotlinx.coroutines.flow.Flow
import no.bellaybestia.audex.domain.model.Author
import no.bellaybestia.audex.domain.model.Edition
import no.bellaybestia.audex.domain.model.Series
import no.bellaybestia.audex.domain.model.Work

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
     * The work's description, fetched live from the server (ABS only returns it
     * on the expanded item endpoint, so it isn't in the synced graph). Null
     * offline or when the book has none. HTML is stripped to plain text.
     */
    suspend fun description(serverId: String, libraryItemId: String): String?

    /** Full deterministic recompute from remote items + overrides. */
    suspend fun rebuildGraph()
}
