package no.bellaybestia.audex.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {
    @Query("SELECT * FROM servers ORDER BY name")
    fun observeAll(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers WHERE enabled = 1 ORDER BY serverId")
    suspend fun enabled(): List<ServerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(server: ServerEntity)

    @Query("DELETE FROM servers WHERE serverId = :serverId")
    suspend fun delete(serverId: String)
}

@Dao
interface RemoteItemDao {
    @Query("SELECT * FROM remote_items ORDER BY serverId, libraryItemId")
    suspend fun all(): List<RemoteItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<RemoteItemEntity>)

    @Query("DELETE FROM remote_items WHERE serverId = :serverId AND libraryItemId NOT IN (:keep)")
    suspend fun pruneMissing(serverId: String, keep: List<String>)
}

@Dao
interface ChapterDao {
    @Query("SELECT * FROM remote_chapters WHERE serverId = :serverId AND libraryItemId = :itemId ORDER BY idx")
    suspend fun forItem(serverId: String, itemId: String): List<RemoteChapterEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(chapters: List<RemoteChapterEntity>)
}

/** Row shape for the library lists: a work with its display names and dual progress. */
data class WorkRow(
    val workId: String,
    val title: String,
    val authorName: String?,
    val seriesName: String?,
    val seriesPosition: Double?,
    val subSeriesName: String?,
    val year: Int?,
    val listenPct: Double?,
    val readPct: Double?,
    val audioCount: Int,
    val ebookCount: Int,
    /** Representative edition for the cover, as "serverId|libraryItemId" (split in the repo). */
    val coverKey: String?,
    /** Latest remote updatedAt across the work's items — proxy for "recently added". */
    val updatedAtRemote: Long?,
)

private const val WORK_ROW_SELECT = """
    SELECT w.workId, w.title, a.displayName AS authorName, s.displayName AS seriesName,
           w.seriesPosition, w.subSeriesName, w.year,
           MAX(CASE WHEN e.format = 'AUDIO' THEN COALESCE(p.pct, 0) END) AS listenPct,
           MAX(CASE WHEN e.format = 'EBOOK' THEN COALESCE(p.pct, 0) END) AS readPct,
           COUNT(CASE WHEN e.format = 'AUDIO' THEN 1 END) AS audioCount,
           COUNT(CASE WHEN e.format = 'EBOOK' THEN 1 END) AS ebookCount,
           MIN(e.serverId || '|' || e.libraryItemId) AS coverKey,
           MAX(r.updatedAtRemote) AS updatedAtRemote
    FROM works w
    LEFT JOIN authors a ON a.authorId = w.authorId
    LEFT JOIN series s ON s.seriesId = w.seriesId
    LEFT JOIN editions e ON e.workId = w.workId
    LEFT JOIN progress p ON p.serverId = e.serverId AND p.libraryItemId = e.libraryItemId
    LEFT JOIN remote_items r ON r.serverId = e.serverId AND r.libraryItemId = e.libraryItemId
"""

@Dao
interface CatalogDao {
    @Query(
        """SELECT a.authorId AS id, a.displayName AS name, COUNT(w.workId) AS workCount
           FROM authors a LEFT JOIN works w ON w.authorId = a.authorId
           GROUP BY a.authorId ORDER BY a.displayName COLLATE NOCASE"""
    )
    fun observeAuthors(): Flow<List<AuthorRow>>

    @Query(
        """SELECT s.seriesId AS id, s.displayName AS name,
                  COUNT(w.workId) AS workCount
           FROM series s LEFT JOIN works w ON w.seriesId = s.seriesId
           GROUP BY s.seriesId ORDER BY s.displayName COLLATE NOCASE"""
    )
    fun observeSeries(): Flow<List<SeriesRow>>

    @Query("$WORK_ROW_SELECT GROUP BY w.workId ORDER BY authorName COLLATE NOCASE, seriesName, w.seriesPosition, w.title")
    fun observeWorks(): Flow<List<WorkRow>>

    @Query("$WORK_ROW_SELECT WHERE w.authorId = :authorId GROUP BY w.workId ORDER BY seriesName, w.seriesPosition, w.year, w.title")
    fun observeWorksForAuthor(authorId: String): Flow<List<WorkRow>>

    @Query("$WORK_ROW_SELECT WHERE w.seriesId = :seriesId GROUP BY w.workId ORDER BY w.seriesPosition, w.year, w.title")
    fun observeWorksForSeries(seriesId: String): Flow<List<WorkRow>>

    @Query(
        """SELECT e.*, COALESCE(p.pct, 0) AS fraction FROM editions e
           LEFT JOIN progress p ON p.serverId = e.serverId AND p.libraryItemId = e.libraryItemId
           WHERE e.workId = :workId ORDER BY e.format, e.serverId"""
    )
    fun observeEditionsForWork(workId: String): Flow<List<EditionWithProgress>>

    @Query("SELECT workId FROM editions WHERE serverId = :serverId AND libraryItemId = :itemId LIMIT 1")
    suspend fun workIdForItem(serverId: String, itemId: String): String?

    // -- graph rebuild (full replace, in one transaction) --

