package no.bellaybestia.audex.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.bellaybestia.audex.domain.reader.AlignmentRepository
import no.bellaybestia.audex.domain.settings.FiledReport
import no.bellaybestia.audex.domain.settings.ReportKind
import no.bellaybestia.audex.domain.settings.ReportsRepository
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
private data class WireReport(
    val kind: String,
    val title: String,
    val body: String,
    val appVersion: String,
)

@Serializable
private data class WireFiled(val number: Int = 0, val url: String = "")

/**
 * Files reports through the audex-align box's /reports endpoint (it holds the
 * GitHub token server-side and opens the issue). Reuses the configured
 * word-sync service URL — same box distributes the app and its updates.
 */
@Singleton
class ReportsRepositoryImpl @Inject constructor(
    private val alignment: AlignmentRepository,
) : ReportsRepository {

    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient()

    override suspend fun submit(
        kind: ReportKind,
        title: String,
        body: String,
        appVersion: String,
    ): FiledReport = withContext(Dispatchers.IO) {
        val service = alignment.serviceUrl()
            ?: error("No service configured — set the alignment service URL in Settings first.")
        val payload = json.encodeToString(
            WireReport.serializer(),
            WireReport(kind.name.lowercase(), title, body, appVersion),
        ).toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$service/reports").post(payload).build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) {
                if (response.code == 503) "Report service isn't set up on the server yet."
                else "Sending failed (HTTP ${response.code})."
            }
            val filed = json.decodeFromString(WireFiled.serializer(), response.body?.string().orEmpty())
            FiledReport(filed.number, filed.url)
        }
    }
}
