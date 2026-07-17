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
import no.bellaybestia.audex.common.DefaultDispatcher
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

@Singleton
class CodexSyncImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) : CodexSync {

    private val client = OkHttpClient()
    private val json = Json { encodeDefaults = true }
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
}
