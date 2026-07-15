package no.bellaybestia.audex.data

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import no.bellaybestia.audex.common.DefaultDispatcher
import no.bellaybestia.audex.database.ProgressDao
import no.bellaybestia.audex.database.ServerDao
import no.bellaybestia.audex.domain.model.absCoverUrl
import no.bellaybestia.audex.domain.playback.Chapter
import no.bellaybestia.audex.domain.playback.PlaybackController
import no.bellaybestia.audex.domain.playback.PlaybackState
import no.bellaybestia.audex.network.abs.AbsApi
import no.bellaybestia.audex.network.abs.AbsClientFactory
import no.bellaybestia.audex.network.abs.AbsSessionSyncBody
import no.bellaybestia.audex.player.SessionRecorder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val SYNC_INTERVAL_MS = 15_000L
private val KEY_PLAYBACK_SPEED = floatPreferencesKey("playback_speed")

@Singleton
class PlaybackControllerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val serverDao: ServerDao,
    private val clientFactory: AbsClientFactory,
    private val downloadManager: DownloadManager,
    private val progressDao: ProgressDao,
    private val sessionRecorder: SessionRecorder,
    @DefaultDispatcher private val ioDispatcher: CoroutineDispatcher,
) : PlaybackController {

    private val main = Dispatchers.Main.immediate
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var controller: MediaController? = null

    // Active session bookkeeping. `activeOffsets` maps a track index → its start
    // second, so overall position works the same for streamed and local playback.
    private var activeApi: AbsApi? = null
    private var activeSessionId: String? = null
    private var activeOffsets: List<Double> = emptyList()
    private var activeChapters: List<Chapter> = emptyList()
    private var totalDurationS: Double = 0.0
    private var syncJob: Job? = null
    private var tickJob: Job? = null
    private var sleepJob: Job? = null

    override suspend fun play(serverId: String, libraryItemId: String, title: String, author: String?) {
        _state.update {
            it.copy(isLoading = true, error = null, serverId = serverId, libraryItemId = libraryItemId, title = title, author = author)
        }
        val server = serverDao.enabled().firstOrNull { it.serverId == serverId }
        if (server == null) {
            _state.update { it.copy(isLoading = false, error = "Server not connected.") }
            return
        }
        _state.update { it.copy(coverUrl = absCoverUrl(server.baseUrl, libraryItemId)) }

        // Offline-first: play downloaded files if present, otherwise stream.
        val local = downloadManager.localAudioTracks(serverId, libraryItemId)
        if (local != null) {
            playLocal(serverId, libraryItemId, title, author, local)
        } else {
            playStreaming(serverId, server.baseUrl, libraryItemId, title, author)
        }
    }

    private suspend fun playStreaming(
        serverId: String,
        baseUrl: String,
        libraryItemId: String,
        title: String,
        author: String?,
    ) {
        val api = clientFactory.api(serverId, baseUrl)
        val session = runCatching { api.play(libraryItemId) }.getOrElse {
            _state.update { it.copy(isLoading = false, error = "Couldn't start playback.") }
            return
        }
        val base = baseUrl.trimEnd('/')
        val tracks = session.audioTracks.sortedBy { it.index }
        if (tracks.isEmpty()) {
            _state.update { it.copy(isLoading = false, error = "This item has no audio tracks.") }
            return
        }
        val items = tracks.map { track ->
            mediaItem(base + track.contentUrl, serverId, libraryItemId, title, author)
        }
        val resumeAt = session.currentTime
        activeApi = api
        activeSessionId = session.id
        activeOffsets = tracks.map { it.startOffset }
        activeChapters = session.chapters.map {
            Chapter(it.title, (it.start * 1000).toLong(), (it.end * 1000).toLong())
        }
        totalDurationS = tracks.sumOf { it.duration }
        sessionRecorder.start(serverId, libraryItemId, resumeAt)
        startPlayback(items, resumeAt)
        _state.update { it.copy(isLoading = false, chapters = activeChapters) }
        startSyncLoop()
        startTicker()
    }

    private suspend fun playLocal(
        serverId: String,
        libraryItemId: String,
        title: String,
        author: String?,
        tracks: List<LocalTrack>,
    ) {
        val items = tracks.map { track ->
            mediaItem(Uri.fromFile(track.file).toString(), serverId, libraryItemId, title, author)
        }
        val resumeAt = progressDao.get(serverId, libraryItemId)?.currentTimeS ?: 0.0
        // No server session while offline — the local SessionRecorder row drains
        // later via /api/session/local-all.
        activeApi = null
        activeSessionId = null
        activeOffsets = tracks.map { it.startOffset }
        activeChapters = downloadManager.localAudioChapters(serverId, libraryItemId)
        totalDurationS = tracks.sumOf { it.duration }
        sessionRecorder.start(serverId, libraryItemId, resumeAt)
        startPlayback(items, resumeAt)
        _state.update { it.copy(isLoading = false, chapters = activeChapters) }
        startTicker()
    }

    private suspend fun startPlayback(items: List<MediaItem>, resumeAtS: Double) {
        val startIndex = activeOffsets.indexOfLast { it <= resumeAtS }.coerceAtLeast(0)
        val withinMs = ((resumeAtS - (activeOffsets.getOrNull(startIndex) ?: 0.0)).coerceAtLeast(0.0) * 1000).toLong()
        // The chosen speed is a listening preference, not per-book: restore it
        // on every start so it survives sessions and process restarts.
        val savedSpeed = context.appSettingsDataStore.data.first()[KEY_PLAYBACK_SPEED]
        withContext(main) {
            val c = awaitController()
            c.addListener(playerListener)
            c.setMediaItems(items, startIndex, withinMs)
            c.prepare()
            c.play()
            savedSpeed?.let { c.setPlaybackSpeed(it) }
        }
        savedSpeed?.let { speed -> _state.update { it.copy(speed = speed) } }
    }

    private fun mediaItem(uri: String, serverId: String, libraryItemId: String, title: String, author: String?): MediaItem =
        MediaItem.Builder()
            .setUri(uri)
            .setMediaId("$serverId|$libraryItemId")
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(author).build())
            .build()

    override fun togglePlayPause() {
        scope.launch(main) {
            val c = controller ?: return@launch
            if (c.isPlaying) c.pause() else c.play()
        }
    }

    override fun seekTo(positionMs: Long) {
        scope.launch(main) { seekOverall(positionMs / 1000.0) }
    }

    override fun skipBackward() {
        scope.launch(main) { seekOverall((overallPositionS() - 15).coerceAtLeast(0.0)) }
    }

    override fun skipForward() {
        scope.launch(main) { seekOverall((overallPositionS() + 30).coerceAtMost(totalDurationS)) }
    }

    override fun setSpeed(speed: Float) {
        scope.launch(main) {
            controller?.setPlaybackSpeed(speed)
            _state.update { it.copy(speed = speed) }
        }
        // Persist so the chosen speed survives sessions and process restarts.
        scope.launch {
            context.appSettingsDataStore.edit { prefs ->
                prefs[KEY_PLAYBACK_SPEED] = speed
            }
        }
    }

    override fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        if (minutes <= 0) {
            _state.update { it.copy(sleepTimerRemainingMs = null) }
            return
        }
        val endAt = System.currentTimeMillis() + minutes * 60_000L
        sleepJob = scope.launch {
            while (true) {
                val remaining = endAt - System.currentTimeMillis()
                if (remaining <= 0) {
                    withContext(main) { controller?.pause() }
                    _state.update { it.copy(sleepTimerRemainingMs = null) }
                    break
                }
                _state.update { it.copy(sleepTimerRemainingMs = remaining) }
                delay(1000)
            }
        }
    }

    /** Seek to an overall second, mapping onto the right track. Runs on main. */
    private fun seekOverall(targetS: Double) {
        val c = controller ?: return
        val idx = activeOffsets.indexOfLast { it <= targetS }.coerceAtLeast(0)
        val withinMs = ((targetS - (activeOffsets.getOrNull(idx) ?: 0.0)).coerceAtLeast(0.0) * 1000).toLong()
        c.seekTo(idx, withinMs)
    }

    override fun seekToChapter(index: Int) {
        scope.launch(main) {
            activeChapters.getOrNull(index)?.let { seekOverall(it.startMs / 1000.0) }
        }
    }

    override fun stop() {
        val api = activeApi
        val sessionId = activeSessionId
        syncJob?.cancel(); syncJob = null
        tickJob?.cancel(); tickJob = null
        sleepJob?.cancel(); sleepJob = null
        scope.launch(main) {
            val position = overallPositionS()
            controller?.stop()
            controller?.clearMediaItems()
            if (api != null && sessionId != null) {
                runCatching { api.closeSession(sessionId, AbsSessionSyncBody(position, 0.0, totalDurationS)) }
            }
            sessionRecorder.finalizeActive()
            activeApi = null; activeSessionId = null; activeOffsets = emptyList()
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
        val offset = activeOffsets.getOrNull(c.currentMediaItemIndex) ?: 0.0
        return offset + c.currentPosition / 1000.0
    }

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (true) {
                val (posMs, playing) = withContext(main) {
                    (overallPositionS() * 1000).toLong() to (controller?.isPlaying == true)
                }
                val chapterIdx = activeChapters.indexOfLast { it.startMs <= posMs }
                _state.update {
                    it.copy(
                        positionMs = posMs,
                        durationMs = (totalDurationS * 1000).toLong(),
                        isPlaying = playing,
                        currentChapterIndex = chapterIdx,
                    )
                }
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
                if (!playing) continue
                runCatching {
                    api.syncSession(
                        sessionId,
                        AbsSessionSyncBody(position, SYNC_INTERVAL_MS / 1000.0, totalDurationS),
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
