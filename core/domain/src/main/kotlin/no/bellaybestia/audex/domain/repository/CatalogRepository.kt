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

    /**
     * The edition to jump into when "Resume" is pressed on Home: the one you used
     * most recently (by saved progress time), so Resume starts the audiobook or
     * opens the reader directly. Null if the work has no editions.
     */
    suspend fun resumeTarget(workId: String): no.bellaybestia.audex.domain.model.ResumeTarget?

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

    /**
     * Cross-format sync: set the AUDIO edition's saved position to [fraction]
     * (0..1) of its runtime, so that after reading the ebook, switching to the
     * audiobook resumes where you read. Proportional — good enough without a
     * word-sync map. Writes the local mirror (the detail slider + Resume + the
     * player's start position all read it); a real audio session on next play
     * carries it to the server.
     */
    suspend fun mirrorAudioProgress(
        serverId: String,
        libraryItemId: String,
        fraction: Double,
        durationS: Long?,
    )

    /**
     * Cross-format sync, the other way: set the EBOOK edition's saved fraction to
     * [fraction] (0..1) as you LISTEN, so the two editions' progress on the book page
     * stays matched and switching to reading resumes at the right place. Forward-only
     * (listening can't rewind your reading). Bumps only the fraction — the stored exact
     * page locator is left as-is; the reader resolves the precise spot on open.
     */
    suspend fun mirrorEbookProgress(
        serverId: String,
        ebookItemId: String,
        fraction: Double,
    )

    /**
     * Set the audio edition's saved position to [fraction] UNCONDITIONALLY (both
     * directions) — for an EXPLICIT jump (scrubber drag, bookmark tap). Unlike
     * [mirrorAudioProgress] this may move the audiobook BACKWARD, because a deliberate
     * jump is the new authoritative "here", overriding the forward-only listen mirror.
     */
    suspend fun setAudioFraction(
        serverId: String,
        libraryItemId: String,
        fraction: Double,
        durationS: Long?,
    )

    /**
     * Merge this work's author into an existing one (the "Fix author" picker):
     * records a durable AUTHOR_MERGE override on the two authors' immutable norm
     * keys and re-runs the graph build, so the correction survives every re-sync.
     * No-op when either id has no author row.
     */
    suspend fun mergeAuthorInto(sourceAuthorId: String, targetAuthorId: String)

    /** As [mergeAuthorInto], for a series (SERIES_MERGE override). */
    suspend fun mergeSeriesInto(sourceSeriesId: String, targetSeriesId: String)

    /** Full deterministic recompute from remote items + overrides. */
    suspend fun rebuildGraph()
}
