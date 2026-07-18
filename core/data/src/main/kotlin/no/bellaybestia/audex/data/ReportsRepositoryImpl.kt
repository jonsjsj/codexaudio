package no.bellaybestia.audex.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import no.bellaybestia.audex.domain.reader.AlignmentRepository
import no.bellaybestia.audex.domain.settings.FiledReport
import no.bellaybestia.audex.domain.settings.MyReport
import no.bellaybestia.audex.domain.settings.ReportKind
import no.bellaybestia.audex.domain.settings.ReportsRepository
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val KEY_MY_REPORTS = stringPreferencesKey("my_reports")

@Serializable
private data class WireReport(
    val kind: String,
    val title: String,
    val body: String,
    val appVersion: String,
)

@Serializable
private data class WireFiled(val number: Int = 0, val url: String = "")

@Serializable
private data class WireStatus(
    val number: Int = 0,
    val state: String = "open",
    val fixedIn: String? = null,
    val url: String = "",
)

@Serializable
private data class StoredReport(
    val number: Int,
    val url: String,
    val kind: String,
    val title: String,
    val createdAt: Long,
    val state: String = "open",
    val fixedIn: String? = null,
)

/**
 * Files reports through the audex-align box's /reports endpoint (it holds the
 * GitHub token server-side and opens the issue), and keeps a local list of
 * this device's reports with tracker state — the Codex "Your reports" loop.
 */
@Singleton
class ReportsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alignment: AlignmentRepository,
) : ReportsRepository {

    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient()
    private val listSerializer = ListSerializer(StoredReport.serializer())

    override val myReports: Flow<List<MyReport>> =
        context.appSettingsDataStore.data.map { prefs ->
            decode(prefs[KEY_MY_REPORTS]).map { it.toDomain() }
        }

    override suspend fun submit(
        kind: ReportKind,
        title: String,
        body: String,
        appVersion: String,
        screen: String?,
    ): FiledReport = withContext(Dispatchers.IO) {
        val service = alignment.serviceUrl()
            ?: error("No service configured — set the alignment service URL in Settings first.")
        // Auto-attach the build, device, and the screen the user came from — so triage has
        // context without the reporter having to type it (parity with Codex's reporter).
        val meta = buildString {
            append("\n\n— Audex ").append(appVersion)
            append(" · ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
            append(" · Android ").append(android.os.Build.VERSION.RELEASE)
            append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(')')
            if (!screen.isNullOrBlank()) append("\nScreen: ").append(screen)
        }
        val fullBody = body + meta
        val payload = json.encodeToString(
            WireReport.serializer(),
            WireReport(kind.name.lowercase(), title, fullBody, appVersion),
        ).toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$service/reports").post(payload).build()
        val filed = http.newCall(request).execute().use { response ->
            check(response.isSuccessful) {
                if (response.code == 503) "Report service isn't set up on the server yet."
                else "Sending failed (HTTP ${response.code})."
            }
            json.decodeFromString(WireFiled.serializer(), response.body?.string().orEmpty())
        }
        // Remember it locally so "Your reports" can track the loop.
        context.appSettingsDataStore.edit { prefs ->
            val current = decode(prefs[KEY_MY_REPORTS])
            val record = StoredReport(
                number = filed.number,
                url = filed.url,
                kind = kind.name,
                title = title,
                createdAt = System.currentTimeMillis(),
            )
            prefs[KEY_MY_REPORTS] = json.encodeToString(
                listSerializer,
                (listOf(record) + current).take(50),
            )
        }
        FiledReport(filed.number, filed.url)
    }

    override suspend fun refreshMyReports() = withContext(Dispatchers.IO) {
        val service = alignment.serviceUrl() ?: return@withContext
        val current = decode(context.appSettingsDataStore.data.first()[KEY_MY_REPORTS])
        if (current.isEmpty()) return@withContext
        val updated = current.map { report ->
            if (report.state == "closed") return@map report // terminal; skip the call
            runCatching {
                val request = Request.Builder().url("$service/reports/${report.number}").get().build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use report
                    val status = json.decodeFromString(
                        WireStatus.serializer(),
                        response.body?.string().orEmpty(),
                    )
                    report.copy(state = status.state, fixedIn = status.fixedIn)
                }
            }.getOrDefault(report)
        }
        context.appSettingsDataStore.edit { prefs ->
            prefs[KEY_MY_REPORTS] = json.encodeToString(listSerializer, updated)
        }
    }

    private fun decode(raw: String?): List<StoredReport> =
        raw?.let { runCatching { json.decodeFromString(listSerializer, it) }.getOrNull() }
            ?: emptyList()

    private fun StoredReport.toDomain() = MyReport(
        number = number,
        url = url,
        kind = runCatching { ReportKind.valueOf(kind) }.getOrDefault(ReportKind.FEEDBACK),
        title = title,
        createdAt = createdAt,
        state = state,
        fixedIn = fixedIn,
    )
}
