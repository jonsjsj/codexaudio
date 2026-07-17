package no.bellaybestia.audex.domain.playback

/** A manual audio bookmark (server-side ABS state, so it syncs across clients). */
data class Bookmark(
    val serverId: String,
    val libraryItemId: String,
    /** Position in seconds — also the bookmark's identity on the server. */
    val timeS: Long,
    val title: String,
    val createdAt: Long,
)

/** Manual bookmarks with notes (impl in :core:data via the ABS bookmarks API). */
interface BookmarksRepository {
    suspend fun bookmarksFor(serverId: String, libraryItemId: String): List<Bookmark>
    suspend fun add(serverId: String, libraryItemId: String, timeS: Long, title: String): Bookmark
    suspend fun remove(bookmark: Bookmark)
}
