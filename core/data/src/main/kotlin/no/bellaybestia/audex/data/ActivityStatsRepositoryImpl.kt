package no.bellaybestia.audex.data

import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import no.bellaybestia.audex.common.DefaultDispatcher
import no.bellaybestia.audex.database.ActivityDao
import no.bellaybestia.audex.database.ServerDao
import no.bellaybestia.audex.domain.model.absCoverUrl
import no.bellaybestia.audex.domain.settings.ActivityKind
import no.bellaybestia.audex.domain.settings.ActivityStats
import no.bellaybestia.audex.domain.settings.ActivityStatsRepository
import no.bellaybestia.audex.domain.settings.BookActivity
import no.bellaybestia.audex.domain.settings.KindStats
import no.bellaybestia.audex.network.abs.AbsClientFactory

/**
 * "Your activity" computed from Audex's own local ledger (accurate by
 * construction), not from the server's opaque all-time total. Listening and
 * reading time are recorded as they happen; the all-time figure is seeded ONCE
 * from the server's per-book listening totals so it isn't empty on day one
 * (that seed lands at the sentinel day 0, so it counts toward all-time and the
 * per-book breakdown but never toward today / this week).
 */
@Singleton
class ActivityStatsRepositoryImpl @Inject constructor(
    private val activityDao: ActivityDao,
    private val serverDao: ServerDao,
    private val clientFactory: AbsClientFactory,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) : ActivityStatsRepository {

    @Volatile private var seeded = false

    override suspend fun stats(): ActivityStats = withContext(dispatcher) {
        seedFromServerOnce()
        ActivityStats(
            listen = kindStats(ActivityKind.LISTEN),
            read = kindStats(ActivityKind.READ),
        )
    }

    private suspend fun kindStats(kind: ActivityKind): KindStats {
        val today = LocalDate.now().toEpochDay()
        val weekFrom = today - 6 // last 7 days, inclusive
        return KindStats(
            totalSeconds = activityDao.total(kind.db).toLong(),
            todaySeconds = activityDao.since(kind.db, today).toLong(),
            weekSeconds = activityDao.since(kind.db, weekFrom).toLong(),
            daysActive = activityDao.daysActive(kind.db),
            books = activityDao.books(kind.db),
        )
    }

    override suspend fun breakdown(kind: ActivityKind): List<BookActivity> = withContext(dispatcher) {
        val bases = serverDao.enabled().associate { it.serverId to it.baseUrl }
        activityDao.breakdown(kind.db).map { row ->
            BookActivity(
                serverId = row.serverId,
                libraryItemId = row.libraryItemId,
                title = row.title?.takeIf { it.isNotBlank() } ?: "Unknown book",
                coverUrl = bases[row.serverId]?.let { base -> absCoverUrl(base, row.libraryItemId) },
                seconds = row.seconds.toLong(),
            )
        }
    }

    /** Seed all-time listening from the server's per-book totals, once, when it
     * succeeds. Reading has no server source, so it starts at zero and grows. */
    private suspend fun seedFromServerOnce() {
        if (seeded) return
        val server = serverDao.enabled().firstOrNull() ?: return
        val stats = runCatching {
            clientFactory.api(server.serverId, server.baseUrl).listeningStats()
        }.getOrNull() ?: return // offline / no server — retry on a later load
        stats.items.forEach { (key, item) ->
            val itemId = item.id.ifBlank { key }
            if (itemId.isNotBlank() && item.timeListening > 0.0) {
                activityDao.setSeed(server.serverId, itemId, ActivityKind.LISTEN.db, item.timeListening)
            }
        }
        seeded = true
    }
}
