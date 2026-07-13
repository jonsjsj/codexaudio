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

    /** Full deterministic recompute from remote items + overrides. */
    suspend fun rebuildGraph()
}
