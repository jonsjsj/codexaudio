package no.bellaybestia.audex.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.bellaybestia.audex.BuildConfig
import no.bellaybestia.audex.domain.reader.AlignmentRepository
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * In-app updater. The self-hosted audex-align box publishes a version manifest
 * (/audex-latest.json) next to the APK (/audex.apk); when it advertises a higher
 * versionCode than this build, we download the APK and hand it to the system
 * package installer. The update host is the configured word-sync service if set
 * (same box distributes the app), otherwise the compiled-in public default.
 */
@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alignment: AlignmentRepository,
) {
    private val http = OkHttpClient()

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val url: String,
        val notes: String,
    )

    private suspend fun base(): String =
        (alignment.serviceUrl()?.takeIf { it.isNotBlank() } ?: BuildConfig.UPDATE_URL).trimEnd('/')

    /** Returns update info only when the server advertises a newer build. */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val root = base()
        runCatching {
            val req = Request.Builder().url("$root/audex-latest.json").get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val o = JSONObject(resp.body?.string().orEmpty())
                val versionCode = o.getInt("versionCode")
                if (versionCode <= BuildConfig.VERSION_CODE) return@use null
                val rawUrl = o.optString("url").ifBlank { "$root/audex.apk" }
                val url = if (rawUrl.startsWith("http")) rawUrl else "$root$rawUrl"
                UpdateInfo(
                    versionCode = versionCode,
                    versionName = o.optString("versionName", "?"),
                    url = url,
                    notes = o.optString("notes", ""),
                )
            }
        }.getOrNull()
    }

    /**
     * Download the APK to app-private external storage (streaming, reporting
     * 0..1 progress) then launch the installer. The FileProvider grants the
     * installer read access to the content:// URI.
     */
    suspend fun downloadAndInstall(info: UpdateInfo, onProgress: (Float) -> Unit) {
        val apk = withContext(Dispatchers.IO) {
            val dir = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
            val target = File(dir, "audex-${info.versionCode}.apk")
            val req = Request.Builder().url(info.url).get().build()
            http.newCall(req).execute().use { resp ->
                check(resp.isSuccessful) { "download HTTP ${resp.code}" }
                val body = resp.body ?: error("empty download body")
                val total = body.contentLength()
                target.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(1 shl 16)
                        var read = 0L
                        var n = input.read(buf)
                        while (n >= 0) {
                            out.write(buf, 0, n)
                            read += n
                            if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                            n = input.read(buf)
                        }
                    }
                }
            }
            target
        }
        withContext(Dispatchers.Main) { launchInstall(apk) }
    }

    private fun launchInstall(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
