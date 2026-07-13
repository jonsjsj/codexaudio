package no.bellaybestia.audex.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import no.bellaybestia.audex.database.ServerDao
import no.bellaybestia.audex.database.ServerEntity
import no.bellaybestia.audex.domain.model.ServerAccount
import no.bellaybestia.audex.domain.repository.ServerRepository
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
        val serverId = deriveServerId(normalized)
        serverDao.upsert(ServerEntity(serverId = serverId, name = name.trim(), baseUrl = normalized))
        return ServerAccount(serverId, name.trim(), normalized)
    }

    override suspend fun removeServer(serverId: String) {
        serverDao.delete(serverId)
    }
}
