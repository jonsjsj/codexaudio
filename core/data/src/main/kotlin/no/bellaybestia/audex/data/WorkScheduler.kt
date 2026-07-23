package no.bellaybestia.audex.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import no.bellaybestia.audex.data.workers.DownloadWorker
import no.bellaybestia.audex.data.workers.EbookProgressUploadWorker
import no.bellaybestia.audex.data.workers.LibrarySyncWorker
import no.bellaybestia.audex.data.workers.PodcastSyncWorker
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
        workManager.enqueueUniquePeriodicWork(
            EbookProgressUploadWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<EbookProgressUploadWorker>(1, TimeUnit.HOURS)
                .setConstraints(connected)
                .build(),
        )
        // Podcasts refresh more often than books: episodes drop daily and the
        // server auto-downloads on its own cron, so a 3h pull keeps the list live.
        workManager.enqueueUniquePeriodicWork(
            PodcastSyncWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PodcastSyncWorker>(3, TimeUnit.HOURS)
                .setConstraints(connected)
                .build(),
        )
    }

    /** Flush queued ebook positions as soon as there's connectivity. */
    fun uploadEbookProgressNow() {
        workManager.enqueueUniqueWork(
            "${EbookProgressUploadWorker.UNIQUE_NAME}-now",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<EbookProgressUploadWorker>().setConstraints(connected).build(),
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

    /** Ingest podcast libraries now (after login, subscribe, or a socket change). */
    fun syncPodcastsNow() {
        workManager.enqueueUniqueWork(
            "${PodcastSyncWorker.UNIQUE_NAME}-now",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<PodcastSyncWorker>().setConstraints(connected).build(),
        )
    }

    /** Download an item's audio or ebook files for offline use. */
    fun downloadNow(serverId: String, libraryItemId: String, kind: DownloadKind) {
        val data = workDataOf(
            DownloadWorker.KEY_SERVER to serverId,
            DownloadWorker.KEY_ITEM to libraryItemId,
            DownloadWorker.KEY_KIND to kind.name,
        )
        // REPLACE, not KEEP: with KEEP a work request stuck in retry backoff (or
        // orphaned by a process death) silently swallows the user's re-tap and
        // the download can never be restarted from the UI.
        workManager.enqueueUniqueWork(
            "download-$serverId-$libraryItemId-${kind.name}",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(data)
                .setConstraints(connected)
                .build(),
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
