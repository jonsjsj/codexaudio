package no.bellaybestia.audex.feature.player

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import no.bellaybestia.audex.domain.playback.PlaybackController
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playbackController: PlaybackController,
) : ViewModel() {

    val state = playbackController.state

    fun togglePlayPause() = playbackController.togglePlayPause()
    fun skipBackward() = playbackController.skipBackward()
    fun skipForward() = playbackController.skipForward()
    fun seekTo(positionMs: Long) = playbackController.seekTo(positionMs)
    fun setSpeed(speed: Float) = playbackController.setSpeed(speed)
    fun setSleepTimer(minutes: Int) = playbackController.setSleepTimer(minutes)

    fun setSleepAtChapterEnd(enabled: Boolean) = playbackController.setSleepAtChapterEnd(enabled)
    fun seekToChapter(index: Int) = playbackController.seekToChapter(index)
    fun stop() = playbackController.stop()
}
