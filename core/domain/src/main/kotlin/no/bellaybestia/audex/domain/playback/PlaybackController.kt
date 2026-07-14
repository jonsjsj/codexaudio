package no.bellaybestia.audex.domain.playback

import kotlinx.coroutines.flow.StateFlow

/** Snapshot of the current playback for the mini-player / player UI. */
data class PlaybackState(
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val serverId: String? = null,
    val libraryItemId: String? = null,
    val title: String? = null,
    val author: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val error: String? = null,
) {
    val hasItem: Boolean get() = libraryItemId != null
}

/**
 * Controls audiobook playback through the Media3 service. Starting playback opens
 * an ABS playback session (`POST /api/items/{id}/play`), builds the ExoPlayer
 * playlist from its audioTracks, resumes at the server position, and drives the
 * online session lifecycle (`/session/{id}/sync` + `/close`) plus the local-first
 * SessionRecorder. Audio progress only ever flows through the sessions API.
 */
interface PlaybackController {
    val state: StateFlow<PlaybackState>

    /** Open a playback session for an audio edition and start playing. */
    suspend fun play(serverId: String, libraryItemId: String, title: String, author: String?)

    fun togglePlayPause()

    /** Seek to an overall position (ms from the start of the whole book). */
    fun seekTo(positionMs: Long)

    /** Jump back ~15s. */
    fun skipBackward()

    /** Jump forward ~30s. */
    fun skipForward()

    /** Set the playback speed (0.5–3.0×). */
    fun setSpeed(speed: Float)

    fun stop()
}
