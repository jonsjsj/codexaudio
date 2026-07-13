package no.bellaybestia.audex.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import no.bellaybestia.audex.catalog.CatalogOverride
import no.bellaybestia.audex.catalog.GraphBuilder
import no.bellaybestia.audex.catalog.OverrideKind
import no.bellaybestia.audex.catalog.RemoteBook
import no.bellaybestia.audex.catalog.SeriesRef
import no.bellaybestia.audex.common.DefaultDispatcher
import no.bellaybestia.audex.database.AuthorEntity
import no.bellaybestia.audex.database.CatalogDao
import no.bellaybestia.audex.database.EditionEntity
import no.bellaybestia.audex.database.OverrideDao
import no.bellaybestia.audex.database.RemoteItemDao
import no.bellaybestia.audex.database.RemoteItemEntity
import no.bellaybestia.audex.database.SeriesEntity
import no.bellaybestia.audex.database.WorkEntity
import no.bellaybestia.audex.database.WorkRow
import no.bellaybestia.audex.domain.model.Author
import no.bellaybestia.audex.domain.model.Edition
import no.bellaybestia.audex.domain.model.Format
import no.bellaybestia.audex.domain.model.Series
import no.bellaybestia.audex.domain.model.Work
import no.bellaybestia.audex.domain.repository.CatalogRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read side over the Room graph tables; write side is [rebuildGraph], the
 * deterministic full recompute (docs/06): raw remote items + overrides in,
 * canonical authors/series/works/editions out, replaced in one transaction.
 */
@Singleton
class CatalogRepositoryImpl @Inject constructor(
    private val catalogDao: CatalogDao,
    private val remoteItemDao: RemoteItemDao,
    private val overrideDao: OverrideDao,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) : CatalogRepository {

    private val json = Json { ignoreUnknownKeys = true }
    private val builder = GraphBuilder()

    override fun authors(): Flow<List<Author>> =
        catalogDao.observeAuthors().map { rows -> rows.map { Author(it.id, it.name, it.workCount) } }

    override fun series(): Flow<List<Series>> =
        catalogDao.observeSeries().map { rows -> rows.map { Series(it.id, it.name, workCount = it.workCount) } }

    override fun works(): Flow<List<Work>> =
        catalogDao.observeWorks().map { rows -> rows.map { it.toDomain() } }

    override fun worksForAuthor(authorId: String): Flow<List<Work>> =
        catalogDao.observeWorksForAuthor(authorId).map { rows -> rows.map { it.toDomain() } }

    override fun worksForSeries(seriesId: String): Flow<List<Work>> =
        catalogDao.observeWorksForSeries(seriesId).map { rows -> rows.map { it.toDomain() } }

    override fun editionsForWork(workId: String): Flow<List<Edition>> =
        catalogDao.observeEditionsForWork(workId).map { rows ->
            rows.map {
                Edition(
                    id = it.editionId,
                    workId = it.workId,
                    format = if (it.format == "AUDIO") Format.AUDIO else Format.EBOOK,
                    serverId = it.serverId,
                    libraryItemId = it.libraryItemId,
                    durationS = it.durationS,
                    fraction = it.fraction,
                )
            }
        }

    override suspend fun rebuildGraph() = withContext(dispatcher) {
        val items = remoteItemDao.all().map { it.toRemoteBook(json) }
        val overrides = overrideDao.all().mapNotNull { row ->
            runCatching {
                CatalogOverride(OverrideKind.valueOf(row.kind), row.subjectKey, row.targetKey)
            }.getOrNull()
        }
        val graph = builder.build(items, overrides)
        catalogDao.replaceGraph(
            authors = graph.authors.map { AuthorEntity(it.authorId, it.displayName, it.normKey) },
            series = graph.series.map { SeriesEntity(it.seriesId, it.displayName, it.normKey) },
            works = graph.works.map {
                WorkEntity(
                    workId = it.workId, title = it.title, authorId = it.authorId,
                    seriesId = it.seriesId, seriesPosition = it.seriesPosition,
                    subSeriesName = it.subSeriesName, subSeriesPosition = it.subSeriesPosition,
                    year = it.year,
                )
            },
            editions = graph.editions.map {
                EditionEntity(
                    editionId = it.editionId, workId = it.workId, format = it.format.name,
                    serverId = it.serverId, libraryItemId = it.libraryItemId,
                    durationS = it.durationS, asin = it.asin, isbn13 = it.isbn13,
                    abridged = it.abridged, matchMethod = it.matchMethod.name,
                    matchConfidence = it.matchConfidence, flaggedForReview = it.flaggedForReview,
                )
            },
        )
    }
}

private fun WorkRow.toDomain() = Work(
    id = workId,
    title = title,
    authorName = authorName,
    seriesName = seriesName,
    seriesPosition = seriesPosition,
    subSeriesName = subSeriesName,
    year = year,
    hasAudio = audioCount > 0,
    hasEbook = ebookCount > 0,
    listenFraction = listenPct ?: 0.0,
    readFraction = readPct ?: 0.0,
)

internal fun RemoteItemEntity.toRemoteBook(json: Json): RemoteBook {
    val authors = runCatching { json.decodeFromString<List<String>>(authorsJson) }.getOrDefault(emptyList())
    val narrators = runCatching { json.decodeFromString<List<String>>(narratorsJson) }.getOrDefault(emptyList())
    val series = runCatching { json.decodeFromString<List<StoredSeriesRef>>(seriesJson) }.getOrDefault(emptyList())
    return RemoteBook(
        serverId = serverId,
        libraryItemId = libraryItemId,
        title = title,
        subtitle = subtitle,
        authors = authors,
        narrators = narrators,
        series = series.map { SeriesRef(it.name, it.sequence) },
        asin = asin,
        isbn = isbn,
        publishedYear = publishedYear,
        durationS = durationS,
        hasAudio = numAudioFiles > 0,
        hasEbook = !ebookFormat.isNullOrBlank(),
        abridged = abridged,
    )
}

@kotlinx.serialization.Serializable
internal data class StoredSeriesRef(val name: String, val sequence: Double? = null)
