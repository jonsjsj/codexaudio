package no.bellaybestia.audex.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import no.bellaybestia.audex.data.workers.LibrarySyncWorker
import no.bellaybestia.audex.data.workers.SessionUploadWorker
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the WorkManager schedule: a periodic library sync and a periodic
 * session-upload drain, both gated on connectivity, plus one-shot triggers used
 * after login, on socket library-change events, and when a local session is
 * recorded. Unique names keep the periodic work idempotent across app starts.
 */
@Singleton
class WorkScheduler @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val workManager = WorkManager.getInstance(context)

    private val connected = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** Idempotently install the periodic schedules (call at startup). */
    fun scheduleAll() {
        workManager.enqueueUniquePeriodicWork(
            LibrarySyncWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<LibrarySyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(connected)
                .build(),
        )
        workManager.enqueueUniquePeriodicWork(
            SessionUploadWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SessionUploadWorker>(1, TimeUnit.HOURS)
                .setConstraints(connected)
                .build(),
        )
    }

    /** Sync the library now (after login, or on a socket library-change event). */
    fun syncNow() {
        workManager.enqueueUniqueWork(
            "${LibrarySyncWorker.UNIQUE_NAME}-now",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<LibrarySyncWorker>().setConstraints(connected).build(),
        )
    }

    /** Drain pending local sessions as soon as there's connectivity. */
    fun uploadSessionsNow() {
        workManager.enqueueUniqueWork(
            "${SessionUploadWorker.UNIQUE_NAME}-now",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<SessionUploadWorker>().setConstraints(connected).build(),
        )
    }
}
