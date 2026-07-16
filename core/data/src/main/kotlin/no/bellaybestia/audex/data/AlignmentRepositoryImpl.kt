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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.bellaybestia.audex.auth.ServerTokenStore
import no.bellaybestia.audex.database.ServerDao
import no.bellaybestia.audex.domain.reader.AlignmentRepository
import no.bellaybestia.audex.domain.reader.SyncAnchor
import no.bellaybestia.audex.domain.reader.SyncChapter
import no.bellaybestia.audex.domain.reader.SyncMap
import no.bellaybestia.audex.domain.reader.WordSyncStatus
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val KEY_ALIGN_URL = stringPreferencesKey("align_service_url")
private val KEY_CODEX_URL = stringPreferencesKey("codex_url")

/** Shape of Codex's GET /audex/config discovery response. */
@Serializable
private data class WireAudexConfig(
    @SerialName("word_sync_url") val wordSyncUrl: String = "",
    @SerialName("word_sync_enabled") val wordSyncEnabled: Boolean = false,
)

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

    override suspend fun serviceUrl(): String? =
        context.appSettingsDataStore.data.first()[KEY_ALIGN_URL]?.takeIf { it.isNotBlank() }

    override suspend fun setServiceUrl(url: String?) {
        context.appSettingsDataStore.edit { prefs ->
            val trimmed = url?.trim()?.trimEnd('/')
            if (trimmed.isNullOrBlank()) prefs.remove(KEY_ALIGN_URL) else prefs[KEY_ALIGN_URL] = trimmed
        }
    }

    override suspend fun codexUrl(): String? =
        context.appSettingsDataStore.data.first()[KEY_CODEX_URL]?.takeIf { it.isNotBlank() }

    override suspend fun setCodexUrl(url: String?) {
        context.appSettingsDataStore.edit { prefs ->
            val trimmed = url?.trim()?.trimEnd('/')
            if (trimmed.isNullOrBlank()) prefs.remove(KEY_CODEX_URL) else prefs[KEY_CODEX_URL] = trimmed
        }
    }

    override suspend fun fetchServiceUrlFromCodex(codexUrl: String): Result<String?> =
        withContext(Dispatchers.IO) {
            runCatching {
                var base = codexUrl.trim().trimEnd('/')
                require(base.isNotBlank()) { "Enter your Codex URL first" }
                if (!base.startsWith("http://") && !base.startsWith("https://")) base = "https://$base"
                setCodexUrl(base)
                val request = Request.Builder().url("$base/audex/config").get().build()
                http.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "Codex returned HTTP ${response.code}" }
                    val cfg = json.decodeFromString(
                        WireAudexConfig.serializer(), response.body?.string().orEmpty(),
                    )
                    cfg.wordSyncUrl.trim().trimEnd('/').takeIf { cfg.wordSyncEnabled && it.isNotBlank() }
                }
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
            http.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Alignment service said HTTP ${response.code}" }
            }
            requested.add(bookKey(baseUrl, audioItemId))
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

    private suspend fun baseUrlFor(serverId: String): String? =
        serverDao.enabled().firstOrNull { it.serverId == serverId }?.baseUrl?.trimEnd('/')

    private fun bookKey(baseUrl: String, itemId: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest("$baseUrl|$itemId".toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)
}
