package no.bellaybestia.audex.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import no.bellaybestia.audex.database.HighlightDao
import no.bellaybestia.audex.database.HighlightEntity
import no.bellaybestia.audex.domain.reader.Highlight
import no.bellaybestia.audex.domain.reader.HighlightsRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HighlightsRepositoryImpl @Inject constructor(
    private val highlightDao: HighlightDao,
) : HighlightsRepository {

    override fun forItem(serverId: String, libraryItemId: String): Flow<List<Highlight>> =
        highlightDao.observe(serverId, libraryItemId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun add(serverId: String, libraryItemId: String, locatorJson: String, text: String) {
        highlightDao.upsert(
            HighlightEntity(
                id = UUID.randomUUID().toString(),
                serverId = serverId,
                libraryItemId = libraryItemId,
                locatorJson = locatorJson,
                text = text,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun remove(id: String) = highlightDao.delete(id)

    private fun HighlightEntity.toDomain() = Highlight(
        id = id,
        serverId = serverId,
        libraryItemId = libraryItemId,
        locatorJson = locatorJson,
        text = text,
        createdAt = createdAt,
    )
}
