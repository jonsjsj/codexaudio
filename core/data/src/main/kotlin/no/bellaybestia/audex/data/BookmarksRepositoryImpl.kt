package no.bellaybestia.audex.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.bellaybestia.audex.database.ServerDao
import no.bellaybestia.audex.domain.playback.Bookmark
import no.bellaybestia.audex.domain.playback.BookmarksRepository
import no.bellaybestia.audex.network.abs.AbsBookmarkRequest
import no.bellaybestia.audex.network.abs.AbsClientFactory

/**
 * ABS-backed bookmarks (endpoints verified live against 2.35.1): create via
 * POST /api/me/item/{id}/bookmark, list via /api/me's user.bookmarks, delete
 * keyed on the time value. Server-side state → syncs with every ABS client.
 */
@Singleton
class BookmarksRepositoryImpl @Inject constructor(
    private val serverDao: ServerDao,
    private val clientFactory: AbsClientFactory,
) : BookmarksRepository {

    override suspend fun bookmarksFor(serverId: String, libraryItemId: String): List<Bookmark> =
        withContext(Dispatchers.IO) {
            val server = serverDao.enabled().firstOrNull { it.serverId == serverId }
                ?: return@withContext emptyList()
            runCatching {
                clientFactory.api(serverId, server.baseUrl).me().bookmarks
                    .filter { it.libraryItemId == libraryItemId }
                    .map {
                        Bookmark(serverId, libraryItemId, it.time, it.title, it.createdAt)
                    }
                    .sortedBy { it.timeS }
            }.getOrDefault(emptyList())
        }

    override suspend fun add(
        serverId: String,
        libraryItemId: String,
        timeS: Long,
        title: String,
    ): Bookmark = withContext(Dispatchers.IO) {
        val server = serverDao.enabled().firstOrNull { it.serverId == serverId }
            ?: error("Server not connected")
        val created = clientFactory.api(serverId, server.baseUrl)
            .createBookmark(libraryItemId, AbsBookmarkRequest(timeS, title))
        Bookmark(serverId, libraryItemId, created.time, created.title, created.createdAt)
    }

    override suspend fun remove(bookmark: Bookmark): Unit = withContext(Dispatchers.IO) {
        val server = serverDao.enabled().firstOrNull { it.serverId == bookmark.serverId }
            ?: return@withContext
        runCatching {
            clientFactory.api(bookmark.serverId, server.baseUrl)
                .deleteBookmark(bookmark.libraryItemId, bookmark.timeS)
        }
        Unit
    }
}
