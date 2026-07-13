package no.bellaybestia.audex.data

import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import no.bellaybestia.audex.auth.ServerTokenStore
import no.bellaybestia.audex.common.DefaultDispatcher
import no.bellaybestia.audex.database.ServerDao
import no.bellaybestia.audex.player.StreamTokenResolver
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maps a media URL's host → serverId (cached from the server registry) and
 * returns that server's current access token. The cache is refreshed reactively
 * so a rotated token or a newly added server is picked up without a rebuild.
 */
@Singleton
class StreamTokenResolverImpl @Inject constructor(
    serverDao: ServerDao,
    private val tokenStore: ServerTokenStore,
    @DefaultDispatcher dispatcher: CoroutineDispatcher,
) : StreamTokenResolver {

    @Volatile
    private var hostToServerId: Map<String, String> = emptyMap()

    init {
        serverDao.observeAll()
            .onEach { rows ->
                hostToServerId = rows.mapNotNull { row ->
                    Uri.parse(row.baseUrl).host?.let { it to row.serverId }
                }.toMap()
            }
            .launchIn(CoroutineScope(SupervisorJob() + dispatcher))
    }

    override fun bearerForUrl(url: String): String? {
        val host = runCatching { Uri.parse(url).host }.getOrNull() ?: return null
        val serverId = hostToServerId[host] ?: return null
        return tokenStore.accessToken(serverId)
    }
}
