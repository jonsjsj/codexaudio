package no.bellaybestia.codexaudio.player

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Media3 playback service. A MediaLibraryService (not a plain
 * MediaSessionService) from day one so the Android Auto browse tree in Phase 4
 * is an addition, not a migration (docs/04 §4.4).
 *
 * Phase-1 TODOs: playlist from the ABS play session's audioTracks (streaming)
 * or downloaded files, chapter seek session command, per-book speed, sleep
 * timer — see docs/07-build-plan.md.
 */
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {

    @Inject lateinit var sessionRecorder: SessionRecorder

    private var player: ExoPlayer? = null
    private var session: MediaLibrarySession? = null

    override fun onCreate() {
        super.onCreate()
        val exo = ExoPlayer.Builder(this).build().also { player = it }
        exo.addListener(sessionRecorder.playerListener(exo))
        session = MediaLibrarySession.Builder(this, exo, LibraryCallback()).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        sessionRecorder.finalizeActive()
        session?.release()
        player?.release()
        session = null
        player = null
        super.onDestroy()
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback
}
