package no.bellaybestia.audex.domain.reader

import kotlinx.coroutines.flow.Flow

/**
 * A saved reader highlight. [locatorJson] is a Readium Locator serialized to
 * JSON — the reader turns it back into a Decoration (to paint) and a jump
 * target. [text] is the highlighted passage, shown in the highlights list.
 */
data class Highlight(
    val id: String,
    val serverId: String,
    val libraryItemId: String,
    val locatorJson: String,
    val text: String,
    val createdAt: Long,
)

/** Local store of reader highlights (ABS has no highlights API). */
interface HighlightsRepository {
    fun forItem(serverId: String, libraryItemId: String): Flow<List<Highlight>>
    suspend fun add(serverId: String, libraryItemId: String, locatorJson: String, text: String)
    suspend fun remove(id: String)
}
