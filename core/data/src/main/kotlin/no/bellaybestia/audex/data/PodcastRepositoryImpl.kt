package no.bellaybestia.audex.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import no.bellaybestia.audex.common.DefaultDispatcher
import no.bellaybestia.audex.database.EpisodeDao
import no.bellaybestia.audex.database.EpisodeWithProgress
import no.bellaybestia.audex.database.PodcastDao
import no.bellaybestia.audex.database.PodcastEntity
import no.bellaybestia.audex.database.ServerDao
import no.bellaybestia.audex.domain.model.Episode
import no.bellaybestia.audex.domain.model.FeedEpisode
import no.bellaybestia.audex.domain.model.Podcast
import no.bellaybestia.audex.domain.model.PodcastFeedPreview
import no.bellaybestia.audex.domain.model.PodcastLibraryTarget
import no.bellaybestia.audex.domain.model.PodcastSearchResult
import no.bellaybestia.audex.domain.model.absCoverUrl
import no.bellaybestia.audex.domain.repository.PodcastRepository
import no.bellaybestia.audex.network.abs.AbsClientFactory
import no.bellaybestia.audex.network.abs.AbsPodcastCreateMedia
import no.bellaybestia.audex.network.abs.AbsPodcastCreateMetadata
import no.bellaybestia.audex.network.abs.AbsPodcastCreateRequest
import no.bellaybestia.audex.network.abs.AbsPodcastFeedRequest
import no.bellaybestia.audex.network.abs.AbsPodcastSettingsBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read side over the Room podcast tables; write side drives the ABS podcast API
 * (search, feed preview, subscribe, settings) via [PodcastSyncer] for ingest.
 * Subscribing is the server-wide "add a podcast" action — it needs a podcast
 * library with a folder and an account with upload permission.
 */
