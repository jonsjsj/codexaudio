package no.bellaybestia.codexaudio.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import no.bellaybestia.codexaudio.database.ServerDao
import no.bellaybestia.codexaudio.database.SessionDao
import no.bellaybestia.codexaudio.network.abs.AbsClientFactory
import no.bellaybestia.codexaudio.network.abs.AbsDeviceInfo
import no.bellaybestia.codexaudio.network.abs.AbsLocalSession
import no.bellaybestia.codexaudio.network.abs.AbsLocalSessionsBody
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

    /** @return true when nothing is left pending (worker success). */
    suspend fun flush(): Boolean {
        var allDrained = true
        for (server in serverDao.enabled()) {
            val pending = sessionDao.pendingForServer(server.serverId)
            if (pending.isEmpty()) continue

            val api = clientFactory.api(server.serverId, server.baseUrl)
            val body = AbsLocalSessionsBody(
                sessions = pending.map { row ->
                    AbsLocalSession(
                        id = row.localId,
                        libraryItemId = row.libraryItemId,
                        deviceInfo = runCatching {
                            json.decodeFromString<AbsDeviceInfo>(row.deviceInfoJson)
                        }.getOrDefault(AbsDeviceInfo()),
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
