package no.bellaybestia.audex.data.workers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import no.bellaybestia.audex.data.DownloadKind
import no.bellaybestia.audex.data.DownloadManager
import no.bellaybestia.audex.data.EbookProgressUploader
import no.bellaybestia.audex.data.LibrarySyncer
import no.bellaybestia.audex.data.SessionUploader

/**
 * Walks every enabled server's libraries into remote_items, then triggers a
 * graph rebuild. Per-server failures are isolated: one unreachable server must
 * not block the others (its cached graph contribution stays, flagged stale).
 */
@HiltWorker
class LibrarySyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncer: LibrarySyncer,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = runCatching {
        syncer.syncAll()
        Result.success()
    }.getOrElse { Result.retry() }

    companion object {
        const val UNIQUE_NAME = "library-sync"
    }
}

/**
 * Batch-uploads PENDING local listening sessions per server via
 * POST /api/session/local-all — the offline-correctness backbone
 * (docs/05 diagram b). Re-upload is dedupe-safe (client-generated session ids).
 */
@HiltWorker
class SessionUploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val uploader: SessionUploader,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = runCatching {
        val allSynced = uploader.flush()
        if (allSynced) Result.success() else Result.retry()
    }.getOrElse { Result.retry() }

    companion object {
        const val UNIQUE_NAME = "session-upload"
    }
}

/**
 * Downloads one item's files (audio or ebook) for offline use, streamed to
 * app-private storage. Input: serverId, itemId, kind. Retries on transient
 * failure so a dropped connection resumes when connectivity returns.
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val downloadManager: DownloadManager,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val serverId = inputData.getString(KEY_SERVER) ?: return Result.failure()
        val itemId = inputData.getString(KEY_ITEM) ?: return Result.failure()
        val kind = inputData.getString(KEY_KIND)
            ?.let { runCatching { DownloadKind.valueOf(it) }.getOrNull() }
            ?: return Result.failure()
        // Foreground (dataSync) so a multi-hundred-MB audiobook survives the app
        // being backgrounded; without it Android kills the worker and the row is
        // orphaned in RUNNING. Best-effort: on API 34+ a background start can
        // throw — the download still proceeds, just without FGS protection.
        runCatching { setForeground(foregroundInfo()) }
        val ok = downloadManager.download(serverId, itemId, kind)
        return when {
            ok -> Result.success()
            // Bounded retries: endless retry flip-flops FAILED→RUNNING in the UI
            // and burns data against a down server. After 4 attempts stay FAILED
            // so the user gets a stable "Retry" action.
            runAttemptCount >= 4 -> Result.failure()
            else -> Result.retry()
        }
    }

    private fun foregroundInfo(): ForegroundInfo {
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading")
            .setContentText("Saving for offline use")
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_SERVER = "serverId"
        const val KEY_ITEM = "itemId"
        const val KEY_KIND = "kind"
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 41
    }
}

/** Drains queued ebook positions via PATCH /api/me/progress/{id}. */
@HiltWorker
class EbookProgressUploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val uploader: EbookProgressUploader,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = runCatching {
        if (uploader.flush()) Result.success() else Result.retry()
    }.getOrElse { Result.retry() }

    companion object {
        const val UNIQUE_NAME = "ebook-progress-upload"
    }
}
