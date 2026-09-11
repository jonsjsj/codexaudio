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
import no.bellaybestia.audex.domain.repository.CatalogRepository
import no.bellaybestia.audex.domain.settings.FiledReport
import no.bellaybestia.audex.domain.settings.MyReport
import no.bellaybestia.audex.domain.settings.ReportKind
import no.bellaybestia.audex.domain.settings.ReportsRepository
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val KEY_MY_REPORTS = stringPreferencesKey("my_reports")

/** Default reports host — the self-hosted audex-align box (also serves word sync).
 * LAN-only, so it can't be reached over mobile data. */
private const val DEFAULT_REPORT_HOST = "http://192.168.68.212:8590"

/** Public reports host — codex.* proxies /reports to align and is reachable over
 * the internet (audex.* is a dead NPMplus page), so reporting works off-LAN too.
 * Same alive host the OTA updater falls back to (BuildConfig.UPDATE_URL_ALT). */
private const val PUBLIC_REPORT_HOST = "https://codex.bellaybestia.no"

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
    private val catalogRepository: CatalogRepository,
) : ReportsRepository {

    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient()
    private val listSerializer = ListSerializer(StoredReport.serializer())

    /**
     * Hosts the reporter talks to, in order. The word-sync alignment box also
     * serves /reports, so we use it when it's set — but reporting must NOT
     * require the (optional) word-sync URL to be configured, which was making
     * "Send report" throw "No service configured". Fall back to the default
     * self-hosted box so a fresh install can file reports out of the box.
     */
    private suspend fun reportBases(): List<String> = buildList {
        alignment.serviceUrl()?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }?.let { add(it) }
        add(DEFAULT_REPORT_HOST)
        // Public fallback so reporting works off-LAN (mobile data). On LAN the box
        // answers first; off-LAN the LAN hosts fail fast and this one takes it.
        add(PUBLIC_REPORT_HOST)
    }.distinct()

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
        diagnostics: String?,
    ): FiledReport = withContext(Dispatchers.IO) {
        val fullBody = buildString {
            append(body)
            if (screen != null) append("\n\nScreen: $screen")
            if (!diagnostics.isNullOrBlank()) {
                append("\n\n<details><summary>Diagnostic data</summary>\n\n```\n")
                append(diagnostics)
                append("\n```\n</details>")
            }
        }
        val payloadJson = json.encodeToString(
            WireReport.serializer(),
            WireReport(kind.name.lowercase(), title, fullBody, appVersion),
        )
        // Try each host; the first that accepts the report wins. A fresh request
        // body per attempt (OkHttp bodies aren't reusable across calls).
        var lastError: Throwable? = null
        var result: WireFiled? = null
        for (base in reportBases()) {
            val attempt = runCatching {
                val payload = payloadJson.toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url("$base/reports").post(payload).build()
                http.newCall(request).execute().use { response ->
                    check(response.isSuccessful) {
                        if (response.code == 503) "Report service isn't set up on the server yet."
                        else "Sending failed (HTTP ${response.code})."
                    }
                    json.decodeFromString(WireFiled.serializer(), response.body?.string().orEmpty())
                }
            }
            if (attempt.isSuccess) { result = attempt.getOrThrow(); break }
            lastError = attempt.exceptionOrNull()
        }
        val filed = result ?: throw (lastError ?: IllegalStateException("Couldn't reach the report service."))
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

    override suspend fun buildDiagnostics(): String = withContext(Dispatchers.IO) {
        val rows = runCatching { catalogRepository.debugProgressRows().first() }.getOrDefault(emptyList())
        // Reports are filed as PUBLIC GitHub issues — a book title is someone's
        // actual reading history, not something that belongs on a public tracker.
        // Replace it with a per-report label instead: stable WITHIN this one
        // diagnostic dump (so a book's audio and ebook rows still carry the same
        // label, keeping a cross-format issue traceable) but not across reports —
        // labels.size ties it to iteration order, which is `rows`' own
        // lastUpdate-DESC order, so "Book 1" is always whichever book was touched
        // most recently (almost always the one actually being reported).
        val labels = mutableMapOf<String, String>()
        fun labelFor(title: String?): String {
            val key = title ?: return "Book ?"
            return labels.getOrPut(key) { "Book ${labels.size + 1}" }
        }
        val progressBlock = if (rows.isEmpty()) {
            "(no progress rows)"
        } else {
            rows.joinToString("\n") { r ->
                // Locale.ROOT, not the device default: a comma-decimal locale (this
                // is a .no domain) rendered "pct=0,2525" - fine for a human, but it
                // reads as three fields to anything trying to parse the number back
                // out, and a report should be consistent regardless of the phone.
                "${labelFor(r.title)} [${r.format ?: "?"}] pct=${"%.4f".format(java.util.Locale.ROOT, r.pct)} " +
                    "currentTimeS=${r.currentTimeS} ebookProgress=${r.ebookProgress} " +
                    "isFinished=${r.isFinished} source=${r.source} lastUpdate=${r.lastUpdate}"
            }
        }
        // Best-effort scrub of the same titles out of the log text too: nothing in
        // this app logs a title directly through Timber today (only the Readium
        // reader library's own internal diagnostics flow through it), but if any of
        // these exact titles happen to appear there anyway, swap them for the same
        // label used above rather than risk a duplicate leak. Longest title first,
        // so a title that's a substring of another doesn't get partially replaced.
        var logText = DiagnosticLog.snapshot().joinToString("\n").ifBlank { "(no log lines captured)" }
        labels.keys.sortedByDescending { it.length }.forEach { title ->
            if (title.length >= 3) logText = logText.replace(title, labels.getValue(title))
        }
        "Progress table:\n$progressBlock\n\nRecent log:\n$logText"
    }

    override suspend fun refreshMyReports() = withContext(Dispatchers.IO) {
        val current = decode(context.appSettingsDataStore.data.first()[KEY_MY_REPORTS])
        if (current.isEmpty()) return@withContext
        val bases = reportBases()
        val updated = current.map { report ->
            if (report.state == "closed") return@map report // terminal; skip the call
            // First host that answers wins; otherwise keep the last-known status.
            bases.firstNotNullOfOrNull { base ->
                runCatching {
                    val request = Request.Builder().url("$base/reports/${report.number}").get().build()
                    http.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use null
                        val status = json.decodeFromString(
                            WireStatus.serializer(),
                            response.body?.string().orEmpty(),
                        )
                        report.copy(state = status.state, fixedIn = status.fixedIn)
                    }
                }.getOrNull()
            } ?: report
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
