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
}
