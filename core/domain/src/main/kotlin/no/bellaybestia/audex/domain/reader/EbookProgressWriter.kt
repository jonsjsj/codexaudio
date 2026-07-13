package no.bellaybestia.audex.domain.reader

/**
 * Records an ebook reading position. Writes go to a local queue and the mirror
 * immediately (offline-safe), then flush to ABS via `PATCH /api/me/progress/{id}`
 * (`ebookLocation`/`ebookProgress`) — the ONLY legitimate use of that PATCH.
 * Audio never touches it. Used by the Readium reader (Phase 2).
 */
interface EbookProgressWriter {
    suspend fun record(
        serverId: String,
        libraryItemId: String,
        location: String,
        progress: Double,
        isFinished: Boolean = false,
    )
}
