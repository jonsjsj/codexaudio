package no.bellaybestia.audex.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One connected Audiobookshelf server. Tokens live in the Keystore-backed store, never here. */
@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey val serverId: String,
    val name: String,
    val baseUrl: String,
    val absUserId: String? = null,
    val absVersion: String? = null,
    val enabled: Boolean = true,
    val needsLogin: Boolean = false,
    val lastFullSyncAt: Long? = null,
)

/** Raw per-server ingest truth — survives graph rebuilds. */
@Entity(
    tableName = "remote_items",
    primaryKeys = ["serverId", "libraryItemId"],
    indices = [Index("updatedAtRemote")],
)
data class RemoteItemEntity(
    val serverId: String,
    val libraryItemId: String,
    val libraryId: String,
    val mediaType: String,
    val title: String,
    val subtitle: String? = null,
    val authorsJson: String = "[]",
    val seriesJson: String = "[]",
    val narratorsJson: String = "[]",
    val asin: String? = null,
    val isbn: String? = null,
    val publishedYear: Int? = null,
    val durationS: Long? = null,
    val numAudioFiles: Int = 0,
    val ebookFormat: String? = null,
    val abridged: Boolean = false,
    val updatedAtRemote: Long = 0,
)

@Entity(tableName = "remote_chapters", primaryKeys = ["serverId", "libraryItemId", "idx"])
data class RemoteChapterEntity(
    val serverId: String,
    val libraryItemId: String,
    val idx: Int,
    val title: String,
    val startS: Double,
    val endS: Double,
)

// --- canonical graph (output of :core:catalog, rebuilt idempotently) ---

@Entity(tableName = "authors")
data class AuthorEntity(
    @PrimaryKey val authorId: String,
    val displayName: String,
    val normKey: String,
)

@Entity(tableName = "series")
data class SeriesEntity(
    @PrimaryKey val seriesId: String,
    val displayName: String,
    val normKey: String,
)

@Entity(
    tableName = "works",
    indices = [Index("authorId"), Index("seriesId")],
)
data class WorkEntity(
    @PrimaryKey val workId: String,
    val title: String,
    val authorId: String? = null,
    val seriesId: String? = null,
    val seriesPosition: Double? = null,
    val subSeriesName: String? = null,
    val subSeriesPosition: Double? = null,
    val year: Int? = null,
)

@Entity(
    tableName = "editions",
    indices = [Index("workId"), Index(value = ["serverId", "libraryItemId"])],
)
data class EditionEntity(
    @PrimaryKey val editionId: String,
    val workId: String,
    val format: String, // AUDIO | EBOOK
    val serverId: String,
    val libraryItemId: String,
    val durationS: Long? = null,
    val asin: String? = null,
    val isbn13: String? = null,
    val abridged: Boolean = false,
    val matchMethod: String,
    val matchConfidence: Double,
    val flaggedForReview: Boolean = false,
)

/**
 * Manual matching corrections. Subject/target are immutable keys
 * ("serverId:libraryItemId" or norm keys) so re-syncs re-apply them
 * deterministically — the Codex MergedGroup contract.
 */
@Entity(
    tableName = "overrides",
    indices = [Index(value = ["kind", "subjectKey"], unique = true)],
)
data class OverrideEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val subjectKey: String,
    val targetKey: String? = null,
    val createdAt: Long,
)

// --- progress & operations ---

@Entity(tableName = "progress", primaryKeys = ["serverId", "libraryItemId"])
data class ProgressEntity(
    val serverId: String,
    val libraryItemId: String,
    val pct: Double = 0.0,
    val currentTimeS: Double? = null,
    val ebookLocation: String? = null,
    val ebookProgress: Double? = null,
    val isFinished: Boolean = false,
    val lastUpdate: Long = 0,
    val source: String = "SERVER", // SERVER | LOCAL_PLAYBACK | LOCAL_READER
)

/** Locally recorded listening session, queued for /api/session/local-all. */
@Entity(tableName = "pending_sessions", indices = [Index("state")])
data class PendingSessionEntity(
    @PrimaryKey val localId: String,
    val serverId: String,
    val libraryItemId: String,
    val startedAt: Long,
    val updatedAt: Long,
    val startTimeS: Double,
    val currentTimeS: Double,
    val timeListeningS: Double,
    val deviceInfoJson: String,
    val state: String = "RECORDING", // RECORDING | PENDING | SYNCING | SYNCED | FAILED
    val attempts: Int = 0,
    /** Set for podcast-episode sessions; null for books. Carried into the
     * /api/session/local-all upload so ABS accounts the listen to the episode. */
    val episodeId: String? = null,
)

