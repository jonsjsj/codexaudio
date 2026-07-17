package no.bellaybestia.audex.data

import no.bellaybestia.audex.auth.ServerTokenStore
import no.bellaybestia.audex.auth.ServerTokens
import no.bellaybestia.audex.database.ServerDao
import no.bellaybestia.audex.database.ServerEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TEST-ONLY. Seeds a server + token pair straight into storage, bypassing the
 * interactive OIDC sign-in, so automated UI testing on an emulator can reach a
 * real library without a login form. The token is still AES/GCM-wrapped by the
 * Keystore-backed [ServerTokenStore] on-device — this only supplies the plaintext
 * that a normal login would have supplied.
 *
 * The only caller (MainActivity) gates this to debug builds running on an
 * emulator, so it is inert on a real device. Persistence mirrors
 * [AuthRepositoryImpl.completeLogin] exactly (same [deriveServerId], token save,
 * server upsert, first sync) so a seeded session is indistinguishable from a
 * signed-in one.
 */
@Singleton
class TestSeeder @Inject constructor(
    private val tokenStore: ServerTokenStore,
    private val serverDao: ServerDao,
    private val librarySyncer: LibrarySyncer,
) {
    suspend fun seed(
        baseUrl: String,
        name: String,
        accessToken: String,
        refreshToken: String,
        absUserId: String?,
    ) {
        val normalized = baseUrl.trim().trimEnd('/')
        val serverId = deriveServerId(normalized)
        val userId = absUserId?.ifBlank { null }
        tokenStore.save(ServerTokens(serverId, accessToken, refreshToken, userId))
        serverDao.upsert(
            ServerEntity(
                serverId = serverId,
                name = name,
                baseUrl = normalized,
                absUserId = userId,
                enabled = true,
                needsLogin = false,
            ),
        )
        librarySyncer.syncAll()
    }
}
