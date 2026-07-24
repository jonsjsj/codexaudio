package no.bellaybestia.audex.player

import android.os.Build
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import no.bellaybestia.audex.database.PendingSessionEntity
import no.bellaybestia.audex.database.SessionDao
import no.bellaybestia.audex.domain.settings.ActivityKind
import no.bellaybestia.audex.domain.settings.ActivityRecorder
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local-first listening-session recorder — the offline correctness backbone
 * (docs/04 §4.4, docs/05 diagram b). Every playback, streamed or downloaded,
 * gets a locally recorded session row; online streaming additionally mirrors
 * to the server session endpoints. Rows finalize to PENDING and are drained by
 * SessionUploadWorker via POST /api/session/local-all. Audio progress NEVER
 * goes through PATCH /api/me/progress.
 */
@Singleton
class SessionRecorder @Inject constructor(
    private val sessionDao: SessionDao,
    private val activityRecorder: ActivityRecorder,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var active: PendingSessionEntity? = null

    fun start(serverId: String, libraryItemId: String, startTimeS: Double, episodeId: String? = null) {
        val now = System.currentTimeMillis()
        val row = PendingSessionEntity(
            localId = UUID.randomUUID().toString(),
            serverId = serverId,
            libraryItemId = libraryItemId,
            startedAt = now,
            updatedAt = now,
            startTimeS = startTimeS,
            currentTimeS = startTimeS,
            timeListeningS = 0.0,
            deviceInfoJson = deviceInfoJson(),
            state = "RECORDING",
            episodeId = episodeId,
        )
        active = row
        scope.launch { sessionDao.upsert(row) }
    }

    fun tick(currentTimeS: Double, listenedDeltaS: Double) {
        val row = active ?: return
        val updated = row.copy(
            currentTimeS = currentTimeS,
            timeListeningS = row.timeListeningS + listenedDeltaS,
            updatedAt = System.currentTimeMillis(),
        )
        active = updated
        scope.launch { sessionDao.upsert(updated) }
        // Feed the same listened delta into the durable activity ledger (stats).
        if (listenedDeltaS > 0.0) {
            scope.launch {
                activityRecorder.record(row.serverId, row.libraryItemId, ActivityKind.LISTEN, listenedDeltaS)
            }
        }
    }

    fun finalizeActive() {
        val row = active ?: return
        active = null
        scope.launch { sessionDao.upsert(row.copy(state = "PENDING", updatedAt = System.currentTimeMillis())) }
    }

    /**
     * Bridges ExoPlayer state changes into the recorder lifecycle. Listened TIME
     * is now accrued by PlaybackController's always-on ticker (so it counts
     * offline and survives a kill); this listener only SNAPS the saved position
     * on a pause or seek (delta 0.0) — accruing a delta here too would
     * double-count the listening time.
     */
    fun playerListener(player: Player): Player.Listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) tick(player.currentPosition / 1000.0, 0.0)
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            tick(player.currentPosition / 1000.0, 0.0)
        }
    }

    private fun deviceInfoJson(): String =
        """{"manufacturer":"${Build.MANUFACTURER}","model":"${Build.MODEL}",""" +
            """"sdkVersion":${Build.VERSION.SDK_INT},"clientName":"audex","clientVersion":"0.1.0"}"""
}
