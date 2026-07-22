package no.bellaybestia.audex.data

import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import no.bellaybestia.audex.database.ActivityDao
import no.bellaybestia.audex.domain.settings.ActivityKind
import no.bellaybestia.audex.domain.settings.ActivityRecorder

/**
 * Writes real time-spent into the local `activity` ledger, bucketed by today's
 * date. Additive: every listen/read tick just adds seconds to the day's total.
 */
@Singleton
class ActivityRecorderImpl @Inject constructor(
    private val activityDao: ActivityDao,
) : ActivityRecorder {

    override suspend fun record(
        serverId: String,
        libraryItemId: String,
        kind: ActivityKind,
        seconds: Double,
    ) {
        if (seconds <= 0.0 || serverId.isBlank() || libraryItemId.isBlank()) return
        activityDao.add(serverId, libraryItemId, LocalDate.now().toEpochDay(), kind.db, seconds)
    }
}
