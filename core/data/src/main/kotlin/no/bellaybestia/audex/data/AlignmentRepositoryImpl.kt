package no.bellaybestia.audex.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.bellaybestia.audex.auth.ServerTokenStore
import no.bellaybestia.audex.database.ServerDao
import no.bellaybestia.audex.domain.reader.AlignmentRepository
import no.bellaybestia.audex.domain.reader.SyncAnchor
import no.bellaybestia.audex.domain.reader.SyncChapter
import no.bellaybestia.audex.domain.reader.SyncMap
import no.bellaybestia.audex.domain.reader.WordSyncProgress
import no.bellaybestia.audex.domain.reader.WordSyncStatus
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val KEY_ALIGN_URL = stringPreferencesKey("align_service_url")

@Serializable
private data class WireAnchor(
    val t0: Double,
    val t1: Double,
    val p: Double,
    val href: String? = null,
    val text: String? = null,
    val c0: Int = 0,
)

@Serializable
private data class WireChapter(val href: String = "", val c0: Int = 0, val c1: Int = 0)

@Serializable
private data class WireMap(
    val version: Int = 1,
    val durationS: Double = 0.0,
    val entries: List<WireAnchor> = emptyList(),
    val chapters: List<WireChapter> = emptyList(),
)

@Serializable
private data class WireJobRequest(
    val serverUrl: String,
    val token: String,
    val libraryItemId: String,
    val ebookLibraryItemId: String? = null,
)

@Serializable
private data class WireJobResp(val jobId: String = "", val bookKey: String = "")

@Serializable
private data class WireJobStatus(val state: String = "queued", val detail: String = "", val bookKey: String = "")

@Serializable
private data class WireHealth(val jobs: Map<String, String> = emptyMap())

/**
 * Talks to the self-hosted audex-align service (alignment-service/). The book
 * key must match the service's: sha1("{serverUrl}|{audioItemId}")[:16] with the
 * serverUrl exactly as sent in the job request (our stored baseUrl, no trailing
 * slash). Fetched maps are cached under filesDir/syncmaps for offline reading.
 */