@Singleton
class PodcastRepositoryImpl @Inject constructor(
    private val serverDao: ServerDao,
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
    private val clientFactory: AbsClientFactory,
    private val syncer: PodcastSyncer,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) : PodcastRepository {

    private val baseUrls: Flow<Map<String, String>> =
        serverDao.observeAll().map { rows -> rows.associate { it.serverId to it.baseUrl } }

    override fun podcasts(): Flow<List<Podcast>> =
        combine(podcastDao.observeAll(), baseUrls) { rows, urls -> rows.map { it.toDomain(urls) } }

    override fun podcast(serverId: String, libraryItemId: String): Flow<Podcast?> =
        combine(podcastDao.observe(serverId, libraryItemId), baseUrls) { row, urls -> row?.toDomain(urls) }

    override fun episodes(serverId: String, libraryItemId: String): Flow<List<Episode>> =
        episodeDao.observeForPodcast(serverId, libraryItemId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun refresh() = withContext(dispatcher) { syncer.syncAll() }

    override suspend fun refreshPodcast(serverId: String, libraryItemId: String) =
        withContext(dispatcher) { syncer.syncOne(serverId, libraryItemId) }

    override suspend fun search(serverId: String, term: String): List<PodcastSearchResult> =
        withContext(dispatcher) {
            val server = serverDao.enabled().firstOrNull { it.serverId == serverId } ?: return@withContext emptyList()
            runCatching {
                clientFactory.api(serverId, server.baseUrl).searchPodcasts(term).map { r ->
                    PodcastSearchResult(
                        title = r.title,
                        author = r.artistName,
                        description = r.descriptionPlain ?: r.description,
                        coverUrl = r.cover?.takeIf { it.isNotBlank() },
                        feedUrl = r.feedUrl,
                        numEpisodes = r.trackCount,
                    )
                }.filter { it.feedUrl.isNotBlank() }
            }.getOrDefault(emptyList())
        }

    override suspend fun previewFeed(serverId: String, feedUrl: String): PodcastFeedPreview? =
        withContext(dispatcher) {
            val server = serverDao.enabled().firstOrNull { it.serverId == serverId } ?: return@withContext null
            runCatching {
                val feed = clientFactory.api(serverId, server.baseUrl)
                    .getPodcastFeed(AbsPodcastFeedRequest(feedUrl)).podcast
                val md = feed.metadata
                PodcastFeedPreview(
                    title = md.title.orEmpty().ifBlank { "Podcast" },
                    author = md.author,
                    description = md.descriptionPlain ?: md.description,
                    imageUrl = md.imageUrl,
                    feedUrl = md.feedUrl ?: feedUrl,
                    numEpisodes = if (feed.numEpisodes > 0) feed.numEpisodes else feed.episodes.size,
                    recentEpisodes = feed.episodes.take(20).map {
                        FeedEpisode(
                            title = it.title.orEmpty().ifBlank { "(untitled episode)" },
                            pubDate = it.pubDate,
                            publishedAt = it.publishedAt,
                        )
                    },
                )
            }.getOrNull()
        }

    override suspend fun subscribeTargets(): List<PodcastLibraryTarget> = withContext(dispatcher) {
        serverDao.enabled().flatMap { server ->
            runCatching {
                val api = clientFactory.api(server.serverId, server.baseUrl)
                val canUpload = runCatching { api.me().canUpload }.getOrDefault(false)
                api.libraries().libraries
                    .filter { it.mediaType == "podcast" }
                    .mapNotNull { lib ->
                        val folder = lib.folders.firstOrNull { it.fullPath.isNotBlank() } ?: return@mapNotNull null
                        PodcastLibraryTarget(
                            serverId = server.serverId,
                            serverName = server.name,
                            libraryId = lib.id,
                            libraryName = lib.name,
                            folderId = folder.id,
                            folderPath = folder.fullPath,
                            canSubscribe = canUpload,
                        )
                    }
            }.getOrDefault(emptyList())
        }
    }

    override suspend fun subscribe(
        target: PodcastLibraryTarget,
        feedUrl: String,
        title: String,
        author: String?,
        description: String?,
        imageUrl: String?,
        autoDownload: Boolean,
    ): Result<Unit> = withContext(dispatcher) {
        val server = serverDao.enabled().firstOrNull { it.serverId == target.serverId }
            ?: return@withContext Result.failure(IllegalStateException("Server not connected."))
        val path = target.folderPath.trimEnd('/') + "/" + sanitizeFolderName(title)
        val body = AbsPodcastCreateRequest(
            libraryId = target.libraryId,
            folderId = target.folderId,
            path = path,
            media = AbsPodcastCreateMedia(
                metadata = AbsPodcastCreateMetadata(
                    title = title,
                    author = author,
                    description = description,
                    feedUrl = feedUrl,
                    imageUrl = imageUrl,
                ),
                autoDownloadEpisodes = autoDownload,
            ),
        )
        val result = runCatching { clientFactory.api(target.serverId, server.baseUrl).createPodcast(body) }
        result.onSuccess { runCatching { syncer.syncAll() } }
        result.map { }
    }

    override suspend fun setAutoDownload(serverId: String, libraryItemId: String, enabled: Boolean): Result<Unit> =
        withContext(dispatcher) {
            val server = serverDao.enabled().firstOrNull { it.serverId == serverId }
                ?: return@withContext Result.failure(IllegalStateException("Server not connected."))
            val result = runCatching {
                val resp = clientFactory.api(serverId, server.baseUrl)
                    .updatePodcastSettings(libraryItemId, AbsPodcastSettingsBody(autoDownloadEpisodes = enabled))
                check(resp.isSuccessful) { "Update rejected (${resp.code()})." }
            }
            // Mirror locally so the toggle reflects immediately (server confirms on next sync).
            result.onSuccess {
                podcastDao.get(serverId, libraryItemId)?.let {
                    podcastDao.upsertAll(listOf(it.copy(autoDownload = enabled)))
                }
            }
            result.map { }
        }

    override suspend fun checkNewEpisodes(serverId: String, libraryItemId: String) = withContext(dispatcher) {
        val server = serverDao.enabled().firstOrNull { it.serverId == serverId } ?: return@withContext
        runCatching { clientFactory.api(serverId, server.baseUrl).checkNewEpisodes(libraryItemId) }
        runCatching { syncer.syncOne(serverId, libraryItemId) }
        Unit
    }

    /** Strip filesystem-illegal characters for the podcast directory name. */
    private fun sanitizeFolderName(title: String): String =
        title.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "Podcast" }
}

private fun PodcastEntity.toDomain(baseUrls: Map<String, String>) = Podcast(
    serverId = serverId,
    libraryItemId = libraryItemId,
    title = title,
    author = author,
    description = description,
    feedUrl = feedUrl,
    autoDownload = autoDownload,
    numEpisodes = numEpisodes,
    coverUrl = baseUrls[serverId]?.let { absCoverUrl(it, libraryItemId) },
)

private fun EpisodeWithProgress.toDomain() = Episode(
    serverId = serverId,
    libraryItemId = libraryItemId,
    episodeId = episodeId,
    title = title,
    subtitle = subtitle,
    pubDate = pubDate,
    publishedAt = publishedAt,
    durationS = durationS,
    progressFraction = pct,
    isFinished = isFinished,
    currentTimeS = currentTimeS,
)
