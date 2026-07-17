package no.bellaybestia.audex.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import no.bellaybestia.audex.BuildConfig
import no.bellaybestia.audex.domain.reader.AlignmentRepository
import no.bellaybestia.audex.domain.settings.UpdateChannel
import no.bellaybestia.audex.domain.settings.UpdateSettings
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
    private val updateSettings: UpdateSettings,
) {
    private val http = OkHttpClient()

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val url: String,
        val notes: String,
    )

    /**
     * Outcome of a manifest check. [Unreachable] is distinct from [UpToDate] so
     * the UI can say "couldn't reach the update server" (common off-LAN while the
     * public ingress flaps) instead of falsely claiming the app is current.
     */
    sealed interface CheckResult {
        data class Available(val info: UpdateInfo) : CheckResult
        data object UpToDate : CheckResult
        data object Unreachable : CheckResult
    }

    /**
     * Hosts to check, in order — every one that might answer, because they fail
     * independently:
     *  - the public align host (works off-LAN)
     *  - the public Codex host, which mirrors the same manifest + APK. Not
     *    redundancy for its own sake: audex.* has spent long stretches serving
     *    the NPMplus "Default Page" (HTTP 200 + HTML) while codex.* was fine,
     *    and a 200-with-HTML silently killed the check.
     *  - the word-sync box when configured (the origin; fastest on LAN)
     *
     * [checkDetailed] only counts a host as reachable once its JSON parses, so a
     * proxy serving a 200 HTML error page is correctly treated as unreachable
     * rather than as "no update".
     */
    private suspend fun bases(): List<String> = buildList {
        add(BuildConfig.UPDATE_URL.trimEnd('/'))
        add(BuildConfig.UPDATE_URL_ALT.trimEnd('/'))
        alignment.serviceUrl()?.takeIf { it.isNotBlank() }?.let { add(it.trimEnd('/')) }
    }.filter { it.isNotBlank() }.distinct()

    /** Returns update info only when a reachable host advertises a newer build. */
    suspend fun check(): UpdateInfo? = (checkDetailed() as? CheckResult.Available)?.info

    /**
     * Full check result across every host. Tries each base in turn; if any host
     * returns a manifest we know the server is reachable (so we can tell
     * up-to-date apart from an unreachable host). Picks the highest advertised
     * newer build across all hosts.
     */
    suspend fun checkDetailed(): CheckResult = withContext(Dispatchers.IO) {
        // STABLE follows main's releases; BETA follows the report-autofix channel.
        val manifest = when (updateSettings.channel.first()) {
            UpdateChannel.STABLE -> "audex-latest.json"
            UpdateChannel.BETA -> "audex-beta-latest.json"
        }
        var reachable = false
        var best: UpdateInfo? = null
        for (root in bases()) {
            runCatching {
                val req = Request.Builder().url("$root/$manifest").get().build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val o = JSONObject(resp.body?.string().orEmpty())
                    reachable = true
                    val versionCode = o.getInt("versionCode")
                    if (versionCode > BuildConfig.VERSION_CODE) {
                        val rawUrl = o.optString("url").ifBlank { "/audex.apk" }
                        val url = if (rawUrl.startsWith("http")) rawUrl else "$root$rawUrl"
                        val info = UpdateInfo(
                            versionCode = versionCode,
                            versionName = o.optString("versionName", "?"),
                            url = url,
                            notes = o.optString("notes", ""),
                        )
                        if (best == null || info.versionCode > best!!.versionCode) best = info
                    }
                }
            }
        }
        when {
            best != null -> CheckResult.Available(best!!)
            reachable -> CheckResult.UpToDate
            else -> CheckResult.Unreachable
        }
    }

    /**
     * Download the APK to app-private external storage (streaming, reporting
     * 0..1 progress) then launch the installer. The FileProvider grants the
     * installer read access to the content:// URI.
     */
    suspend fun downloadAndInstall(info: UpdateInfo, onProgress: (Float) -> Unit) {
        val apk = withContext(Dispatchers.IO) {
            val dir = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
            // Name the file by the VERSION the user sees (e.g. audex-0.2.12.0.apk),
            // not the internal versionCode, so a saved APK is self-identifying.
            val safeVersion = info.versionName.filter { it.isDigit() || it == '.' }.ifBlank { info.versionCode.toString() }
            val target = File(dir, "audex-$safeVersion.apk")
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