    @Transaction
    suspend fun replaceGraph(
        authors: List<AuthorEntity>,
        series: List<SeriesEntity>,
        works: List<WorkEntity>,
        editions: List<EditionEntity>,
    ) {
        clearEditions(); clearWorks(); clearSeries(); clearAuthors()
        insertAuthors(authors); insertSeries(series); insertWorks(works); insertEditions(editions)
    }

    @Query("DELETE FROM authors") suspend fun clearAuthors()
    @Query("DELETE FROM series") suspend fun clearSeries()
    @Query("DELETE FROM works") suspend fun clearWorks()
    @Query("DELETE FROM editions") suspend fun clearEditions()
    // IGNORE (not the default ABORT): the graph ids are content hashes, so a rare
    // collision between two groups the builder couldn't distinguish must drop the
    // duplicate row — never abort the transaction and wipe the whole library.
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAuthors(rows: List<AuthorEntity>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertSeries(rows: List<SeriesEntity>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertWorks(rows: List<WorkEntity>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertEditions(rows: List<EditionEntity>)
}

data class AuthorRow(val id: String, val name: String, val workCount: Int)
data class SeriesRow(val id: String, val name: String, val workCount: Int)

data class EditionWithProgress(
    val editionId: String,
    val workId: String,
    val format: String,
    val serverId: String,
    val libraryItemId: String,
    val durationS: Long?,
    val asin: String?,
    val isbn13: String?,
    val abridged: Boolean,
    val matchMethod: String,
    val matchConfidence: Double,
    val flaggedForReview: Boolean,
    val fraction: Double,
)

@Dao
interface OverrideDao {
    @Query("SELECT * FROM overrides ORDER BY id")
    suspend fun all(): List<OverrideEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(override: OverrideEntity)

    @Query("DELETE FROM overrides WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ProgressDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<ProgressEntity>)

    @Query("SELECT * FROM progress WHERE serverId = :serverId AND libraryItemId = :itemId")
    suspend fun get(serverId: String, itemId: String): ProgressEntity?
}

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: PendingSessionEntity)

    @Query("SELECT * FROM pending_sessions WHERE state = 'PENDING' AND serverId = :serverId ORDER BY startedAt")
    suspend fun pendingForServer(serverId: String): List<PendingSessionEntity>

    @Query("UPDATE pending_sessions SET state = :state, attempts = attempts + 1 WHERE localId IN (:ids)")
    suspend fun setState(ids: List<String>, state: String)

    // Sessions stuck in RECORDING because the process died before
    // finalizeActive (force-stop, crash, task-kill) — safe to adopt at process
    // start, when no recording can be active yet.
    @Query("UPDATE pending_sessions SET state = 'PENDING' WHERE state = 'RECORDING'")
    suspend fun adoptOrphanedRecordings()

    @Query("DELETE FROM pending_sessions WHERE state = 'SYNCED' AND updatedAt < :olderThan")
    suspend fun purgeSynced(olderThan: Long)
}

@Dao
interface EbookProgressQueueDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: PendingEbookProgressEntity)

    @Query("SELECT * FROM pending_ebook_progress WHERE state = 'PENDING'")
    suspend fun pending(): List<PendingEbookProgressEntity>

    @Query("DELETE FROM pending_ebook_progress WHERE serverId = :serverId AND libraryItemId = :itemId")
    suspend fun delete(serverId: String, itemId: String)
}

/** A download row joined with its item title (for the Downloads list). */
data class DownloadWithTitle(
    val serverId: String,
    val libraryItemId: String,
    val kind: String,
    val state: String,
    val bytesTotal: Long,
    val bytesDone: Long,
    val dirPath: String?,
    val title: String?,
)

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY serverId, libraryItemId")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query(
        """SELECT d.serverId, d.libraryItemId, d.kind, d.state, d.bytesTotal, d.bytesDone, d.dirPath,
                  r.title AS title
           FROM downloads d
           LEFT JOIN remote_items r ON r.serverId = d.serverId AND r.libraryItemId = d.libraryItemId
           ORDER BY r.title COLLATE NOCASE"""
    )
    fun observeAllWithTitle(): Flow<List<DownloadWithTitle>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: DownloadEntity)

    @Query("SELECT * FROM downloads WHERE serverId = :serverId AND libraryItemId = :itemId AND kind = :kind")
    suspend fun get(serverId: String, itemId: String, kind: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE serverId = :serverId AND libraryItemId = :itemId")
    suspend fun forItem(serverId: String, itemId: String): List<DownloadEntity>

    @Query("UPDATE downloads SET state = :state, bytesDone = :bytesDone, bytesTotal = :bytesTotal, dirPath = :dirPath WHERE serverId = :serverId AND libraryItemId = :itemId AND kind = :kind")
    suspend fun updateProgress(serverId: String, itemId: String, kind: String, state: String, bytesDone: Long, bytesTotal: Long, dirPath: String?)

    @Query("DELETE FROM downloads WHERE serverId = :serverId AND libraryItemId = :itemId AND kind = :kind")
    suspend fun delete(serverId: String, itemId: String, kind: String)
}
