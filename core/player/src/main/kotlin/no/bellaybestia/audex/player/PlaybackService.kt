package no.bellaybestia.audex.player

import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import dagger.hilt.android.AndroidEntryPoint
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * Media3 playback service. A MediaLibraryService (not a plain
 * MediaSessionService) from day one so the Android Auto browse tree in Phase 4
 * is an addition, not a migration (docs/04 §4.4).
 *
 * Streaming: the ExoPlayer uses an OkHttp data source whose interceptor attaches
 * the per-server Bearer token (resolved by URL host), because ABS audioTrack
 * `contentUrl`s are authenticated via the Authorization header, not `?token=`.
 */
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {

    @Inject lateinit var sessionRecorder: SessionRecorder
    @Inject lateinit var tokenResolver: StreamTokenResolver

    private var player: ExoPlayer? = null
    private var session: MediaLibrarySession? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        val httpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val token = tokenResolver.bearerForUrl(request.url.toString())
                val authed = if (token.isNullOrBlank()) request
                else request.newBuilder().header("Authorization", "Bearer $token").build()
                chain.proceed(authed)
            }
            .build()
        val dataSourceFactory = OkHttpDataSource.Factory(httpClient)

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also { player = it }

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
