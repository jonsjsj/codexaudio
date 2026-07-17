package no.bellaybestia.audex.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import no.bellaybestia.audex.database.ProgressDao
import no.bellaybestia.audex.database.ProgressEntity
import no.bellaybestia.audex.database.RemoteChapterEntity
import no.bellaybestia.audex.database.RemoteItemDao
import no.bellaybestia.audex.database.RemoteItemEntity
import no.bellaybestia.audex.database.ServerDao
import no.bellaybestia.audex.domain.repository.CatalogRepository
import no.bellaybestia.audex.network.abs.AbsClientFactory
import no.bellaybestia.audex.network.abs.AbsLibraryItem
import no.bellaybestia.audex.network.abs.AbsMediaProgress
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
        upsertProgressKeepingLocalReader(serverId, me.mediaProgress.map { it.toProgressEntity(serverId) })
        serverDao.upsert(
            serverDao.enabled().first { it.serverId == serverId }
                .copy(absUserId = me.id, lastFullSyncAt = System.currentTimeMillis())
        )
    }

    /**
     * Lightweight progress-only reconcile from GET /api/me — used on socket
     * (re)connect and on `user_updated` events, without a full library walk.
     */
    suspend fun reconcileProgress(serverId: String) {
        val server = serverDao.enabled().firstOrNull { it.serverId == serverId } ?: return
        val api = clientFactory.api(serverId, server.baseUrl)
        val me = runCatching { api.me() }.getOrNull() ?: return
        upsertProgressKeepingLocalReader(serverId, me.mediaProgress.map { it.toProgressEntity(serverId) })
    }

    /**
     * Upsert server progress, but DON'T clobber a fresher LOCAL_READER position: that row
     * holds our reader's EXACT Readium locator, whereas the server carries only the coarse
     * epubcfi we (or the ABS app) uploaded. Keeping the local row preserves exact restore;
     * for every other item the server row wins as before. Audio rows (LOCAL_PLAYBACK/SERVER)
     * are unaffected.
     */
    private suspend fun upsertProgressKeepingLocalReader(serverId: String, incoming: List<ProgressEntity>) {
        val merged = incoming.map { srv ->
            val local = progressDao.get(serverId, srv.libraryItemId)
            if (local != null && local.source == "LOCAL_READER" && local.lastUpdate >= srv.lastUpdate) local else srv
        }
        progressDao.upsertAll(merged)
    }
}

internal fun AbsLibraryItem.toEntity(serverId: String, libraryId: String, json: Json): RemoteItemEntity {
    val md = media.metadata
    // The library-items LIST endpoint returns MINIFIED metadata: the authors/
    // narrators/series arrays are absent, replaced by comma-joined *Name strings
    // (series sequence embedded as "Name #3"). Fall back to those so the catalog
    // graph gets real authors/series instead of empty lists (which collapse
    // distinct books to one work id and abort the graph rebuild).
    val authors = md.authors.map { it.name }.ifEmpty { splitJoinedNames(md.authorName) }
    val narrators = md.narrators.ifEmpty { splitJoinedNames(md.narratorName) }
    val series = md.series.map { StoredSeriesRef(it.name, it.sequence?.toDoubleOrNull()) }
        .ifEmpty { parseMinifiedSeries(md.seriesName) }
    return RemoteItemEntity(
        serverId = serverId,
        libraryItemId = id,
        libraryId = libraryId,
        mediaType = mediaType,
        title = md.title.orEmpty().ifBlank { "(untitled)" },
        subtitle = md.subtitle,
        authorsJson = json.encodeToString(authors),
        seriesJson = json.encodeToString(series),
        narratorsJson = json.encodeToString(narrators),
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

/** Split ABS minified `authorName`/`narratorName` ("A, B, C") into names. */
internal fun splitJoinedNames(joined: String?): List<String> =
    joined?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()

/**
 * Parse ABS minified `seriesName` ("Wheel of Time #3, Legends #1") into refs.
 * Sequence is embedded after the LAST " #"; a series with no sequence keeps
 * position null. Names containing ", " degrade (rare); the expanded item detail
 * has the exact structured data for the work screen.
 */
internal fun parseMinifiedSeries(joined: String?): List<StoredSeriesRef> =
    joined?.split(",")?.mapNotNull { part ->
        val s = part.trim()
        if (s.isBlank()) return@mapNotNull null
        val at = s.lastIndexOf(" #")
        if (at >= 0) {
            val name = s.substring(0, at).trim()
            val seq = s.substring(at + 2).trim().toDoubleOrNull()
            StoredSeriesRef(name.ifBlank { s }, seq)
        } else {
            StoredSeriesRef(s, null)
        }
    } ?: emptyList()

internal fun AbsMediaProgress.toProgressEntity(serverId: String): ProgressEntity =
    ProgressEntity(
        serverId = serverId,
        libraryItemId = libraryItemId,
        pct = if (isFinished) 1.0 else maxOf(progress, ebookProgress ?: 0.0),
        currentTimeS = currentTime,
        ebookLocation = ebookLocation,
        ebookProgress = ebookProgress,
        isFinished = isFinished,
        lastUpdate = lastUpdate,
        source = "SERVER",
    )
