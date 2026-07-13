package no.bellaybestia.codexaudio.data.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import no.bellaybestia.codexaudio.data.LibrarySyncer
import no.bellaybestia.codexaudio.data.SessionUploader

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
