package no.bellaybestia.audex.network.abs

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
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
    private val authCache = ConcurrentHashMap<String, Entry>()

    // One cookie jar per server, shared between the auth client (which seeds the
    // ABS OIDC session + auth_method cookies via GET /auth/openid) and the main
    // client (which spends them on GET /auth/openid/callback). Without this the
    // callback has no session cookie → ABS 400s / never returns the JSON payload.
    private val jars = ConcurrentHashMap<String, CookieJar>()

    private fun jarFor(serverId: String): CookieJar =
        jars.getOrPut(serverId) { InMemoryCookieJar() }

    fun api(serverId: String, baseUrl: String): AbsApi {
        val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val hit = cache[serverId]
        if (hit != null && hit.baseUrl == normalized) return hit.api

        val client = OkHttpClient.Builder()
            .cookieJar(jarFor(serverId))
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

    /**
     * Client for the OIDC mobile-flow initiation (GET /auth/openid). Redirect
     * following is disabled so the 302 Location (the IdP authorization URL we
     * open in the Custom Tab) is readable, and it shares [jarFor] so the session
     * + auth_method cookies ABS sets here are resent on the callback exchange.
     */
    fun authApi(serverId: String, baseUrl: String): AbsApi {
        val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val hit = authCache[serverId]
        if (hit != null && hit.baseUrl == normalized) return hit.api

        val client = OkHttpClient.Builder()
            .cookieJar(jarFor(serverId))
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        val api = Retrofit.Builder()
            .baseUrl(normalized)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AbsApi::class.java)
        authCache[serverId] = Entry(normalized, api)
        return api
    }

    fun evict(serverId: String) {
        cache.remove(serverId)
        authCache.remove(serverId)
        jars.remove(serverId)
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

/**
 * Minimal in-memory cookie jar (host-scoped, last-write-wins per name), enough
 * to carry the ABS OIDC session + auth_method cookies from GET /auth/openid to
 * GET /auth/openid/callback within a login. Not persisted: a login that spans
 * app-process death has to be restarted (the PendingOidcLogin is discarded too).
 */
private class InMemoryCookieJar : CookieJar {
    private val byHost = ConcurrentHashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val list = byHost.getOrPut(url.host) { mutableListOf() }
        synchronized(list) {
            for (cookie in cookies) {
                list.removeAll { it.name == cookie.name }
                list.add(cookie)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val list = byHost[url.host] ?: return emptyList()
        val now = System.currentTimeMillis()
        synchronized(list) {
            list.removeAll { it.expiresAt < now }
            return list.filter { it.matches(url) }
        }
    }
}
