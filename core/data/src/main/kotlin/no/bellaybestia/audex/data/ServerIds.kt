package no.bellaybestia.audex.data

import java.util.UUID

/**
 * Deterministic server id from the base URL, so re-adding a server keeps its
 * editions, progress rows and downloads attached. Shared by the login flow and
 * the manual add path so both agree.
 */
internal fun deriveServerId(baseUrl: String): String {
    val normalized = baseUrl.trim().trimEnd('/').lowercase()
    return UUID.nameUUIDFromBytes(normalized.toByteArray()).toString()
}
