package no.bellaybestia.audex.data.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import no.bellaybestia.audex.data.DownloadKind
import no.bellaybestia.audex.data.DownloadManager
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
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val downloadManager: DownloadManager,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val serverId = inputData.getString(KEY_SERVER) ?: return Result.failure()
        val itemId = inputData.getString(KEY_ITEM) ?: return Result.failure()
        val kind = inputData.getString(KEY_KIND)
            ?.let { runCatching { DownloadKind.valueOf(it) }.getOrNull() }
            ?: return Result.failure()
        return if (downloadManager.download(serverId, itemId, kind)) Result.success() else Result.retry()
    }

    companion object {
        const val KEY_SERVER = "serverId"
        const val KEY_ITEM = "itemId"
        const val KEY_KIND = "kind"
    }
}
