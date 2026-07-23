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
import androidx.datastore.preferences.core.intPreferencesKey
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
import no.bellaybestia.audex.database.ProgressEntity
import no.bellaybestia.audex.database.ServerDao
import no.bellaybestia.audex.domain.model.absCoverUrl
import no.bellaybestia.audex.domain.playback.Chapter
import no.bellaybestia.audex.domain.playback.PlaybackController
import no.bellaybestia.audex.domain.playback.PlaybackState
import no.bellaybestia.audex.domain.reader.AlignmentRepository
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
    private val alignmentRepository: AlignmentRepository,
    private val codexSync: no.bellaybestia.audex.domain.settings.CodexSync,
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

    // Smart-rewind bookkeeping: when playback last paused (any source).
    private var pausedAtMs: Long = 0

    // End-of-chapter sleep: the chapter index that was playing when armed.
    private var sleepChapterIndex: Int = -1

    override suspend fun play(
        serverId: String,
        libraryItemId: String,
        title: String,
        author: String?,
        resumeAtS: Double?,
        episodeId: String?,
    ) {
        _state.update {
            it.copy(isLoading = true, error = null, serverId = serverId, libraryItemId = libraryItemId, episodeId = episodeId, title = title, author = author)
        }
        val server = serverDao.enabled().firstOrNull { it.serverId == serverId }
        if (server == null) {
            _state.update { it.copy(isLoading = false, error = "Server not connected.") }
            return
        }
        _state.update { it.copy(coverUrl = absCoverUrl(server.baseUrl, libraryItemId)) }

        // Podcast episodes always stream (episode downloads aren't built yet).
        // Books are offline-first: play downloaded files if present, else stream.
        val local = if (episodeId == null) downloadManager.localAudioTracks(serverId, libraryItemId) else null
        if (local != null) {
            playLocal(serverId, libraryItemId, title, author, local, resumeAtS)
        } else {
            playStreaming(serverId, server.baseUrl, libraryItemId, title, author, resumeAtS, episodeId)
        }
    }

    private suspend fun playStreaming(
        serverId: String,
        baseUrl: String,
        libraryItemId: String,
        title: String,
        author: String?,
        resumeAtS: Double?,
        episodeId: String?,
    ) {
        val api = clientFactory.api(serverId, baseUrl)
        val session = runCatching {
            if (episodeId != null) api.playEpisode(libraryItemId, episodeId) else api.play(libraryItemId)
        }.getOrElse {
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
        // Local precise position is the source of truth. Prefer the
        // second-accurate LOCAL_PLAYBACK row over ABS's session.currentTime,
        // which lags the last local tick — so resuming (app relaunch or
        // stop/start) was snapping back to the last *synced* spot and losing
        // recent listening. An explicit resumeAtS (e.g. cross-format furthest)
        // still wins over both.
        // Episodes have no book progress row; resume from the server's session time.
        val localS = if (episodeId == null) progressDao.get(serverId, libraryItemId)?.currentTimeS?.takeIf { it > 0.0 } else null
        val resumeAt = resumeAtS ?: localS ?: session.currentTime
        activeApi = api
        activeSessionId = session.id
        activeOffsets = tracks.map { it.startOffset }
        activeChapters = withSynthesizedFallback(
            serverId,
            libraryItemId,
            session.chapters.map {
                Chapter(it.title, (it.start * 1000).toLong(), (it.end * 1000).toLong())
            },
        )
        totalDurationS = tracks.sumOf { it.duration }
        sessionRecorder.start(serverId, libraryItemId, resumeAt, episodeId)
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
        resumeAtS: Double?,
    ) {
        val items = tracks.map { track ->
            mediaItem(Uri.fromFile(track.file).toString(), serverId, libraryItemId, title, author)
        }
        val resumeAt = resumeAtS ?: progressDao.get(serverId, libraryItemId)?.currentTimeS ?: 0.0
        // No server session while offline — the local SessionRecorder row drains
        // later via /api/session/local-all.
        activeApi = null
        activeSessionId = null
        activeOffsets = tracks.map { it.startOffset }
        activeChapters = withSynthesizedFallback(
            serverId,
            libraryItemId,
            downloadManager.localAudioChapters(serverId, libraryItemId),
        )
        totalDurationS = tracks.sumOf { it.duration }
        sessionRecorder.start(serverId, libraryItemId, resumeAt)
        startPlayback(items, resumeAt)
        _state.update { it.copy(isLoading = false, chapters = activeChapters) }
        startTicker()
    }

    /**
     * Chapters where none exist: when the audio files carry no chapter markers
     * but the work has a word-sync map, project the EPUB's chapter boundaries
     * through the alignment onto the audio timeline. Cached maps make this work
     * offline too; any failure just means "no chapters", like before.
     */
    private suspend fun withSynthesizedFallback(
        serverId: String,
        libraryItemId: String,
        fromSource: List<Chapter>,
    ): List<Chapter> {
        if (fromSource.isNotEmpty()) return fromSource
        val map = runCatching { alignmentRepository.syncMap(serverId, libraryItemId) }
            .getOrNull() ?: return emptyList()
        val starts = map.synthesizedChapterStarts()
        if (starts.size < 2) return emptyList()
        val totalMs = (map.durationS * 1000).toLong()
        return starts.mapIndexed { index, startS ->
            Chapter(
                title = "Chapter ${index + 1}",
                startMs = (startS * 1000).toLong(),
                endMs = starts.getOrNull(index + 1)?.let { (it * 1000).toLong() } ?: totalMs,
            )
        }
    }

    private suspend fun startPlayback(items: List<MediaItem>, resumeAtS: Double) {
        val startIndex = activeOffsets.indexOfLast { it <= resumeAtS }.coerceAtLeast(0)
        val withinMs = ((resumeAtS - (activeOffsets.getOrNull(startIndex) ?: 0.0)).coerceAtLeast(0.0) * 1000).toLong()
        // The chosen speed is a listening preference, not per-book: restore it
        // on every start so it survives sessions and process restarts.
        // Per-book speed: this book's own remembered speed, else your usual
        // (last-chosen) speed, else the player default. So each book keeps its
        // pace and a new one starts at whatever you normally listen at.
        val prefs = context.appSettingsDataStore.data.first()
        val itemId = _state.value.libraryItemId
        val savedSpeed = (itemId?.let { prefs[speedKey(it)] }) ?: prefs[KEY_PLAYBACK_SPEED]
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
            if (c.isPlaying) {
                c.pause()
            } else {
                applySmartRewind(c)
                c.play()
            }
        }
    }

    /**
     * Smart rewind (the Audible/Smart-AudioBook-Player pattern): resuming after
     * a pause backs up a little so the listener catches the thread again —
     * scaled by how long they were away. Pause time is recorded in the player
     * listener so pauses from the notification/headset count too.
     */
    private fun applySmartRewind(c: MediaController) {
        val pausedAt = pausedAtMs.takeIf { it > 0 } ?: return
        pausedAtMs = 0
        val awayS = (System.currentTimeMillis() - pausedAt) / 1000
        val backMs = when {
            awayS < 10 -> return          // blink-length pause: stay put
            awayS < 60 -> 5_000L
            awayS < 600 -> 15_000L
            else -> 30_000L
        }
        c.seekTo((c.currentPosition - backMs).coerceAtLeast(0))
    }

    override fun seekTo(positionMs: Long) {
        scope.launch(main) { seekOverall(positionMs / 1000.0) }
    }

    override fun skipBackward() {
        scope.launch(main) {
            val s = currentSkipSeconds()
            seekOverall((overallPositionS() - s).coerceAtLeast(0.0))
        }
    }

    override fun skipForward() {
        scope.launch(main) {
            val s = currentSkipSeconds()
            seekOverall((overallPositionS() + s).coerceAtMost(totalDurationS))
        }
    }

    /** The user's chosen skip amount (10 or 30 s), same for back and forward. */
    private suspend fun currentSkipSeconds(): Int =
        context.appSettingsDataStore.data.first()[intPreferencesKey("playback_skip_seconds")] ?: 30

    override fun setSpeed(speed: Float) {
        scope.launch(main) {
            controller?.setPlaybackSpeed(speed)
            _state.update { it.copy(speed = speed) }
        }
        // Persist per-book AND as the new "usual" speed for the next book.
        scope.launch {
            val itemId = _state.value.libraryItemId
            context.appSettingsDataStore.edit { prefs ->
                prefs[KEY_PLAYBACK_SPEED] = speed
                if (itemId != null) prefs[speedKey(itemId)] = speed
            }
        }
    }

    private fun speedKey(libraryItemId: String) = floatPreferencesKey("speed_$libraryItemId")

    override fun setSleepAtChapterEnd(enabled: Boolean) {
        sleepJob?.cancel()
        sleepJob = null
        sleepChapterIndex = if (enabled) _state.value.currentChapterIndex else -1
        _state.update {
            it.copy(sleepAtChapterEnd = enabled, sleepTimerRemainingMs = null)
        }
    }

    override fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        sleepChapterIndex = -1
        if (minutes <= 0) {
            _state.update { it.copy(sleepTimerRemainingMs = null, sleepAtChapterEnd = false) }
            return
        }
        _state.update { it.copy(sleepAtChapterEnd = false) }
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
            if (!isPlaying) pausedAtMs = System.currentTimeMillis()
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
                // End-of-chapter sleep: the armed chapter finished → pause.
                if (sleepChapterIndex >= 0 && playing && chapterIdx != sleepChapterIndex) {
                    sleepChapterIndex = -1
                    withContext(main) { controller?.pause() }
                    _state.update { it.copy(sleepAtChapterEnd = false) }
                }
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

    /**
     * Mirror the live audio position into the local progress table (the source
     * the detail slider / Home / library read). Playback otherwise only wrote
     * server sessions, so those surfaces stayed frozen until the next reconcile.
     */
    private suspend fun writeLocalProgress(itemId: String?, positionS: Double, finished: Boolean) {
        val sid = _state.value.serverId ?: return
        val id = itemId ?: return
        if (totalDurationS <= 0) return
        val pct = if (finished) 1.0 else (positionS / totalDurationS).coerceIn(0.0, 1.0)
        runCatching {
            val existing = progressDao.get(sid, id)
            progressDao.upsertAll(
                listOf(
                    (existing ?: ProgressEntity(serverId = sid, libraryItemId = id)).copy(
                        pct = pct,
                        currentTimeS = positionS,
                        isFinished = finished,
                        lastUpdate = System.currentTimeMillis(),
                        source = "LOCAL_PLAYBACK",
                    ),
                ),
            )
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
                val itemId = _state.value.libraryItemId
                val finished = totalDurationS > 0 && position >= totalDurationS - 1.0
                // Books only: episodes have no book progress row, and Codex tracks
                // books, not podcast episodes. Episode position is carried by the
                // server session (synced above) + the local SessionRecorder row.
                if (_state.value.episodeId == null) {
                    // Keep the LOCAL progress row current so the detail slider, Home,
                    // and library reflect where you are AS you listen — it used to
                    // freeze at the last server reconcile (the "stuck at 90%" bug).
                    writeLocalProgress(itemId, position, finished)
                    // Optional: mirror progress to Codex immediately (best-effort; no
                    // effect unless the user configured Codex sync in Settings).
                    if (itemId != null) {
                        runCatching { codexSync.pushAudioProgress(itemId, position, finished) }
                    }
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
