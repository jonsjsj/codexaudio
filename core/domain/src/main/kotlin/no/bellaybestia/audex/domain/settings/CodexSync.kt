package no.bellaybestia.audex.domain.settings

import kotlinx.coroutines.flow.Flow
import no.bellaybestia.audex.domain.model.MediaDetail
import no.bellaybestia.audex.domain.model.UpcomingItem

/**
 * Optional push of listening progress to a Codex instance
 * ([[project-codex]]) so it reflects in Codex immediately instead of waiting
 * for Codex's periodic Audiobookshelf pull. Uses Codex's existing ABS webhook
 * (`POST /webhooks/abs?token=`), which maps by the ABS libraryItemId Codex
 * already stores — no item-id mapping needed.
 *
 * [token] is a real Codex API key (Codex → Settings → API keys) — Codex's own
 * webhook validates it against the same table a Bearer `Authorization` header
 * does, so the one key doubles as the credential for reading Codex's own data
 * ([CodexSync.upcomingBooks]) too.
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

    /**
     * Books not yet released, in series/authors you follow on Codex, within
     * [days] — Codex's own `/upcoming` endpoint (the same one Companion's
     * Home/Upcoming screen uses), filtered to books. Null when not configured
     * (see [CodexSyncSettings.canFetchUpcoming]) or the request failed —
     * distinct from a real, empty list.
     */
    suspend fun upcomingBooks(days: Int = 365): List<UpcomingItem>?

    /**
     * Detail for one [UpcomingItem] you tapped, keyed by Codex's `media_id`.
     * Tries Codex's own catalog first (`GET /media/{id}`, no ownership
     * required — it already tracks the release) and only falls back to a
     * direct, public Open Library lookup by [fallbackTitle]/[fallbackAuthor]
     * when Codex has nothing for that id (not configured, 404, or a network
     * failure). Null when both fail.
     */
    suspend fun mediaDetail(mediaId: Int, fallbackTitle: String, fallbackAuthor: String? = null): MediaDetail?
}
