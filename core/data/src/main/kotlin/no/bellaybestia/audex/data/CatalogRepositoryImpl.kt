package no.bellaybestia.audex.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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
import no.bellaybestia.audex.database.ServerDao
import no.bellaybestia.audex.database.WorkEntity
import no.bellaybestia.audex.database.WorkRow
import no.bellaybestia.audex.domain.model.Author
import no.bellaybestia.audex.domain.model.Edition
import no.bellaybestia.audex.domain.model.Format
import no.bellaybestia.audex.domain.model.Series
import no.bellaybestia.audex.domain.model.Work
import no.bellaybestia.audex.domain.model.absCoverUrl
import no.bellaybestia.audex.domain.repository.CatalogRepository
import no.bellaybestia.audex.network.abs.AbsClientFactory
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
    private val serverDao: ServerDao,
    private val clientFactory: AbsClientFactory,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) : CatalogRepository {

    private val json = Json { ignoreUnknownKeys = true }
    private val builder = GraphBuilder()

    // serverId → baseUrl, kept reactive so covers appear as soon as a server is
    // added (and switch URLs if a server's base ever changes).
    private val baseUrls: Flow<Map<String, String>> =
        serverDao.observeAll().map { rows -> rows.associate { it.serverId to it.baseUrl } }

    override fun authors(): Flow<List<Author>> =
        catalogDao.observeAuthors().map { rows -> rows.map { Author(it.id, it.name, it.workCount) } }

    override fun series(): Flow<List<Series>> =
        catalogDao.observeSeries().map { rows -> rows.map { Series(it.id, it.name, workCount = it.workCount) } }

    override fun works(): Flow<List<Work>> =
        combine(catalogDao.observeWorks(), baseUrls) { rows, urls -> rows.map { it.toDomain(urls) } }

    override fun work(workId: String): Flow<Work?> =
        works().map { list -> list.firstOrNull { it.id == workId } }

    override suspend fun description(serverId: String, libraryItemId: String): String? =
        withContext(dispatcher) {
            val server = serverDao.enabled().firstOrNull { it.serverId == serverId }
                ?: return@withContext null
            runCatching {
                clientFactory.api(serverId, server.baseUrl)
                    .item(libraryItemId).media.metadata.description
            }.getOrNull()?.let(::cleanHtml)?.takeIf { it.isNotBlank() }
        }

    /** ABS descriptions are HTML-ish; strip tags, decode common entities (Codex rule). */
    private fun cleanHtml(raw: String): String = raw
        .replace(Regex("<br ?/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    override fun worksForAuthor(authorId: String): Flow<List<Work>> =
        combine(catalogDao.observeWorksForAuthor(authorId), baseUrls) { rows, urls ->
            rows.map { it.toDomain(urls) }
        }

    override fun worksForSeries(seriesId: String): Flow<List<Work>> =
        combine(catalogDao.observeWorksForSeries(seriesId), baseUrls) { rows, urls ->
            rows.map { it.toDomain(urls) }
        }

    override fun editionsForWork(workId: String): Flow<List<Edition>> =
        combine(catalogDao.observeEditionsForWork(workId), baseUrls) { rows, urls ->
            rows.map {
                Edition(
                    id = it.editionId,
                    workId = it.workId,
                    format = if (it.format == "AUDIO") Format.AUDIO else Format.EBOOK,
                    serverId = it.serverId,
                    libraryItemId = it.libraryItemId,
                    durationS = it.durationS,
                    fraction = it.fraction,
                    coverUrl = urls[it.serverId]?.let { base -> absCoverUrl(base, it.libraryItemId) },
                )
            }
        }

    override suspend fun workIdForItem(serverId: String, libraryItemId: String): String? =
        catalogDao.workIdForItem(serverId, libraryItemId)

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

private fun WorkRow.toDomain(baseUrls: Map<String, String>) = Work(
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
    coverUrl = coverKey?.split('|', limit = 2)
        ?.takeIf { it.size == 2 }
        ?.let { (serverId, itemId) -> baseUrls[serverId]?.let { absCoverUrl(it, itemId) } },
    updatedAt = updatedAtRemote,
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
