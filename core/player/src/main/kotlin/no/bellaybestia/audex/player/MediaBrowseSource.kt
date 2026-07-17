package no.bellaybestia.audex.player

import androidx.media3.common.MediaItem

/** Tracks resolved for playback, plus where to resume and the ABS identity. */
data class PlayableResolution(
    val items: List<MediaItem>,
    /** Index of the track to start on, and the offset within it (ms). */
    val startIndex: Int,
    val startPositionMs: Long,
    /** Absolute position in the whole audiobook (s) — for the session recorder. */
    val resumeOverallS: Double,
    val serverId: String,
    val libraryItemId: String,
)

/**
 * Supplies the Android Auto / Automotive browse tree and resolves a chosen
 * item to real playable tracks. Implemented in :core:data (which has the
 * catalog, downloads, and ABS access); the PlaybackService injects it.
 *
 * Browse node ids: "root", "node:continue", "node:downloads"; a book uses the
 * same "serverId|libraryItemId" media id as phone playback.
 */
interface MediaBrowseSource {
    suspend fun rootChildren(): List<MediaItem>
    suspend fun children(parentId: String): List<MediaItem>
    suspend fun item(mediaId: String): MediaItem?

    /** Resolve an id-only book item (from Auto) to playable tracks; null if it can't. */
    suspend fun resolvePlayable(mediaId: String): PlayableResolution?
}
