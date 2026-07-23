package no.bellaybestia.audex.domain.repository

import kotlinx.coroutines.flow.Flow
import no.bellaybestia.audex.domain.model.Episode
import no.bellaybestia.audex.domain.model.Podcast
import no.bellaybestia.audex.domain.model.PodcastFeedPreview
import no.bellaybestia.audex.domain.model.PodcastLibraryTarget
import no.bellaybestia.audex.domain.model.PodcastSearchResult

/**
 * Read/write side for podcasts. The read side observes the Room mirror
 * (offline-first, like the catalog); the write side drives the ABS podcast API
 * — searching, previewing feeds, and creating subscriptions (POST /api/podcasts).
 *
 * Subscribing is a server-wide action: it creates a shared podcast library item
 * and requires an account with upload permission (see [subscribeTargets]).
 */
interface PodcastRepository {

    /** All subscribed podcasts across servers (offline mirror). */
    fun podcasts(): Flow<List<Podcast>>

    fun podcast(serverId: String, libraryItemId: String): Flow<Podcast?>

    /** Episodes of one podcast, newest first, with per-episode progress. */
    fun episodes(serverId: String, libraryItemId: String): Flow<List<Episode>>

    /** Ingest every server's podcast libraries into the Room mirror. */
    suspend fun refresh()

    /** Pull one podcast's episode list + settings from the server. */
    suspend fun refreshPodcast(serverId: String, libraryItemId: String)

    /** Search the server's podcast index by name. */
    suspend fun search(serverId: String, term: String): List<PodcastSearchResult>

    /** Preview a raw RSS feed URL before subscribing. */
    suspend fun previewFeed(serverId: String, feedUrl: String): PodcastFeedPreview?

    /** Podcast libraries the user could subscribe into, across all servers. */
    suspend fun subscribeTargets(): List<PodcastLibraryTarget>

    /**
     * Create a subscription (POST /api/podcasts) into [target], from [feedUrl].
     * With [autoDownload] the ABS server polls the feed and pulls new episodes.
     */
    suspend fun subscribe(
        target: PodcastLibraryTarget,
        feedUrl: String,
        title: String,
        author: String?,
        description: String?,
        imageUrl: String?,
        autoDownload: Boolean,
    ): Result<Unit>

    /** Toggle server-side auto-download for a subscribed podcast. */
    suspend fun setAutoDownload(serverId: String, libraryItemId: String, enabled: Boolean): Result<Unit>

    /** Ask the server to check the feed for new episodes now (best-effort). */
    suspend fun checkNewEpisodes(serverId: String, libraryItemId: String)
}
