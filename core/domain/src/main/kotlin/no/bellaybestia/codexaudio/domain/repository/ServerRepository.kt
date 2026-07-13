package no.bellaybestia.codexaudio.domain.repository

import kotlinx.coroutines.flow.Flow
import no.bellaybestia.codexaudio.domain.model.ServerAccount

interface ServerRepository {
    fun servers(): Flow<List<ServerAccount>>
    suspend fun addServer(name: String, baseUrl: String): ServerAccount
    suspend fun removeServer(serverId: String)
}
