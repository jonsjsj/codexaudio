package no.bellaybestia.audex.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The in-flight OIDC login, persisted so it survives the Custom Tab round trip
 * even if the app process is killed while the browser is foregrounded. Only one
 * login is in flight at a time (the user adds one server at a time), so a single
 * slot is enough.
 */
@Serializable
data class PendingOidcLogin(
    val serverId: String,
    val baseUrl: String,
    val name: String,
    val state: String,
    val codeVerifier: String,
    // Serialized (Set-Cookie form) session + auth_method cookies captured from
    // GET /auth/openid. Persisted here so the /auth/openid/callback exchange still
    // has them even if the app process was killed during the Custom Tab (the
    // in-memory cookie jar dies with the process; this survives it).
    val cookies: List<String> = emptyList(),
)

private val Context.pendingOidcDataStore by preferencesDataStore(name = "pending_oidc")
private val KEY = stringPreferencesKey("pending")

@Singleton
class PendingOidcStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun save(login: PendingOidcLogin) {
        context.pendingOidcDataStore.edit { it[KEY] = json.encodeToString(PendingOidcLogin.serializer(), login) }
    }

    suspend fun load(): PendingOidcLogin? {
        val raw = context.pendingOidcDataStore.data.first()[KEY] ?: return null
        return runCatching { json.decodeFromString(PendingOidcLogin.serializer(), raw) }.getOrNull()
    }

    suspend fun clear() {
        context.pendingOidcDataStore.edit { it.remove(KEY) }
    }
}
