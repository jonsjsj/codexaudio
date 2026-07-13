package no.bellaybestia.codexaudio.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import no.bellaybestia.codexaudio.database.ProgressDao
import no.bellaybestia.codexaudio.database.ProgressEntity
import no.bellaybestia.codexaudio.database.RemoteChapterEntity
import no.bellaybestia.codexaudio.database.RemoteItemDao
import no.bellaybestia.codexaudio.database.RemoteItemEntity
import no.bellaybestia.codexaudio.database.ServerDao
import no.bellaybestia.codexaudio.domain.repository.CatalogRepository
import no.bellaybestia.codexaudio.network.abs.AbsClientFactory
import no.bellaybestia.codexaudio.network.abs.AbsLibraryItem
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pulls every enabled server's book libraries into `remote_items` and refreshes
 * the local progress mirror from GET /api/me, then rebuilds the canonical
 * graph. Incremental strategy: full page walk with updatedAt short-circuit —
 * ABS has no delta endpoint [verify]; socket.io covers changes between walks.
 */
@Singleton
class LibrarySyncer @Inject constructor(
    private val serverDao: ServerDao,
    private val remoteItemDao: RemoteItemDao,
    private val progressDao: ProgressDao,
    private val clientFactory: AbsClientFactory,
    private val catalogRepository: CatalogRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun syncAll() {
        var anySuccess = false
        for (server in serverDao.enabled()) {
            // Per-server error isolation: one unreachable server must not
            // block the rest; its previously-synced items stay in place.
            runCatching { syncServer(server.serverId, server.baseUrl) }
                .onSuccess { anySuccess = true }
        }
        if (anySuccess) catalogRepository.rebuildGraph()
    }

    private suspend fun syncServer(serverId: String, baseUrl: String) {
        val api = clientFactory.api(serverId, baseUrl)

        val seen = mutableListOf<String>()
        for (library in api.libraries().libraries.filter { it.mediaType == "book" }) {
            var page = 0
            while (true) {
                val batch = api.libraryItems(library.id, limit = 100, page = page)
                if (batch.results.isEmpty()) break
                remoteItemDao.upsertAll(batch.results.map { it.toEntity(serverId, library.id, json) })
                seen += batch.results.map { it.id }
                page++
                if ((page * batch.limit) >= batch.total && batch.total > 0) break
                if (batch.limit == 0) break
            }
        }
        if (seen.isNotEmpty()) remoteItemDao.pruneMissing(serverId, seen)

        // Bulk progress reconcile — resume pointers only; listening time is
        // additive and only ever flows out through the sessions API.
        val me = api.me()
        progressDao.upsertAll(
            me.mediaProgress.map {
                ProgressEntity(
                    serverId = serverId,
                    libraryItemId = it.libraryItemId,
                    pct = if (it.isFinished) 1.0 else maxOf(it.progress, it.ebookProgress ?: 0.0),
                    currentTimeS = it.currentTime,
                    ebookLocation = it.ebookLocation,
                    ebookProgress = it.ebookProgress,
                    isFinished = it.isFinished,
                    lastUpdate = it.lastUpdate,
                    source = "SERVER",
                )
            }
        )
        serverDao.upsert(
            serverDao.enabled().first { it.serverId == serverId }
                .copy(absUserId = me.id, lastFullSyncAt = System.currentTimeMillis())
        )
    }
}

internal fun AbsLibraryItem.toEntity(serverId: String, libraryId: String, json: Json): RemoteItemEntity {
    val md = media.metadata
    return RemoteItemEntity(
        serverId = serverId,
        libraryItemId = id,
        libraryId = libraryId,
        mediaType = mediaType,
        title = md.title.orEmpty().ifBlank { "(untitled)" },
        subtitle = md.subtitle,
        authorsJson = json.encodeToString(md.authors.map { it.name }),
        seriesJson = json.encodeToString(
            md.series.map { StoredSeriesRef(it.name, it.sequence?.toDoubleOrNull()) }
        ),
        narratorsJson = json.encodeToString(md.narrators),
        asin = md.asin,
        isbn = md.isbn,
        publishedYear = md.publishedYear?.toIntOrNull(),
        durationS = media.duration?.toLong(),
        numAudioFiles = media.numAudioFiles,
        ebookFormat = media.ebookFormat,
        abridged = md.abridged,
        updatedAtRemote = updatedAt,
    )
}

@Suppress("unused")
internal fun AbsLibraryItem.chapterEntities(serverId: String): List<RemoteChapterEntity> =
    media.chapters.mapIndexed { idx, c ->
        RemoteChapterEntity(serverId, id, idx, c.title, c.start, c.end)
    }
