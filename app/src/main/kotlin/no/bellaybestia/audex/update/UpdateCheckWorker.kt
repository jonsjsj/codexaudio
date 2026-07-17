package no.bellaybestia.audex.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import no.bellaybestia.audex.MainActivity

/**
 * Background "new version is ready" notifier (the Codex Companion pattern):
 * checks the OTA manifest periodically and posts a tappable notification once
 * per advertised versionCode. Tapping opens the app, whose launch update
 * dialog then handles the actual download + install.
 */
@HiltWorker
class UpdateCheckWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val updateManager: UpdateManager,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val info = updateManager.check() ?: return Result.success()
        // Notify once per version — re-checking must not re-nag.
        val prefs = appContext.getSharedPreferences("update_notify", Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_LAST_NOTIFIED, 0) >= info.versionCode) return Result.success()
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) {
            return Result.success()
        }
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val open = PendingIntent.getActivity(
            appContext,
            0,
            Intent(appContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Audex ${info.versionName} is ready")
            .setContentText(info.notes.ifBlank { "Tap to update." })
            .setStyle(NotificationCompat.BigTextStyle().bigText(info.notes))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, notification)
        prefs.edit().putInt(KEY_LAST_NOTIFIED, info.versionCode).apply()
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "update-check"
        private const val CHANNEL_ID = "updates"
        private const val NOTIFICATION_ID = 43
        private const val KEY_LAST_NOTIFIED = "last_notified_vc"
    }
}
