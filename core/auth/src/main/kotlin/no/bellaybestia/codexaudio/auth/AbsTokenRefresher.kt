package no.bellaybestia.codexaudio.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import no.bellaybestia.codexaudio.network.abs.TokenRefresher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single-flight refresh per server against POST {base}/auth/refresh
 * (ABS ≥ 2.26, rotating refresh tokens — request/response shape is
 * [verify]-flagged in docs/03 §3.1).
 *
 * Rotation rule: the new refresh token is persisted BEFORE the new access
 * token is handed back to any caller — losing a rotated refresh token means
 * forced re-login. On refresh failure the server is left for the UI to mark
 * "needs login"; the cached graph stays browsable offline.
 */
@Singleton
class AbsTokenRefresher @Inject constructor(
    private val tokenStore: ServerTokenStore,
) : TokenRefresher {

    /** Resolves a server's base URL; wired by :core:data from the server registry. */
    fun interface BaseUrlResolver {
        suspend fun baseUrl(serverId: String): String?
    }

    var baseUrlResolver: BaseUrlResolver = BaseUrlResolver { null }

    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val locks = ConcurrentHashMap<String, Mutex>()

    override suspend fun refresh(serverId: String): String? {
        val mutex = locks.computeIfAbsent(serverId) { Mutex() }
        return mutex.withLock {
            val current = tokenStore.tokens(serverId) ?: return@withLock null
            val baseUrl = baseUrlResolver.baseUrl(serverId)?.trimEnd('/') ?: return@withLock null

            val body = """{"refreshToken":"${current.refreshToken}"}"""
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl/auth/refresh")
                .post(body)
                .build()

            runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withLock null
                    val payload = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                    val access = payload["accessToken"]?.jsonPrimitive?.content ?: return@withLock null
                    val rotated = payload["refreshToken"]?.jsonPrimitive?.content ?: current.refreshToken
                    // Persist the rotated pair before returning the access token.
                    tokenStore.save(current.copy(accessToken = access, refreshToken = rotated))
                    access
                }
            }.getOrNull()
        }
    }
}
