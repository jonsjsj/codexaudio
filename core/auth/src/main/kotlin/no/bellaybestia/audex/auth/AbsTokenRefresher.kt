package no.bellaybestia.audex.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import no.bellaybestia.audex.network.abs.TokenRefresher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single-flight refresh per server against POST {base}/auth/refresh
 * (ABS ≥ 2.26, rotating refresh tokens).
 *
 * Verified against ABS 2.35.1 (server/Auth.js `initAuthRoutes`):
 *  - The refresh token is sent in the `x-refresh-token` REQUEST HEADER, not the
 *    JSON body. Web clients use an httpOnly `refresh_token` cookie instead; a
 *    native client must use the header, and only the header path makes the
 *    server return the rotated refresh token in the response.
 *  - The response is the full login payload; the tokens are nested under `user`
 *    as `user.accessToken` and (header path only) `user.refreshToken`.
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

            // ABS reads the refresh token from the x-refresh-token header (the
            // body is empty). The header path is also what makes the server
            // rotate + return a new refresh token.
            val emptyBody = "".toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl/auth/refresh")
                .addHeader("x-refresh-token", current.refreshToken)
                .post(emptyBody)
                .build()

            runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withLock null
                    val payload = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                    // Tokens are nested under `user` in the login-shaped response.
                    val user = payload["user"]?.jsonObject ?: return@withLock null
                    val access = user["accessToken"]?.jsonPrimitive?.content ?: return@withLock null
                    val rotated = user["refreshToken"]?.jsonPrimitive?.content ?: current.refreshToken
                    // Persist the rotated pair before returning the access token.
                    tokenStore.save(current.copy(accessToken = access, refreshToken = rotated))
                    access
                }
            }.getOrNull()
        }
    }
}
