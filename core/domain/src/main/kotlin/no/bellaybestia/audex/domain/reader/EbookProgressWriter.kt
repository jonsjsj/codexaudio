package no.bellaybestia.audex.domain.reader

/**
 * Records an ebook reading position. Writes go to a local queue and the mirror
 * immediately (offline-safe), then flush to ABS via `PATCH /api/me/progress/{id}`
 * (`ebookLocation`/`ebookProgress`) — the ONLY legitimate use of that PATCH.
 * Audio never touches it. Used by the Readium reader (Phase 2).
 */
/** Last known reading position from the local progress mirror. */
data class SavedEbookPosition(val location: String?, val progress: Double?)

interface EbookProgressWriter {
    suspend fun record(
        serverId: String,
        libraryItemId: String,
        location: String,
        progress: Double,
        isFinished: Boolean = false,
    )

    /** Read back the last position (server- or reader-written) to restore the reader. */
    suspend fun lastPosition(serverId: String, libraryItemId: String): SavedEbookPosition?
}
