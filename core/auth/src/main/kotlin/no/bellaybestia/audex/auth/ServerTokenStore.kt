package no.bellaybestia.audex.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.bellaybestia.audex.network.abs.TokenProvider
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class ServerTokens(
    val serverId: String,
    val accessToken: String,
    val refreshToken: String,
    val absUserId: String? = null,
)

private val Context.tokenDataStore by preferencesDataStore(name = "server_tokens")

/**
 * Per-server {access, refresh} pairs, AES/GCM-wrapped (Android Keystore) before
 * hitting DataStore. Writes are serialized behind a mutex because ABS rotates
 * the refresh token on every refresh — a lost rotated token means forced
 * re-login, so persist-before-use is the rule (docs/04 §4.6).
 */
@Singleton
class ServerTokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : TokenProvider {

    private val crypto = KeystoreCrypto()
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    // In-memory mirror so the OkHttp interceptor never blocks on DataStore.
    @Volatile
    private var cache: Map<String, ServerTokens> = emptyMap()

    suspend fun load() {
        val prefs = context.tokenDataStore.data.first()
        cache = prefs.asMap().mapNotNull { (key, value) ->
            val decrypted = crypto.decrypt(value.toString()) ?: return@mapNotNull null
            runCatching { json.decodeFromString<ServerTokens>(decrypted) }.getOrNull()
                ?.let { key.name to it }
        }.toMap()
    }

    override fun accessToken(serverId: String): String? = cache[serverId]?.accessToken

    fun tokens(serverId: String): ServerTokens? = cache[serverId]

    suspend fun save(tokens: ServerTokens) {
        mutex.withLock {
            context.tokenDataStore.edit { prefs ->
                prefs[stringPreferencesKey(tokens.serverId)] =
                    crypto.encrypt(json.encodeToString(ServerTokens.serializer(), tokens))
            }
            cache = cache + (tokens.serverId to tokens)
        }
    }

    suspend fun clear(serverId: String) {
        mutex.withLock {
            context.tokenDataStore.edit { it.remove(stringPreferencesKey(serverId)) }
            cache = cache - serverId
        }
    }
}
