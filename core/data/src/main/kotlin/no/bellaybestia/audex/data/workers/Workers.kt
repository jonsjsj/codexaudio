package no.bellaybestia.audex.data.workers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import no.bellaybestia.audex.data.DownloadKind
import no.bellaybestia.audex.data.DownloadManager
import no.bellaybestia.audex.data.EbookProgressUploader
import no.bellaybestia.audex.data.LibrarySyncer
import no.bellaybestia.audex.data.PodcastSyncer
import no.bellaybestia.audex.data.SessionUploader
import no.bellaybestia.audex.data.WorkScheduler
import no.bellaybestia.audex.domain.reader.AlignmentRepository
import no.bellaybestia.audex.domain.reader.AlignmentWatch
import no.bellaybestia.audex.domain.reader.WordSyncStatus
import no.bellaybestia.audex.domain.settings.NotificationSettings
import java.util.concurrent.TimeUnit

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
 * Ingests every enabled server's PODCAST libraries into the podcast tables and
 * reconciles per-episode progress. Parallel to [LibrarySyncWorker] and does not
 * rebuild the catalog graph. Per-server failures are isolated inside the syncer.
 */
@HiltWorker
class PodcastSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncer: PodcastSyncer,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = runCatching {
        syncer.syncAll()
        Result.success()
    }.getOrElse { Result.retry() }

    companion object {
        const val UNIQUE_NAME = "podcast-sync"
    }
}

/**
 * Polls the read-along (audio-ebook sync) builds the user kicked off and posts a local
 * notification when one finishes or fails — even if the app is closed. Self-reschedules
 * every couple of minutes while any build is still pending, and stops once they've all
 * resolved (a build takes a while server-side). Respects the Notifications setting and the
 * OS notification permission. Failure = a build we'd seen RUNNING that the service no longer
 * reports (errored / vanished); a stale watch is dropped after a few hours.
 */
@HiltWorker
class AlignmentWatchWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val alignmentRepository: AlignmentRepository,
    private val notificationSettings: NotificationSettings,
    private val workScheduler: WorkScheduler,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val pending = alignmentRepository.pendingAlignments()
        if (pending.isEmpty()) return Result.success()
        val allowed = runCatching { notificationSettings.prefs.first().readAlong }.getOrDefault(true)
        val seen = appContext.getSharedPreferences("align_watch", Context.MODE_PRIVATE)
        var reschedule = false
        for (w in pending) {
            if (System.currentTimeMillis() - w.startedAtMs > MAX_WATCH_MS) {
                drop(w, seen)
                continue
            }
            val status = runCatching { alignmentRepository.status(w.serverId, w.audioItemId) }
                .getOrNull()
            if (status == null) { reschedule = true; continue }
            val seenRunning = seen.getBoolean(w.audioItemId, false)
            when (status) {
                WordSyncStatus.READY -> {
                    if (allowed) notify(w, done = true)
                    drop(w, seen)
                }
                WordSyncStatus.RUNNING -> {
                    if (!seenRunning) seen.edit().putBoolean(w.audioItemId, true).apply()
                    reschedule = true
                }
                WordSyncStatus.NONE -> {
                    if (seenRunning) {          // was building, now gone → failed
                        if (allowed) notify(w, done = false)
                        drop(w, seen)
                    } else {
                        reschedule = true        // job may not have registered yet
                    }
                }
                else -> reschedule = true        // NOT_CONFIGURED / UNAVAILABLE — transient
            }
        }
        if (reschedule) workScheduler.watchAlignmentNow()
        return Result.success()
    }

    private suspend fun drop(w: AlignmentWatch, seen: android.content.SharedPreferences) {
        seen.edit().remove(w.audioItemId).apply()
        runCatching { alignmentRepository.clearAlignmentWatch(w.audioItemId) }
    }

    private fun notify(w: AlignmentWatch, done: Boolean) {
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) return
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Read-along maps", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val launch = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
            ?: Intent(Intent.ACTION_MAIN)
        val open = PendingIntent.getActivity(
            appContext, 0, launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val name = w.title.ifBlank { "your book" }
        val title = if (done) "Read-along ready" else "Read-along failed"
        val text = if (done) "\"$name\" is ready — open it to read along with the narration."
        else "Couldn't build the read-along map for \"$name\". Open the book to try again."
        val icon = if (done) android.R.drawable.stat_sys_download_done
        else android.R.drawable.stat_notify_error
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(appContext)
            .notify(NOTIF_BASE + (w.audioItemId.hashCode() and 0xffff), notification)
    }

    companion object {
        const val UNIQUE_NAME = "alignment-watch"
        private const val CHANNEL_ID = "readalong"
        private const val NOTIF_BASE = 4400
        private val MAX_WATCH_MS = TimeUnit.HOURS.toMillis(6)
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