@Singleton
class AlignmentRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val serverDao: ServerDao,
    private val tokenStore: ServerTokenStore,
) : AlignmentRepository {

    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient()
    private val cacheDir = File(context.filesDir, "syncmaps").apply { mkdirs() }

    // Book keys we queued this process; promotes status to RUNNING until READY.
    private val requested = ConcurrentHashMap.newKeySet<String>()

    // Book key -> (align jobId, startedAtMs) for jobs we queued, so we can poll
    // /jobs/{id} for phase and estimate progress + ETA.
    private val jobs = ConcurrentHashMap<String, Pair<String, Long>>()

    override suspend fun serviceUrl(): String? =
        context.appSettingsDataStore.data.first()[KEY_ALIGN_URL]?.takeIf { it.isNotBlank() }

    override suspend fun setServiceUrl(url: String?) {
        context.appSettingsDataStore.edit { prefs ->
            val trimmed = url?.trim()?.trimEnd('/')
            if (trimmed.isNullOrBlank()) prefs.remove(KEY_ALIGN_URL) else prefs[KEY_ALIGN_URL] = trimmed
        }
    }

    override suspend fun requestAlignment(
        serverId: String,
        audioItemId: String,
        ebookItemId: String?,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val service = serviceUrl() ?: error("No alignment service configured")
            val baseUrl = baseUrlFor(serverId) ?: error("Unknown server")
            val token = tokenStore.accessToken(serverId) ?: error("Not logged in to this server")
            val body = json.encodeToString(
                WireJobRequest.serializer(),
                WireJobRequest(baseUrl, token, audioItemId, ebookItemId),
            ).toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url("$service/jobs/abs").post(body).build()
            val key = bookKey(baseUrl, audioItemId)
            http.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Alignment service said HTTP ${response.code}" }
                // Remember the jobId so progress() can poll the phase + estimate ETA.
                runCatching {
                    json.decodeFromString(WireJobResp.serializer(), response.body?.string().orEmpty())
                }.getOrNull()?.jobId?.takeIf { it.isNotBlank() }?.let {
                    jobs[key] = it to System.currentTimeMillis()
                }
            }
            requested.add(key)
            Unit
        }
    }

    override suspend fun syncMap(serverId: String, audioItemId: String): SyncMap? =
        withContext(Dispatchers.IO) {
            val baseUrl = baseUrlFor(serverId) ?: return@withContext null
            val key = bookKey(baseUrl, audioItemId)
            val cached = File(cacheDir, "$key.json")
            val service = serviceUrl()
            if (service != null) {
                runCatching {
                    val request = Request.Builder().url("$service/maps/$key").get().build()
                    http.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            cached.writeText(response.body?.string().orEmpty())
                        }
                    }
                }
            }
            if (!cached.exists()) return@withContext null
            runCatching {
                val wire = json.decodeFromString(WireMap.serializer(), cached.readText())
                SyncMap(
                    durationS = wire.durationS,
                    anchors = wire.entries.map {
                        SyncAnchor(it.t0, it.t1, it.p, it.href, it.text, it.c0)
                    },
                    chapters = wire.chapters.map { SyncChapter(it.href, it.c0, it.c1) },
                )
            }.getOrNull()
        }

    override suspend fun status(serverId: String, audioItemId: String): WordSyncStatus {
        serviceUrl() ?: return WordSyncStatus.UNAVAILABLE
        val baseUrl = baseUrlFor(serverId) ?: return WordSyncStatus.UNAVAILABLE
        val key = bookKey(baseUrl, audioItemId)
        if (File(cacheDir, "$key.json").exists()) return WordSyncStatus.READY
        // Full fetch instead of HEAD: it doubles as warming the cache, and the
        // service's GET routes don't answer HEAD (FastAPI: 405).
        return when {
            syncMap(serverId, audioItemId) != null -> WordSyncStatus.READY
            key in requested -> WordSyncStatus.RUNNING
            else -> WordSyncStatus.NONE
        }
    }

    override suspend fun progress(
        serverId: String,
        audioItemId: String,
        audioDurationS: Double?,
    ): WordSyncProgress = withContext(Dispatchers.IO) {
        val service = serviceUrl() ?: return@withContext WordSyncProgress(WordSyncStatus.NOT_CONFIGURED)
        val baseUrl = baseUrlFor(serverId)
            ?: return@withContext WordSyncProgress(WordSyncStatus.UNAVAILABLE)
        val key = bookKey(baseUrl, audioItemId)
        if (File(cacheDir, "$key.json").exists()) {
            return@withContext WordSyncProgress(WordSyncStatus.READY, 1f)
        }
        // Resolve the running job: one we queued this session, else scan the service's live jobs.
        val entry = jobs[key] ?: findJobId(service, key)?.let { it to System.currentTimeMillis() }
        if (entry == null) {
            return@withContext WordSyncProgress(
                if (key in requested) WordSyncStatus.RUNNING else WordSyncStatus.NONE,
            )
        }
        val (jobId, startedAt) = entry
        val js = fetchJob(service, jobId)
            ?: return@withContext WordSyncProgress(WordSyncStatus.RUNNING)
        when (js.state) {
            "done" -> return@withContext WordSyncProgress(WordSyncStatus.READY, 1f)
            "error" -> return@withContext WordSyncProgress(WordSyncStatus.NONE)
        }
        val elapsedS = (System.currentTimeMillis() - startedAt) / 1000.0
        val est = audioDurationS?.takeIf { it > 0 }?.let { it / 2.5 } ?: 0.0  // CPU ≈ 2.5x realtime
        var eta: Long? = null
        val prog = when (js.state) {
            "queued" -> 0.03f
            "downloading" -> 0.08f
            "extracting" -> 0.14f
            "transcribing" -> if (est > 0) {
                eta = (est - elapsedS).coerceAtLeast(0.0).toLong()
                0.15f + 0.78f * (elapsedS / est).coerceIn(0.0, 1.0).toFloat()
            } else 0.5f
            "aligning" -> 0.95f
            else -> 0.1f
        }
        WordSyncProgress(WordSyncStatus.RUNNING, prog, eta, js.state.replaceFirstChar { it.uppercase() })
    }

    private fun fetchJob(service: String, jobId: String): WireJobStatus? = runCatching {
        val request = Request.Builder().url("$service/jobs/$jobId").get().build()
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) null
            else json.decodeFromString(WireJobStatus.serializer(), r.body?.string().orEmpty())
        }
    }.getOrNull()

    /** Match a running server job to our book key (for jobs we didn't queue this session). */
    private fun findJobId(service: String, key: String): String? = runCatching {
        val hr = Request.Builder().url("$service/health").get().build()
        val health = http.newCall(hr).execute().use { r ->
            if (!r.isSuccessful) return@runCatching null
            json.decodeFromString(WireHealth.serializer(), r.body?.string().orEmpty())
        }
        health.jobs.entries.firstOrNull { (jid, state) ->
            state != "done" && state != "error" && fetchJob(service, jid)?.bookKey == key
        }?.key
    }.getOrNull()

    private suspend fun baseUrlFor(serverId: String): String? =
        serverDao.enabled().firstOrNull { it.serverId == serverId }?.baseUrl?.trimEnd('/')

    private fun bookKey(baseUrl: String, itemId: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest("$baseUrl|$itemId".toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)
}
