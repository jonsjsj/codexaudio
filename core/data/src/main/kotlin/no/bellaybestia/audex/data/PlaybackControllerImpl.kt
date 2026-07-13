package no.bellaybestia.audex.data

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import no.bellaybestia.audex.common.DefaultDispatcher
import no.bellaybestia.audex.database.ServerDao
import no.bellaybestia.audex.domain.playback.PlaybackController
import no.bellaybestia.audex.domain.playback.PlaybackState
import no.bellaybestia.audex.network.abs.AbsApi
import no.bellaybestia.audex.network.abs.AbsAudioTrack
import no.bellaybestia.audex.network.abs.AbsSessionSyncBody
import no.bellaybestia.audex.player.SessionRecorder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val SYNC_INTERVAL_MS = 15_000L

@Singleton
class PlaybackControllerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val serverDao: ServerDao,
    private val clientFactory: no.bellaybestia.audex.network.abs.AbsClientFactory,
    private val sessionRecorder: SessionRecorder,
    @DefaultDispatcher private val ioDispatcher: CoroutineDispatcher,
) : PlaybackController {

    private val main = Dispatchers.Main.immediate
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var controller: MediaController? = null

    // Active ABS session bookkeeping (audio position accounting).
    private var activeApi: AbsApi? = null
    private var activeSessionId: String? = null
    private var activeTracks: List<AbsAudioTrack> = emptyList()
    private var totalDurationS: Double = 0.0
    private var syncJob: Job? = null
    private var tickJob: Job? = null

    override suspend fun play(serverId: String, libraryItemId: String, title: String, author: String?) {
        _state.update { it.copy(isLoading = true, error = null, serverId = serverId, libraryItemId = libraryItemId, title = title, author = author) }

        val server = serverDao.enabled().firstOrNull { it.serverId == serverId }
        if (server == null) {
            _state.update { it.copy(isLoading = false, error = "Server not connected.") }
            return
        }
        val api = clientFactory.api(serverId, server.baseUrl)
        val session = runCatching { api.play(libraryItemId) }.getOrElse {
            _state.update { it.copy(isLoading = false, error = "Couldn't start playback.") }
            return
        }
        val base = server.baseUrl.trimEnd('/')
        val tracks = session.audioTracks.sortedBy { it.index }
        if (tracks.isEmpty()) {
            _state.update { it.copy(isLoading = false, error = "This item has no audio tracks.") }
            return
        }
        val items = tracks.map { track ->
            MediaItem.Builder()
                .setUri(base + track.contentUrl)
                .setMediaId("$serverId|$libraryItemId")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(author)
                        .build()
                )
                .build()
        }

        // Resume: map the overall server position onto (trackIndex, offsetInTrack).
        val resumeAt = session.currentTime
        val startIndex = tracks.indexOfLast { it.startOffset <= resumeAt }.coerceAtLeast(0)
        val withinMs = ((resumeAt - tracks[startIndex].startOffset).coerceAtLeast(0.0) * 1000).toLong()

        activeApi = api
        activeSessionId = session.id
        activeTracks = tracks
        totalDurationS = tracks.sumOf { it.duration }
        sessionRecorder.start(serverId, libraryItemId, resumeAt)

        withContext(main) {
            val c = awaitController()
            c.addListener(playerListener)
            c.setMediaItems(items, startIndex, withinMs)
            c.prepare()
            c.play()
        }
        _state.update { it.copy(isLoading = false) }
        startSyncLoop()
        startTicker()
    }

    override fun togglePlayPause() {
        scope.launch(main) {
            val c = controller ?: return@launch
            if (c.isPlaying) c.pause() else c.play()
        }
    }

    override fun stop() {
        val api = activeApi
        val sessionId = activeSessionId
        syncJob?.cancel(); syncJob = null
        tickJob?.cancel(); tickJob = null
        scope.launch(main) {
            val position = overallPositionS()
            controller?.stop()
            controller?.clearMediaItems()
            if (api != null && sessionId != null) {
                runCatching {
                    api.closeSession(sessionId, AbsSessionSyncBody(position, 0.0, totalDurationS))
                }
            }
            sessionRecorder.finalizeActive()
            activeApi = null; activeSessionId = null; activeTracks = emptyList()
            _state.value = PlaybackState()
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
        }
    }

    /** Overall position across the whole book, in seconds. Must run on main. */
    private fun overallPositionS(): Double {
        val c = controller ?: return 0.0
        val idx = c.currentMediaItemIndex
        val offset = activeTracks.getOrNull(idx)?.startOffset ?: 0.0
        return offset + c.currentPosition / 1000.0
    }

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (true) {
                val (posMs, durMs, playing) = withContext(main) {
                    val c = controller
                    Triple(
                        (overallPositionS() * 1000).toLong(),
                        (totalDurationS * 1000).toLong(),
                        c?.isPlaying == true,
                    )
                }
                _state.update { it.copy(positionMs = posMs, durationMs = durMs, isPlaying = playing) }
                delay(1000)
            }
        }
    }

    private fun startSyncLoop() {
        syncJob?.cancel()
        syncJob = scope.launch {
            while (true) {
                delay(SYNC_INTERVAL_MS)
                val api = activeApi ?: break
                val sessionId = activeSessionId ?: break
                val (position, playing) = withContext(main) {
                    overallPositionS() to (controller?.isPlaying == true)
                }
                if (!playing) continue // don't accrue listening time while paused
                runCatching {
                    api.syncSession(
                        sessionId,
                        AbsSessionSyncBody(
                            currentTime = position,
                            timeListened = SYNC_INTERVAL_MS / 1000.0,
                            duration = totalDurationS,
                        ),
                    )
                }
            }
        }
    }

    private suspend fun awaitController(): MediaController {
        controller?.let { return it }
        val built = connect()
        controller = built
        return built
    }

    private suspend fun connect(): MediaController = suspendCancellableCoroutine { cont ->
        val token = SessionToken(context, ComponentName(context, no.bellaybestia.audex.player.PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { cont.resume(it) }
                    .onFailure { cont.resumeWithException(it) }
            },
            { r -> mainHandler.post(r) },
        )
    }
}
