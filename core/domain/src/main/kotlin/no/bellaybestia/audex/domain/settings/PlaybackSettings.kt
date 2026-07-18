package no.bellaybestia.audex.domain.settings

import kotlinx.coroutines.flow.Flow

/**
 * Listening preferences applied to the player itself. [skipSilence] uses
 * ExoPlayer's silence skipping to shorten long gaps in narration — a staple
 * audiobook feature. The playback service observes this and toggles it live.
 */
interface PlaybackSettings {
    val skipSilence: Flow<Boolean>
    suspend fun setSkipSilence(enabled: Boolean)

    /**
     * How many seconds the skip-back / skip-forward controls jump — the SAME
     * amount both directions, switchable (10 or 30) in Settings. Drives the
     * in-app player, the mini player, and the lock-screen / notification /
     * Android Auto buttons.
     */
    val skipSeconds: Flow<Int>
    suspend fun setSkipSeconds(seconds: Int)
}
