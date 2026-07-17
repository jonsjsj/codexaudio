package no.bellaybestia.audex.domain.playback

import kotlinx.coroutines.flow.StateFlow

/** One audiobook chapter (times are ms from the start of the whole book). */
data class Chapter(val title: String, val startMs: Long, val endMs: Long)

/** Snapshot of the current playback for the mini-player / player UI. */
data class PlaybackState(
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val serverId: String? = null,
    val libraryItemId: String? = null,
    val title: String? = null,
    val author: String? = null,
    val coverUrl: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val sleepTimerRemainingMs: Long? = null,
    /** True while the "pause at the end of this chapter" sleep mode is armed. */
    val sleepAtChapterEnd: Boolean = false,
    val chapters: List<Chapter> = emptyList(),
    val currentChapterIndex: Int = -1,
    val error: String? = null,
) {
    val hasItem: Boolean get() = libraryItemId != null
    val currentChapter: Chapter? get() = chapters.getOrNull(currentChapterIndex)
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
    /**
     * Start (or resume) playback. [resumeAtS] overrides where to resume (overall
     * seconds) — used to resume at the furthest-listened position instead of
     * ABS's possibly-stale saved time. Null → resume at the server/local saved
     * position. Passing it INTO play (vs a follow-up seekTo) avoids a race where
     * the initial resume wins over the seek.
     */
    suspend fun play(
        serverId: String,
        libraryItemId: String,
        title: String,
        author: String?,
        resumeAtS: Double? = null,
    )

    fun togglePlayPause()

    /** Seek to an overall position (ms from the start of the whole book). */
    fun seekTo(positionMs: Long)

    /** Jump back ~15s. */
    fun skipBackward()

    /** Jump forward ~30s. */
    fun skipForward()

    /** Set the playback speed (0.5–3.0×). */
    fun setSpeed(speed: Float)

    /** Pause after [minutes] (0 cancels the timer). */
    fun setSleepTimer(minutes: Int)

    /** Arm/disarm "pause when the current chapter ends" (replaces any minute timer). */
    fun setSleepAtChapterEnd(enabled: Boolean)

    /** Jump to the start of chapter [index]. */
    fun seekToChapter(index: Int)

    fun stop()
}
