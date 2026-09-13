package no.bellaybestia.audex.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import no.bellaybestia.audex.common.DefaultDispatcher
import no.bellaybestia.audex.domain.model.MediaDetail
import no.bellaybestia.audex.domain.model.UpcomingItem
import no.bellaybestia.audex.domain.settings.CodexSync
import no.bellaybestia.audex.domain.settings.CodexSyncSettings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

private val KEY_CODEX_URL = stringPreferencesKey("codex_sync_url")
private val KEY_CODEX_TOKEN = stringPreferencesKey("codex_sync_token")
private val KEY_CODEX_ENABLED = booleanPreferencesKey("codex_sync_enabled")

@Serializable private data class WireProgress(val libraryItemId: String, val currentTime: Double, val isFinished: Boolean)
@Serializable private data class WireData(val progress: WireProgress)
@Serializable private data class WireEvent(val event: String, val data: WireData)

@Serializable
private data class WireUpcoming(
    val media_id: Int,
    val title: String,
    val type: String? = null,
    val cover_url: String? = null,
    val release_date: String? = null,
)

// Mirrors Codex's MediaOut schema (GET /media/{id}) — only the fields the
// detail screen actually shows are declared; the rest are ignored via
// ignoreUnknownKeys.
@Serializable
private data class WireMediaDetail(
    val id: Int,
    val type: String? = null,
    val title: String,
    val cover_url: String? = null,
    val description: String? = null,
    val author: String? = null,
    val narrator: String? = null,
    val series_name: String? = null,
    val series_position: Double? = null,
    val release_date: String? = null,
    val year: Int? = null,
    val genres: String? = null,
)

@Singleton
class CodexSyncImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) : CodexSync {

    private val client = OkHttpClient()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val jsonMedia = "application/json".toMediaType()

    override val settings: Flow<CodexSyncSettings> =
        context.appSettingsDataStore.data.map { p ->
            CodexSyncSettings(
                url = p[KEY_CODEX_URL].orEmpty(),
                token = p[KEY_CODEX_TOKEN].orEmpty(),
                enabled = p[KEY_CODEX_ENABLED] ?: false,
            )
        }

    override suspend fun setSettings(url: String, token: String, enabled: Boolean) {
        context.appSettingsDataStore.edit { prefs ->
            prefs[KEY_CODEX_URL] = url.trim()
            prefs[KEY_CODEX_TOKEN] = token.trim()
            prefs[KEY_CODEX_ENABLED] = enabled
        }
    }

    override suspend fun pushAudioProgress(
        libraryItemId: String,
        currentTimeS: Double,
        isFinished: Boolean,
    ) = withContext(dispatcher) {
        val s = settings.first()
        if (!s.isConfigured) return@withContext
        val base = s.url.trim().trimEnd('/')
        val payload = json.encodeToString(
            WireEvent.serializer(),
            WireEvent("user_mediaProgressUpdated", WireData(WireProgress(libraryItemId, currentTimeS, isFinished))),
        )
        val request = Request.Builder()
            .url("$base/webhooks/abs?token=${s.token.trim()}")
            .post(payload.toRequestBody(jsonMedia))
            .build()
        // Best-effort: a Codex outage must never disrupt playback or ABS sync.
        runCatching { client.newCall(request).execute().use { } }
        Unit
    }

