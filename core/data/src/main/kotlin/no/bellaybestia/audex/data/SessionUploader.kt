package no.bellaybestia.audex.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import no.bellaybestia.audex.database.ServerDao
import no.bellaybestia.audex.database.SessionDao
import no.bellaybestia.audex.network.abs.AbsClientFactory
import no.bellaybestia.audex.network.abs.AbsDeviceInfo
import no.bellaybestia.audex.network.abs.AbsLocalSession
import no.bellaybestia.audex.network.abs.AbsLocalSessionsBody
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drains the pending local-session queue per server (docs/05 diagram b):
 * ok / already-known → SYNCED; 4xx → FAILED (never blind-retried, surfaced in
 * Settings ▸ Sync health); 5xx/network → stays PENDING for backoff retry.
 */
@Singleton
class SessionUploader @Inject constructor(
    private val serverDao: ServerDao,
    private val sessionDao: SessionDao,
    private val clientFactory: AbsClientFactory,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
        // ABS stores session `date` as YYYY-MM-DD and `dayOfWeek` as the weekday name.
        val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
        val DOW_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE", Locale.US)
    }

    /** @return true when nothing is left pending (worker success). */
    suspend fun flush(): Boolean {
        var allDrained = true
        for (server in serverDao.enabled()) {
            val pending = sessionDao.pendingForServer(server.serverId)
            if (pending.isEmpty()) continue

            val api = clientFactory.api(server.serverId, server.baseUrl)
            val body = AbsLocalSessionsBody(
                sessions = pending.map { row ->
                    val started = Instant.ofEpochMilli(row.startedAt).atZone(ZoneId.systemDefault())
                    AbsLocalSession(
                        id = row.localId,
                        libraryItemId = row.libraryItemId,
                        episodeId = row.episodeId,
                        deviceInfo = runCatching {
                            json.decodeFromString<AbsDeviceInfo>(row.deviceInfoJson)
                        }.getOrDefault(AbsDeviceInfo()),
                        // date/dayOfWeek bucket the session in ABS listening stats
                        // when the server creates it (never-synced sessions).
                        date = started.format(DATE_FMT),
                        dayOfWeek = started.format(DOW_FMT),
                        startTime = row.startTimeS,
                        currentTime = row.currentTimeS,
                        timeListening = row.timeListeningS,
                        startedAt = row.startedAt,
                        updatedAt = row.updatedAt,
                    )
                }
            )
            val ids = pending.map { it.localId }
            val result = runCatching { api.uploadLocalSessions(body) }
            val response = result.getOrNull()
            when {
                response != null && response.isSuccessful ->
                    sessionDao.setState(ids, "SYNCED")
                response != null && response.code() in 400..499 ->
                    sessionDao.setState(ids, "FAILED")
                else -> allDrained = false // network / 5xx: stays PENDING
            }
        }
        return allDrained
    }
}
