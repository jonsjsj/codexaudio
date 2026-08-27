package no.bellaybestia.audex.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import no.bellaybestia.audex.auth.AbsTokenRefresher
import no.bellaybestia.audex.auth.ServerTokenStore
import no.bellaybestia.audex.database.DownloadDao
import no.bellaybestia.audex.database.ServerDao
import no.bellaybestia.audex.database.SessionDao
import no.bellaybestia.audex.domain.repository.CatalogRepository
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
    @ApplicationContext private val context: Context,
    private val tokenStore: ServerTokenStore,
    private val refresher: AbsTokenRefresher,
    private val serverDao: ServerDao,
    private val sessionDao: SessionDao,
    private val downloadDao: DownloadDao,
    private val catalogRepository: CatalogRepository,
    // dagger.Lazy: AppStartup is field-injected into AudexApp BEFORE
    // workerFactory, and constructing WorkScheduler eagerly initializes
    // WorkManager, whose Configuration.Provider getter reads the still-lateinit
    // workerFactory → startup crash (runtime-verified). Deferring to
    // initialize() (post-onCreate) breaks the ordering hazard.
    private val workScheduler: Lazy<WorkScheduler>,
) {
    suspend fun initialize(appVersionCode: Int) {
        tokenStore.load()
        refresher.baseUrlResolver = AbsTokenRefresher.BaseUrlResolver { serverId ->
            serverDao.enabled().firstOrNull { it.serverId == serverId }?.baseUrl
        }
        rebuildGraphAfterUpdate(appVersionCode)
        sessionDao.adoptOrphanedRecordings()
        // Same idea for downloads: a process death mid-download leaves RUNNING
        // rows the UI can never act on. FAILED shows "Retry".
        downloadDao.adoptOrphanedActive()
        workScheduler.get().uploadSessionsNow()
        // Populate podcasts promptly on launch (the periodic worker's first run
        // can be deferred a while by WorkManager).
        workScheduler.get().syncPodcastsNow()
    }

    /**
     * Recompute the catalog graph once per app-version bump. When an update changes
     * how library items group into works (matching/series-recovery logic), the
     * already-synced graph is stale until the next FULL sync — which only fires on
     * login, an ABS library-change socket event, or the 6-hour periodic worker, NOT
     * on a plain relaunch. So a user who updates and reopens sees the OLD grouping and
     * reasonably concludes the fix "didn't work." Rebuilding here from the cached
     * `remote_items` is offline and near-instant, so grouping fixes apply on the very
     * first launch after updating. Idempotent and deterministic, so re-running is safe.
     */
    private suspend fun rebuildGraphAfterUpdate(appVersionCode: Int) {
        runCatching {
            val key = intPreferencesKey("graph_built_version")
            val built = context.appSettingsDataStore.data.first()[key]
            if (built != appVersionCode) {
                catalogRepository.rebuildGraph()
                context.appSettingsDataStore.edit { it[key] = appVersionCode }
            }
        }
    }
}
