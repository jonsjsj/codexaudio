package no.bellaybestia.audex.data

import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import no.bellaybestia.audex.auth.AbsOidcFlow
import no.bellaybestia.audex.auth.PendingOidcLogin
import no.bellaybestia.audex.auth.PendingOidcStore
import no.bellaybestia.audex.auth.Pkce
import no.bellaybestia.audex.auth.ServerTokenStore
import no.bellaybestia.audex.auth.ServerTokens
import no.bellaybestia.audex.common.DefaultDispatcher
import no.bellaybestia.audex.database.ServerDao
import no.bellaybestia.audex.database.ServerEntity
import no.bellaybestia.audex.domain.model.ServerAccount
import no.bellaybestia.audex.domain.repository.AuthRepository
import no.bellaybestia.audex.domain.repository.LoginStatus
import no.bellaybestia.audex.domain.repository.ServerProbe
import no.bellaybestia.audex.network.abs.AbsClientFactory
import javax.inject.Inject
import javax.inject.Singleton

private const val MIN_MAJOR = 2
private const val MIN_MINOR = 26

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val clientFactory: AbsClientFactory,
    private val tokenStore: ServerTokenStore,
    private val pendingStore: PendingOidcStore,
    private val serverDao: ServerDao,
    private val librarySyncer: LibrarySyncer,
    @DefaultDispatcher dispatcher: CoroutineDispatcher,
) : AuthRepository {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _loginStatus = MutableStateFlow<LoginStatus>(LoginStatus.Idle)
    override val loginStatus: StateFlow<LoginStatus> = _loginStatus.asStateFlow()

    override suspend fun probe(baseUrl: String): Result<ServerProbe> {
        val normalized = baseUrl.trim().trimEnd('/')
        val serverId = deriveServerId(normalized)
        return runCatching {
            val status = clientFactory.api(serverId, normalized).status()
            ServerProbe(
                version = status.serverVersion,
                hasOpenId = status.authMethods.any { it.equals("openid", ignoreCase = true) },
                meetsMinVersion = meetsMinVersion(status.serverVersion),
            )
        }
    }

    override suspend fun beginLogin(baseUrl: String, name: String) {
        _loginStatus.value = LoginStatus.Connecting
        val normalized = baseUrl.trim().trimEnd('/')
        if (normalized.isBlank()) {
            _loginStatus.value = LoginStatus.Error("Enter a server URL.")
            return
        }
        val probe = probe(normalized).getOrElse {
            _loginStatus.value = LoginStatus.Error("Couldn't reach $normalized. Check the URL and your connection.")
            return
        }
        when {
            !probe.hasOpenId ->
                _loginStatus.value = LoginStatus.Error("This server doesn't have OpenID (Authentik) login enabled.")
            !probe.meetsMinVersion ->
                _loginStatus.value = LoginStatus.Error("Audex needs Audiobookshelf 2.26 or newer (server is ${probe.version}).")
            else -> {
                val serverId = deriveServerId(normalized)
                val verifier = Pkce.newVerifier()
                val state = Pkce.newState()
                pendingStore.save(
                    PendingOidcLogin(
                        serverId = serverId,
                        baseUrl = normalized,
                        name = name.trim().ifBlank { Uri.parse(normalized).host ?: normalized },
                        state = state,
                        codeVerifier = verifier,
                    )
                )
                val url = AbsOidcFlow.authorizationUrl(normalized, state, Pkce.challenge(verifier)).toString()
                _loginStatus.value = LoginStatus.AwaitingBrowser(url)
            }
        }
    }

    override suspend fun completeLogin(callbackUrl: String) {
        _loginStatus.value = LoginStatus.Completing
        val callback = AbsOidcFlow.parseCallback(Uri.parse(callbackUrl))
        if (callback == null) {
            fail("That didn't look like a valid login response.")
            return
        }
        val pending = pendingStore.load()
        if (pending == null) {
            fail("No login was in progress. Please start again.")
            return
        }
        if (callback.state != pending.state) {
            fail("Login could not be verified (state mismatch). Please try again.")
            return
        }
        val result = runCatching {
            clientFactory.api(pending.serverId, pending.baseUrl)
                .oidcCallback(callback.code, callback.state, pending.codeVerifier)
        }.getOrElse {
            fail("Sign-in failed while finishing on the server. Please try again.")
            return
        }
        val access = result.user.accessToken
        if (access.isNullOrBlank()) {
            fail("The server didn't return an access token.")
            return
        }
        tokenStore.save(
            ServerTokens(
                serverId = pending.serverId,
                accessToken = access,
                refreshToken = result.user.refreshToken.orEmpty(),
                absUserId = result.user.id.ifBlank { null },
            )
        )
        serverDao.upsert(
            ServerEntity(
                serverId = pending.serverId,
                name = pending.name,
                baseUrl = pending.baseUrl,
                absUserId = result.user.id.ifBlank { null },
                enabled = true,
                needsLogin = false,
            )
        )
        pendingStore.clear()
        // First sync in the background — don't block the success signal on it.
        scope.launch { runCatching { librarySyncer.syncAll() } }
        _loginStatus.value = LoginStatus.Success(
            ServerAccount(pending.serverId, pending.name, pending.baseUrl)
        )
    }

    override fun resetLogin() {
        _loginStatus.value = LoginStatus.Idle
    }

    private suspend fun fail(message: String) {
        pendingStore.clear()
        _loginStatus.value = LoginStatus.Error(message)
    }

    private fun meetsMinVersion(version: String): Boolean {
        val parts = version.split('.', '-').mapNotNull { it.toIntOrNull() }
        val major = parts.getOrElse(0) { 0 }
        val minor = parts.getOrElse(1) { 0 }
        return major > MIN_MAJOR || (major == MIN_MAJOR && minor >= MIN_MINOR)
    }
}
