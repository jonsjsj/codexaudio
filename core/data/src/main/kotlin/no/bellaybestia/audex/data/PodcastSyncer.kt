package no.bellaybestia.audex.data

import no.bellaybestia.audex.database.EpisodeDao
import no.bellaybestia.audex.database.EpisodeEntity
import no.bellaybestia.audex.database.EpisodeProgressDao
import no.bellaybestia.audex.database.EpisodeProgressEntity
import no.bellaybestia.audex.database.PodcastDao
import no.bellaybestia.audex.database.PodcastEntity
import no.bellaybestia.audex.database.ServerDao
import no.bellaybestia.audex.network.abs.AbsApi
import no.bellaybestia.audex.network.abs.AbsLibraryItem
import no.bellaybestia.audex.network.abs.AbsMediaProgress
import no.bellaybestia.audex.network.abs.AbsPodcastEpisode
import no.bellaybestia.audex.network.abs.AbsClientFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ingests every enabled server's PODCAST libraries into the podcast tables and
 * reconciles per-episode progress from GET /api/me. Deliberately parallel to
 * [LibrarySyncer] and independent of the catalog graph — it never calls
 * rebuildGraph (podcasts are not works/editions). Per-server errors are isolated.
 */
@Singleton
class PodcastSyncer @Inject constructor(
    private val serverDao: ServerDao,
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
    private val episodeProgressDao: EpisodeProgressDao,
    private val clientFactory: AbsClientFactory,
) {

    suspend fun syncAll() {
        for (server in serverDao.enabled()) {
            runCatching { syncServer(server.serverId, server.baseUrl) }
        }
    }

    private suspend fun syncServer(serverId: String, baseUrl: String) {
        val api = clientFactory.api(serverId, baseUrl)
        val seen = mutableListOf<String>()
        for (library in api.libraries().libraries.filter { it.mediaType == "podcast" }) {
            var page = 0
            while (true) {
                val batch = api.libraryItems(library.id, limit = 100, page = page)
                if (batch.results.isEmpty()) break
                for (item in batch.results) {
                    seen += item.id
                    // The list projection is thin for podcasts; the expanded detail
                    // carries episodes + the subscription settings we need.
                    runCatching { syncOne(api, serverId, library.id, item.id) }
                        .onFailure { podcastDao.upsertAll(listOf(item.toPodcastEntity(serverId, library.id))) }
                }
                page++
                if (batch.limit == 0 || (page * batch.limit) >= batch.total && batch.total > 0) break
            }
        }
        if (seen.isNotEmpty()) podcastDao.pruneMissing(serverId, seen)
        reconcileEpisodeProgress(serverId, runCatching { api.me().mediaProgress }.getOrDefault(emptyList()))
    }

    /** Pull one podcast's detail (episodes + settings) and upsert it. */
    suspend fun syncOne(serverId: String, libraryItemId: String) {
        val server = serverDao.enabled().firstOrNull { it.serverId == serverId } ?: return
        val api = clientFactory.api(serverId, server.baseUrl)
        val existingLib = podcastDao.get(serverId, libraryItemId)?.libraryId
        runCatching { syncOne(api, serverId, existingLib.orEmpty(), libraryItemId) }
        reconcileEpisodeProgress(serverId, runCatching { api.me().mediaProgress }.getOrDefault(emptyList()))
    }

    private suspend fun syncOne(api: AbsApi, serverId: String, libraryId: String, itemId: String) {
        val item = api.item(itemId)
        val lib = libraryId.ifBlank { item.libraryId }
        podcastDao.upsertAll(listOf(item.toPodcastEntity(serverId, lib)))
        val episodes = item.media.episodes.map { it.toEntity(serverId, itemId) }
        if (episodes.isNotEmpty()) {
            // Replace so removed episodes (retention pruning on the server) drop.
            episodeDao.deleteForPodcast(serverId, itemId)
            episodeDao.upsertAll(episodes)
        }
    }

    /** Reconcile episode progress from a server's mediaProgress array (episodeId set). */
    suspend fun reconcileEpisodeProgress(serverId: String) {
        val server = serverDao.enabled().firstOrNull { it.serverId == serverId } ?: return
        val api = clientFactory.api(serverId, server.baseUrl)
        reconcileEpisodeProgress(serverId, runCatching { api.me().mediaProgress }.getOrDefault(emptyList()))
    }

    private suspend fun reconcileEpisodeProgress(serverId: String, mediaProgress: List<AbsMediaProgress>) {
        val incoming = mediaProgress
            .filter { !it.episodeId.isNullOrBlank() }
            .map { mp ->
                EpisodeProgressEntity(
                    serverId = serverId,
                    libraryItemId = mp.libraryItemId,
                    episodeId = mp.episodeId!!,
                    pct = if (mp.isFinished) 1.0 else mp.progress,
                    currentTimeS = mp.currentTime,
                    isFinished = mp.isFinished,
                    lastUpdate = mp.lastUpdate,
                    source = "SERVER",
                )
            }
        if (incoming.isEmpty()) return
        // Local wins on ties (second-accurate live position), same rule as books.
        val merged = incoming.map { srv ->
            val local = episodeProgressDao.get(serverId, srv.libraryItemId, srv.episodeId)
            if (local != null && local.source.startsWith("LOCAL") && local.lastUpdate >= srv.lastUpdate) local else srv
        }
        episodeProgressDao.upsertAll(merged)
    }
}

internal fun AbsLibraryItem.toPodcastEntity(serverId: String, libraryId: String): PodcastEntity {
    val md = media.metadata
    return PodcastEntity(
        serverId = serverId,
        libraryItemId = id,
        libraryId = libraryId,
        title = md.title.orEmpty().ifBlank { "(untitled)" },
        author = md.author,
        description = md.description,
        feedUrl = md.feedUrl,
        autoDownload = media.autoDownloadEpisodes,
        autoDownloadSchedule = media.autoDownloadSchedule,
        maxEpisodesToKeep = media.maxEpisodesToKeep,
        numEpisodes = if (media.numEpisodes > 0) media.numEpisodes else media.episodes.size,
        updatedAtRemote = updatedAt,
    )
}

internal fun AbsPodcastEpisode.toEntity(serverId: String, libraryItemId: String): EpisodeEntity =
    EpisodeEntity(
        serverId = serverId,
        libraryItemId = libraryItemId,
        episodeId = id,
        title = title.ifBlank { "(untitled episode)" },
        subtitle = subtitle,
        description = description,
        pubDate = pubDate,
        publishedAt = publishedAt,
        durationS = durationS,
        sizeBytes = size,
        season = season,
        episodeNum = episode,
        idx = index,
    )
