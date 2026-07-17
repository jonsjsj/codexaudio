package no.bellaybestia.audex.domain.settings

import kotlinx.coroutines.flow.Flow

/**
 * Optional push of listening progress to a Codex instance
 * ([[project-codex]]) so it reflects in Codex immediately instead of waiting
 * for Codex's periodic Audiobookshelf pull. Uses Codex's existing ABS webhook
 * (`POST /webhooks/abs?token=`), which maps by the ABS libraryItemId Codex
 * already stores — no item-id mapping needed.
 */
data class CodexSyncSettings(
    val url: String = "",
    val token: String = "",
    val enabled: Boolean = false,
) {
    val isConfigured: Boolean get() = enabled && url.isNotBlank() && token.isNotBlank()
}

interface CodexSync {
    val settings: Flow<CodexSyncSettings>
    suspend fun setSettings(url: String, token: String, enabled: Boolean)

    /** Best-effort push of an audiobook's progress. No-op when not configured. */
    suspend fun pushAudioProgress(libraryItemId: String, currentTimeS: Double, isFinished: Boolean)
}
