package no.bellaybestia.audex.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import no.bellaybestia.audex.common.DefaultDispatcher
import no.bellaybestia.audex.database.ServerDao
import no.bellaybestia.audex.domain.settings.ListeningStats
import no.bellaybestia.audex.domain.settings.StatsRepository
import no.bellaybestia.audex.network.abs.AbsClientFactory
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StatsRepositoryImpl @Inject constructor(
    private val serverDao: ServerDao,
    private val clientFactory: AbsClientFactory,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) : StatsRepository {

    override suspend fun listening(): ListeningStats? = withContext(dispatcher) {
        val server = serverDao.enabled().firstOrNull() ?: return@withContext null
        val stats = runCatching { clientFactory.api(server.serverId, server.baseUrl).listeningStats() }
            .getOrNull() ?: return@withContext null
        val weekKeys = (0..6).map { LocalDate.now().minusDays(it.toLong()).toString() }.toSet()
        val weekSecs = stats.days.filterKeys { it in weekKeys }.values.sum()
        ListeningStats(
            totalMinutes = (stats.totalTime / 60).toLong(),
            todayMinutes = (stats.today / 60).toLong(),
            weekMinutes = (weekSecs / 60).toLong(),
            daysActive = stats.days.count { it.value > 0 },
            booksStarted = stats.items.size,
        )
    }
}
