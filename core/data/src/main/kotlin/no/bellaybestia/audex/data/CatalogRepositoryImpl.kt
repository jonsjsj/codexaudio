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
import no.bellaybestia.audex.database.OverrideEntity
import no.bellaybestia.audex.database.EbookProgressQueueDao
import no.bellaybestia.audex.database.ProgressDao
import no.bellaybestia.audex.database.SessionDao
import no.bellaybestia.audex.database.ProgressEntity
import no.bellaybestia.audex.database.RemoteItemDao
import no.bellaybestia.audex.database.RemoteItemEntity
import no.bellaybestia.audex.database.SeriesEntity
import no.bellaybestia.audex.database.ServerDao
import no.bellaybestia.audex.database.WorkEntity
import no.bellaybestia.audex.database.WorkRow
import no.bellaybestia.audex.domain.model.Author
import no.bellaybestia.audex.domain.model.Edition
import no.bellaybestia.audex.domain.model.Format
import no.bellaybestia.audex.domain.model.ResumeTarget
import kotlinx.coroutines.flow.first
import no.bellaybestia.audex.domain.model.Series
import no.bellaybestia.audex.domain.model.Work
import no.bellaybestia.audex.domain.model.absCoverUrl
import no.bellaybestia.audex.domain.repository.BookExtras
import no.bellaybestia.audex.domain.repository.CatalogRepository
import no.bellaybestia.audex.network.abs.AbsClientFactory
import no.bellaybestia.audex.network.abs.AbsProgressResetBody
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
    private val progressDao: ProgressDao,
    private val sessionDao: SessionDao,
    private val ebookProgressQueueDao: EbookProgressQueueDao,
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

    override suspend fun bookExtras(serverId: String, libraryItemId: String): BookExtras? =
        withContext(dispatcher) {
            val server = serverDao.enabled().firstOrNull { it.serverId == serverId }
                ?: return@withContext null
            runCatching {
                val md = clientFactory.api(serverId, server.baseUrl)
                    .item(libraryItemId).media.metadata
                BookExtras(
                    description = md.description?.let(::cleanHtml)?.takeIf { it.isNotBlank() },
                    // Expanded detail gives the array; the minified projection only
                    // ever has the comma-joined string — take whichever is there.
                    narrator = md.narrators.takeIf { it.isNotEmpty() }?.joinToString(", ")
                        ?: md.narratorName?.takeIf { it.isNotBlank() },
                    asin = md.asin?.takeIf { it.isNotBlank() },
                )
            }.getOrNull()
        }

    override suspend fun furthestPositionS(serverId: String, libraryItemId: String): Double? =
        withContext(dispatcher) {
            val server = serverDao.enabled().firstOrNull { it.serverId == serverId }
                ?: return@withContext null
            runCatching {
                clientFactory.api(serverId, server.baseUrl)
                    .itemListeningSessions(libraryItemId)
                    .sessions.maxOfOrNull { it.currentTime }
            }.getOrNull()
        }

    override suspend fun discardProgress(serverId: String, libraryItemId: String) =
        withContext(dispatcher) {
            // Delete the progress record on the server (best-effort — offline
            // still clears locally). Must look up the record id first: DELETE is
            // keyed on the media-progress record id, not the libraryItemId
            // (deleting by libraryItemId 404s), and PATCH-to-zero is ignored.
            serverDao.enabled().firstOrNull { it.serverId == serverId }?.let { server ->
                runCatching {
                    val api = clientFactory.api(serverId, server.baseUrl)
                    val recordId = api.getMediaProgress(libraryItemId).id
                    if (recordId != null) api.deleteProgress(recordId)
                }
            }
            // Kill every queued path that could resurrect the wipe: an orphaned
            // recording session (adopted RECORDING → PENDING at the next launch)
            // or a queued ebook-position PATCH would re-post the old position to
            // the server on app start — the "discarded book comes back after
            // every update" bug.
            sessionDao.deleteForItem(serverId, libraryItemId)
            ebookProgressQueueDao.delete(serverId, libraryItemId)
            // Zero the local row so the detail page, Home, and library all reflect
            // the reset immediately (editions read their fraction from this row).
            progressDao.get(serverId, libraryItemId)?.let { row ->
                progressDao.upsertAll(
                    listOf(
                        row.copy(
                            pct = 0.0,
                            currentTimeS = 0.0,
                            ebookLocation = null,
                            ebookProgress = 0.0,
                            isFinished = false,
                            lastUpdate = System.currentTimeMillis(),
                            // Mark the wipe as a LOCAL override so the next sync can't
                            // resurrect the book from a server row (e.g. if the
                            // server-side delete above didn't land) — local wins.
                            source = "LOCAL_PLAYBACK",
                        ),
                    ),
                )
            }
            Unit
        }

    override suspend fun mirrorAudioProgress(
        serverId: String,
        libraryItemId: String,
        fraction: Double,
        durationS: Long?,
    ) = withContext(dispatcher) {
        val f = fraction.coerceIn(0.0, 1.0)
        val existing = progressDao.get(serverId, libraryItemId)
        // FORWARD-ONLY. This mirror exists so that reading AHEAD of where you
        // listened carries over to the audiobook — it must never drag the audio
        // position BACKWARD. Merely opening the ebook (or a locator that hasn't
        // restored yet) reports an early position, and that was silently
        // overwriting hours of listening: open the text for a second, come back
        // to the audiobook, and you'd resume way behind. If the audio is already
        // further along, keep it.
        val newTimeS = durationS?.let { f * it }
        val existingTimeS = existing?.currentTimeS ?: 0.0
        val alreadyFurther = when {
            existing == null -> false
            newTimeS != null -> existingTimeS >= newTimeS - 1.0
            else -> (existing.pct) >= f - 0.0005
        }
        if (alreadyFurther) return@withContext
        val row = (existing ?: ProgressEntity(serverId = serverId, libraryItemId = libraryItemId)).copy(
            pct = f,
            currentTimeS = newTimeS ?: existingTimeS,
            isFinished = f >= 0.999,
            lastUpdate = System.currentTimeMillis(),
            source = "LOCAL_XFORMAT",
        )
        progressDao.upsertAll(listOf(row))
        Unit
    }

    override suspend fun setAudioFraction(
        serverId: String,
        libraryItemId: String,
        fraction: Double,
        durationS: Long?,
    ) = withContext(dispatcher) {
        val f = fraction.coerceIn(0.0, 1.0)
        val existing = progressDao.get(serverId, libraryItemId)
        val row = (existing ?: ProgressEntity(serverId = serverId, libraryItemId = libraryItemId)).copy(
            pct = f,
            currentTimeS = durationS?.let { f * it } ?: existing?.currentTimeS,
            // A deliberate jump below the end un-finishes the book so it isn't treated
            // as done; a real re-listen writes its own progress from here.
            isFinished = f >= 0.999,
            lastUpdate = System.currentTimeMillis(),
            source = "LOCAL_XFORMAT",
        )
        progressDao.upsertAll(listOf(row))
        Unit
    }

    override suspend fun mirrorEbookProgress(
        serverId: String,
        ebookItemId: String,
        fraction: Double,
    ) = withContext(dispatcher) {
        val f = fraction.coerceIn(0.0, 1.0)
        val existing = progressDao.get(serverId, ebookItemId)
        // FORWARD-ONLY: listening must never rewind where you've read. If the ebook is
        // already at/ahead of the audio, leave it. Bump only the fraction — the exact
        // page locator (ebookLocation) is preserved; ReaderViewModel.resolveInitialLocator
        // detects that the fraction ran ahead of the stored page and resumes at the
        // furthest spot proportionally (or the live narration anchor) instead.
        if (existing != null && (existing.ebookProgress ?: 0.0) >= f - 0.0005) return@withContext
        val row = (existing ?: ProgressEntity(serverId = serverId, libraryItemId = ebookItemId)).copy(
            ebookProgress = f,
            isFinished = existing?.isFinished ?: false,
            lastUpdate = System.currentTimeMillis(),
            source = "LOCAL_XFORMAT",
        )
        progressDao.upsertAll(listOf(row))
        Unit
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

    override suspend fun resumeTarget(workId: String): ResumeTarget? = withContext(dispatcher) {
        val editions = editionsForWork(workId).first()
        if (editions.isEmpty()) return@withContext null
        val work = work(workId).first() ?: return@withContext null
        // Pair each edition with its saved progress, then pick the one you touched
        // most recently. Fall back to any started edition, then audio, then the
        // first — so a fresh (un-started) book still resumes (into audio if it has one).
        val withProgress = editions.map { ed -> ed to progressDao.get(ed.serverId, ed.libraryItemId) }
        val started = withProgress.filter { (ed, p) ->
            ed.fraction > 0.0 ||
                (p != null && (p.pct > 0.0 || (p.currentTimeS ?: 0.0) > 0.0 || (p.ebookProgress ?: 0.0) > 0.0))
        }
        val (ed, prog) = started.maxByOrNull { (_, p) -> p?.lastUpdate ?: 0L }
            ?: withProgress.firstOrNull { (e, _) -> e.format == Format.AUDIO }
            ?: withProgress.first()
        ResumeTarget(
            serverId = ed.serverId,
            libraryItemId = ed.libraryItemId,
            format = ed.format,
            title = work.title,
            author = work.authorName,
            resumeAtS = prog?.currentTimeS,
        )
    }

    override suspend fun mergeAuthorInto(sourceAuthorId: String, targetAuthorId: String) {
        if (sourceAuthorId == targetAuthorId) return
        val subject = catalogDao.authorNormKey(sourceAuthorId) ?: return
        val target = catalogDao.authorNormKey(targetAuthorId) ?: return
        if (subject.isBlank() || subject == target) return
        // Norm keys are the AUTHOR_MERGE subject/target contract (GraphBuilder
        // matches n.authorKey directly, without re-normalizing) — see docs/06.
        overrideDao.upsert(
            OverrideEntity(
                kind = OverrideKind.AUTHOR_MERGE.name,
                subjectKey = subject,
                targetKey = target,
                createdAt = System.currentTimeMillis(),
            ),
        )
        rebuildGraph()
    }

    override suspend fun mergeSeriesInto(sourceSeriesId: String, targetSeriesId: String) {
        if (sourceSeriesId == targetSeriesId) return
        val subject = catalogDao.seriesNormKey(sourceSeriesId) ?: return
        val target = catalogDao.seriesNormKey(targetSeriesId) ?: return
        if (subject.isBlank() || subject == target) return
        overrideDao.upsert(
            OverrideEntity(
                kind = OverrideKind.SERIES_MERGE.name,
                subjectKey = subject,
                targetKey = target,
                createdAt = System.currentTimeMillis(),
            ),
        )
        rebuildGraph()
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

private fun WorkRow.toDomain(baseUrls: Map<String, String>) = Work(
    id = workId,
    title = title,
    authorId = authorId,
    authorName = authorName,
    seriesId = seriesId,
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
    listenedAt = progressUpdatedAt,
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
