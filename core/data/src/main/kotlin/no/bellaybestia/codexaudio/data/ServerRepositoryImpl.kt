package no.bellaybestia.codexaudio.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import no.bellaybestia.codexaudio.database.ServerDao
import no.bellaybestia.codexaudio.database.ServerEntity
import no.bellaybestia.codexaudio.domain.model.ServerAccount
import no.bellaybestia.codexaudio.domain.repository.ServerRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerRepositoryImpl @Inject constructor(
    private val serverDao: ServerDao,
) : ServerRepository {

    override fun servers(): Flow<List<ServerAccount>> =
        serverDao.observeAll().map { rows ->
            rows.map { ServerAccount(it.serverId, it.name, it.baseUrl, it.enabled, it.needsLogin) }
        }

    override suspend fun addServer(name: String, baseUrl: String): ServerAccount {
        val normalized = baseUrl.trim().trimEnd('/')
        // Deterministic id from the base URL so re-adding a server keeps its
        // editions, progress rows and downloads attached.
        val serverId = UUID.nameUUIDFromBytes(normalized.lowercase().toByteArray()).toString()
        serverDao.upsert(ServerEntity(serverId = serverId, name = name.trim(), baseUrl = normalized))
        return ServerAccount(serverId, name.trim(), normalized)
    }

    override suspend fun removeServer(serverId: String) {
        serverDao.delete(serverId)
    }
}
