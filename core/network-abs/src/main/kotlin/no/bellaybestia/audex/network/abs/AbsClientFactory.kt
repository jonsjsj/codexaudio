package no.bellaybestia.audex.network.abs

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Route
import retrofit2.Retrofit
import java.util.concurrent.ConcurrentHashMap

/** Supplies the current access token for a server; implemented by :core:auth. */
fun interface TokenProvider {
    fun accessToken(serverId: String): String?
}

/**
 * Attempts a refresh when a request 401s and returns the new access token
 * (null → give up, surface "needs login"). Implemented by :core:auth with
 * single-flight rotation-safe semantics.
 */
fun interface TokenRefresher {
    suspend fun refresh(serverId: String): String?
}

/**
 * One Retrofit/OkHttp stack per connected ABS server, cached by serverId and
 * rebuilt when the base URL changes. Tokens are attached per request so a
 * rotation never requires a client rebuild.
 */
class AbsClientFactory(
    private val tokenProvider: TokenProvider,
    private val tokenRefresher: TokenRefresher,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    private data class Entry(val baseUrl: String, val api: AbsApi)

    private val cache = ConcurrentHashMap<String, Entry>()

    fun api(serverId: String, baseUrl: String): AbsApi {
        val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val hit = cache[serverId]
        if (hit != null && hit.baseUrl == normalized) return hit.api

        val client = OkHttpClient.Builder()
            .addInterceptor(authInterceptor(serverId))
            .authenticator(refreshAuthenticator(serverId))
            .build()
        val api = Retrofit.Builder()
            .baseUrl(normalized)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AbsApi::class.java)
        cache[serverId] = Entry(normalized, api)
        return api
    }

    fun evict(serverId: String) {
        cache.remove(serverId)
    }

    private fun authInterceptor(serverId: String) = Interceptor { chain ->
        val token = tokenProvider.accessToken(serverId)
        val request = if (token.isNullOrBlank()) chain.request() else {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        }
        chain.proceed(request)
    }

    /**
     * On a 401, refresh once (single-flight + rotation-safe in :core:auth) and
     * retry with the new access token. Returning null gives up, so the caller
     * sees the 401 and the UI can mark the server "needs login". OkHttp calls
     * this off the main thread, so blocking on the suspend refresh is fine.
     */
    private fun refreshAuthenticator(serverId: String) = Authenticator { _: Route?, response: Response ->
        if (responseCount(response) >= 2) return@Authenticator null // already retried once
        val newToken = runBlocking { tokenRefresher.refresh(serverId) } ?: return@Authenticator null
        // Don't loop if the token we'd send is the one that just failed.
        val sent = response.request.header("Authorization")
        if (sent == "Bearer $newToken") return@Authenticator null
        response.request.newBuilder()
            .header("Authorization", "Bearer $newToken")
            .build()
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior: Response? = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
