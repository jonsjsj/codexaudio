package no.bellaybestia.audex.domain.repository

import kotlinx.coroutines.flow.StateFlow
import no.bellaybestia.audex.domain.model.ServerAccount

/** Result of probing a server URL before login (public GET /status). */
data class ServerProbe(
    val version: String,
    val hasOpenId: Boolean,
    val meetsMinVersion: Boolean,
)

/** Observable state of the add-server / OIDC login flow. */
sealed interface LoginStatus {
    data object Idle : LoginStatus
    /** Probing the URL. */
    data object Connecting : LoginStatus
    /** Ready — the UI should open [authorizationUrl] in a Custom Tab. */
    data class AwaitingBrowser(val authorizationUrl: String) : LoginStatus
    /** Redirect caught; exchanging the code for tokens. */
    data object Completing : LoginStatus
    data class Success(val server: ServerAccount) : LoginStatus
    data class Error(val message: String) : LoginStatus
}

/**
 * Drives multi-server OIDC login (native ABS mobile flow via Custom Tabs).
 * The UI only reads [loginStatus] and opens the URL it is handed; all crypto,
 * token exchange, and persistence live in :core:data / :core:auth.
 */
interface AuthRepository {
    val loginStatus: StateFlow<LoginStatus>

    /** Probe [baseUrl] without side effects (version + auth methods). */
    suspend fun probe(baseUrl: String): Result<ServerProbe>

    /**
     * Begin login: probe, generate PKCE + state, persist the pending session,
     * and move [loginStatus] to [LoginStatus.AwaitingBrowser] with the URL to open
     * (or [LoginStatus.Error]).
     */
    suspend fun beginLogin(baseUrl: String, name: String)

    /**
     * Complete login from the `audex://oauth?...` redirect: validate state,
     * exchange the code, persist tokens + the server, and kick off a first sync.
     */
    suspend fun completeLogin(callbackUrl: String)

    /** Return the flow to [LoginStatus.Idle] (e.g. after the UI consumes a result). */
    fun resetLogin()
}