    override suspend fun upcomingBooks(days: Int): List<UpcomingItem>? = withContext(dispatcher) {
        val s = settings.first()
        if (!s.isConfigured) return@withContext null
        val base = s.url.trim().trimEnd('/')
        val request = Request.Builder()
            .url("$base/upcoming?days=$days&type=book")
            .header("Authorization", "Bearer ${s.token.trim()}")
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(WireUpcoming.serializer()), body)
                    .map {
                        UpcomingItem(
                            mediaId = it.media_id,
                            title = it.title,
                            coverUrl = it.cover_url?.let { c -> absoluteCodexUrl(base, c) },
                            releaseDate = it.release_date,
                        )
                    }
            }
        }.getOrNull()
    }

    override suspend fun mediaDetail(
        mediaId: Int,
        fallbackTitle: String,
        fallbackAuthor: String?,
    ): MediaDetail? = withContext(dispatcher) {
        fetchCodexMediaDetail(mediaId) ?: fetchPublicMediaDetail(fallbackTitle, fallbackAuthor)
    }

    private suspend fun fetchCodexMediaDetail(mediaId: Int): MediaDetail? {
        val s = settings.first()
        if (!s.isConfigured) return null
        val base = s.url.trim().trimEnd('/')
        val request = Request.Builder()
            .url("$base/media/$mediaId")
            .header("Authorization", "Bearer ${s.token.trim()}")
            .get()
            .build()
        return runCatching {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string() ?: return@use null
                val w = json.decodeFromString(WireMediaDetail.serializer(), body)
                MediaDetail(
                    mediaId = w.id,
                    title = w.title,
                    type = w.type,
                    author = w.author,
                    narrator = w.narrator,
                    seriesName = w.series_name,
                    seriesPosition = w.series_position,
                    coverUrl = w.cover_url?.let { absoluteCodexUrl(base, it) },
                    description = w.description?.takeIf { it.isNotBlank() },
                    releaseDate = w.release_date,
                    year = w.year,
                    genres = w.genres,
                    source = "codex",
                )
            }
        }.getOrNull()
    }

    /** Codex had nothing for this id (not configured, 404, network failure) —
     *  look the book up directly from Open Library's public, keyless API by
     *  title (+author when known) instead. Best-effort: any failure here just
     *  means the detail screen falls back to the nav-arg title/cover it
     *  already had. */
    private suspend fun fetchPublicMediaDetail(title: String, author: String?): MediaDetail? = runCatching {
        var q = "title=${java.net.URLEncoder.encode(title, "UTF-8")}"
        if (!author.isNullOrBlank()) q += "&author=${java.net.URLEncoder.encode(author, "UTF-8")}"
        val searchRequest = Request.Builder()
            .url("https://openlibrary.org/search.json?$q&limit=1")
            .get()
            .build()
        val first = client.newCall(searchRequest).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            val body = resp.body?.string() ?: return@runCatching null
            json.parseToJsonElement(body).jsonObject["docs"]
                ?.jsonArray?.firstOrNull()?.jsonObject
        } ?: return@runCatching null

        val workKey = first["key"]?.jsonPrimitive?.contentOrNull
        val coverId = first["cover_i"]?.jsonPrimitive?.intOrNull
        val year = first["first_publish_year"]?.jsonPrimitive?.intOrNull
        val authorName = first["author_name"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull

        val description = workKey?.let { key ->
            runCatching {
                val workRequest = Request.Builder().url("https://openlibrary.org$key.json").get().build()
                client.newCall(workRequest).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body?.string() ?: return@use null
                    val d = json.parseToJsonElement(body).jsonObject["description"]
                    d?.jsonPrimitive?.contentOrNull ?: d?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull
                }
            }.getOrNull()
        }

        MediaDetail(
            mediaId = -1,
            title = title,
            type = null,
            author = authorName ?: author,
            narrator = null,
            seriesName = null,
            seriesPosition = null,
            coverUrl = coverId?.let { "https://covers.openlibrary.org/b/id/$it-L.jpg" },
            description = description,
            releaseDate = null,
            year = year,
            genres = null,
            source = "public",
        )
    }.getOrNull()

    /** Codex's own cover paths ("/media/2340/poster", a MediaCoverProxy path) are
     *  relative to the Codex host; an already-absolute URL (e.g. openlibrary.org,
     *  for a manually-pinned title) is used as-is. */
    private fun absoluteCodexUrl(base: String, path: String): String =
        if (path.startsWith("http://") || path.startsWith("https://")) path
        else base + (if (path.startsWith("/")) path else "/$path")
}
