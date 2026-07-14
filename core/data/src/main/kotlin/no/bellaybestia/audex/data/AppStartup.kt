package no.bellaybestia.audex.data

import no.bellaybestia.audex.auth.AbsTokenRefresher
import no.bellaybestia.audex.auth.ServerTokenStore
import no.bellaybestia.audex.database.ServerDao
import no.bellaybestia.audex.database.SessionDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot app-startup wiring, called from AudexApp:
 *  - hydrate the in-memory token cache so the OkHttp interceptor has tokens
 *    before the first request,
 *  - teach the token refresher how to resolve a serverId → base URL (it lives in
 *    :core:auth and can't see the database directly),
 *  - adopt listening sessions orphaned in RECORDING by a process death (a
 *    force-stopped service never runs finalizeActive), so they upload.
 */
@Singleton
class AppStartup @Inject constructor(
    private val tokenStore: ServerTokenStore,
    private val refresher: AbsTokenRefresher,
    private val serverDao: ServerDao,
    private val sessionDao: SessionDao,
    private val workScheduler: WorkScheduler,
) {
    suspend fun initialize() {
        tokenStore.load()
        refresher.baseUrlResolver = AbsTokenRefresher.BaseUrlResolver { serverId ->
            serverDao.enabled().firstOrNull { it.serverId == serverId }?.baseUrl
        }
        sessionDao.adoptOrphanedRecordings()
        workScheduler.uploadSessionsNow()
    }
}