@Entity(tableName = "pending_ebook_progress", primaryKeys = ["serverId", "libraryItemId"])
data class PendingEbookProgressEntity(
    val serverId: String,
    val libraryItemId: String,
    val ebookLocation: String,
    val ebookProgress: Double,
    val updatedAt: Long,
    val state: String = "PENDING",
)

@Entity(tableName = "downloads", primaryKeys = ["serverId", "libraryItemId", "kind"])
data class DownloadEntity(
    val serverId: String,
    val libraryItemId: String,
    val kind: String, // AUDIO | EBOOK
    val state: String = "QUEUED", // QUEUED | RUNNING | DONE | FAILED
    val bytesTotal: Long = 0,
    val bytesDone: Long = 0,
    val dirPath: String? = null,
)

/**
 * A reader highlight: the selected text plus its Readium [Locator] serialized to
 * JSON so it can be turned back into a Decoration and a jump target. Local-only
 * (ABS has no highlights API); no index (the per-book table is tiny). Added in
 * schema v2 via MIGRATION_1_2.
 */
@Entity(tableName = "highlights")
data class HighlightEntity(
    @PrimaryKey val id: String,
    val serverId: String,
    val libraryItemId: String,
    val locatorJson: String,
    val text: String,
    val createdAt: Long,
)

/**
 * Durable per-book, per-day activity — the backbone of the "Your activity"
 * stats. [kind] is "AUDIO" (wall-clock time actually listened) or "EBOOK"
 * (wall-clock time in the reader). [epochDay] is LocalDate.toEpochDay() so the
 * screen can sum today / this week / all-time; a sentinel `epochDay = 0` (1970)
 * holds a one-time baseline seeded from the server's per-book listening totals,
 * counted toward all-time and per-book breakdowns but never toward today/week.
 * Seconds accumulate via UPSERT so each tick just adds to the day's bucket.
 */
@Entity(tableName = "activity", primaryKeys = ["serverId", "libraryItemId", "epochDay", "kind"])
data class ActivityEntity(
    val serverId: String,
    val libraryItemId: String,
    val epochDay: Long,
    val kind: String,
    val seconds: Double = 0.0,
)

// --- podcasts (parallel pipeline; NOT part of the Authors→Series→Works graph) ---

/**
 * A subscribed podcast (a podcast library item on one server). Kept in its own
 * table rather than the book graph: podcasts have no cross-server de-dup, no
 * author/series matching, and no ebook/audio editions. Added in schema v4.
 * [autoDownload]/[autoDownloadSchedule]/[maxEpisodesToKeep] mirror the server's
 * subscription settings (the ABS server, not the app, polls the feed).
 */
@Entity(tableName = "podcasts", primaryKeys = ["serverId", "libraryItemId"])
data class PodcastEntity(
    val serverId: String,
    val libraryItemId: String,
    val libraryId: String,
    val title: String,
    val author: String? = null,
    val description: String? = null,
    val feedUrl: String? = null,
    val autoDownload: Boolean = false,
    val autoDownloadSchedule: String? = null,
    val maxEpisodesToKeep: Int = 0,
    val numEpisodes: Int = 0,
    val updatedAtRemote: Long = 0,
)

/** One podcast episode. Keyed per (server, podcast item, episode). */
@Entity(
    tableName = "episodes",
    primaryKeys = ["serverId", "libraryItemId", "episodeId"],
    indices = [Index(value = ["serverId", "libraryItemId"]), Index("publishedAt")],
)
data class EpisodeEntity(
    val serverId: String,
    val libraryItemId: String,
    val episodeId: String,
    val title: String,
    val subtitle: String? = null,
    val description: String? = null,
    val pubDate: String? = null,
    val publishedAt: Long? = null,
    val durationS: Double? = null,
    val sizeBytes: Long? = null,
    val season: String? = null,
    val episodeNum: String? = null,
    val idx: Int = 0,
)

/**
 * Per-episode progress, keyed by (server, podcast item, episode). Parallel to
 * the book [ProgressEntity] so podcast rows never touch the book progress path.
 */
@Entity(tableName = "episode_progress", primaryKeys = ["serverId", "libraryItemId", "episodeId"])
data class EpisodeProgressEntity(
    val serverId: String,
    val libraryItemId: String,
    val episodeId: String,
    val pct: Double = 0.0,
    val currentTimeS: Double? = null,
    val isFinished: Boolean = false,
    val lastUpdate: Long = 0,
    val source: String = "SERVER", // SERVER | LOCAL_PLAYBACK
)
