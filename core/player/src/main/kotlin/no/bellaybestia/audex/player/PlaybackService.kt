package no.bellaybestia.audex.player

import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    @Inject lateinit var browseSource: MediaBrowseSource
    @Inject lateinit var playbackSettings: no.bellaybestia.audex.domain.settings.PlaybackSettings

    private var player: ExoPlayer? = null
    private var session: MediaLibrarySession? = null

    // Serves the async browse/resolve callbacks; cancelled in onDestroy.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        // DefaultDataSource routes by scheme: file:// (downloaded audio) goes to
        // FileDataSource, http(s) delegates to the authenticated OkHttp source.
        // Feeding local paths straight into OkHttpDataSource broke ALL offline
        // playback with "Malformed URL" (runtime-verified with network off).
        val httpFactory = OkHttpDataSource.Factory(httpClient)
        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)

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
            // Audiobook skip: back 10s / forward 30s. These drive seekBack()/
            // seekForward() everywhere — the lock screen, the notification, Android
            // Auto and headset controls — so "skip" means the same on every surface.
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
            .also { player = it }

        exo.addListener(sessionRecorder.playerListener(exo))
        // Put the 10s-back / 30s-forward skip buttons on the media notification and
        // lock screen. Media3's default layout shows prev/next (meaningless for a
        // single-track audiobook), so we pin explicit seek-back/seek-forward buttons.
        val rewind = CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)
            .setDisplayName("Back 10 seconds")
            .setPlayerCommand(Player.COMMAND_SEEK_BACK)
            .build()
        val forward = CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30)
            .setDisplayName("Forward 30 seconds")
            .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
            .build()
        session = MediaLibrarySession.Builder(this, exo, LibraryCallback())
            .setMediaButtonPreferences(ImmutableList.of(rewind, forward))
            .build()

        // Apply "skip silence" live whenever the preference changes.
        serviceScope.launch {
            playbackSettings.skipSilence.collect { enabled ->
                runCatching { withContext(Dispatchers.Main) { exo.skipSilenceEnabled = enabled } }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        sessionRecorder.finalizeActive()
        session?.release()
        player?.release()
        session = null
        player = null
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * Android Auto browse tree + play resolution. Everything here is additive:
     * phone playback sets fully-resolved, uri-bearing MediaItems, and both
     * item-setting callbacks pass those straight through unchanged — only the
     * id-only items an Auto/Automotive controller sends get resolved to real
     * tracks (offline-first) via [browseSource]. Progress on Auto-started
     * playback records through the SessionRecorder listener already attached to
     * the player, so there is a single progress path.
     */
    @OptIn(UnstableApi::class)
    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = MediaItem.Builder()
                .setMediaId("root")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("Audex")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .build(),
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            serviceScope.launch {
                val kids = runCatching {
                    if (parentId == "root") browseSource.rootChildren()
                    else browseSource.children(parentId)
                }.getOrDefault(emptyList())
                future.set(LibraryResult.ofItemList(ImmutableList.copyOf(kids), params))
            }
            return future
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val future = SettableFuture.create<LibraryResult<MediaItem>>()
            serviceScope.launch {
                val item = runCatching { browseSource.item(mediaId) }.getOrNull()
                future.set(
                    if (item != null) LibraryResult.ofItem(item, null)
                    else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE),
                )
            }
            return future
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            if (!needsResolve(mediaItems)) return Futures.immediateFuture(mediaItems)
            val future = SettableFuture.create<MutableList<MediaItem>>()
            serviceScope.launch {
                val res = runCatching { browseSource.resolvePlayable(mediaItems[0].mediaId) }.getOrNull()
                if (res == null) {
                    future.set(mediaItems)
                } else {
                    // Can't set a start position on this path — record from 0.
                    sessionRecorder.start(res.serverId, res.libraryItemId, 0.0)
                    future.set(res.items.toMutableList())
                }
            }
            return future
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            if (!needsResolve(mediaItems)) {
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs),
                )
            }
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            serviceScope.launch {
                val res = runCatching { browseSource.resolvePlayable(mediaItems[0].mediaId) }.getOrNull()
                if (res == null) {
                    future.set(MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs))
                } else {
                    sessionRecorder.start(res.serverId, res.libraryItemId, res.resumeOverallS)
                    future.set(MediaSession.MediaItemsWithStartPosition(res.items, res.startIndex, res.startPositionMs))
                }
            }
            return future
        }

        /**
         * True only for a single id-only item (no uri) — what an Auto controller
         * sends when the user picks a book from the browse tree. Phone playback
         * always sets uri-bearing items, so it never takes the resolve path.
         */
        private fun needsResolve(items: List<MediaItem>): Boolean =
            items.size == 1 && items[0].localConfiguration == null && items[0].mediaId.contains('|')
    }
}
