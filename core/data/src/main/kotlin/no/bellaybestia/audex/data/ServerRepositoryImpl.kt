package no.bellaybestia.audex.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import no.bellaybestia.audex.auth.ServerTokenStore
import no.bellaybestia.audex.database.ProgressDao
import no.bellaybestia.audex.database.RemoteItemDao
import no.bellaybestia.audex.database.ServerDao
import no.bellaybestia.audex.database.ServerEntity
import no.bellaybestia.audex.domain.model.ServerAccount
import no.bellaybestia.audex.domain.repository.CatalogRepository
import no.bellaybestia.audex.domain.repository.ServerRepository
import no.bellaybestia.audex.network.abs.AbsClientFactory
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerRepositoryImpl @Inject constructor(
    private val serverDao: ServerDao,
    private val tokenStore: ServerTokenStore,
    private val clientFactory: AbsClientFactory,
    private val remoteItemDao: RemoteItemDao,
    private val progressDao: ProgressDao,
    private val catalogRepository: CatalogRepository,
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

    /**
     * Full teardown, not just the registry row: tokens wiped, the cached HTTP
     * stack (and its cookies) evicted, the server's items + progress removed,
     * and the catalog graph rebuilt so its works disappear from the library.
     * Downloaded files stay on disk until removed per-item from Downloads.
     */
    override suspend fun removeServer(serverId: String) {
        tokenStore.clear(serverId)
        clientFactory.evict(serverId)
        remoteItemDao.deleteForServer(serverId)
        progressDao.deleteForServer(serverId)
        serverDao.delete(serverId)
        catalogRepository.rebuildGraph()
    }
}
