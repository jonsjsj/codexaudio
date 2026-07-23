package no.bellaybestia.audex.network.abs

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Minimal DTOs for the Audiobookshelf REST API — only the fields this app
 * consumes. Field names follow the ABS payloads; anything uncertain is flagged
 * [verify] in docs/03-abs-api-usage.md and must be checked against the deployed
 * server version before Phase-1 completion.
 */
/** Public GET /status — connectivity, version gate, and offered auth methods. */
@Serializable
data class AbsStatus(
    val app: String = "",
    val serverVersion: String = "",
    val isInit: Boolean = false,
    val authMethods: List<String> = emptyList(),
)

@Serializable
data class AbsLibrariesResponse(val libraries: List<AbsLibrary> = emptyList())

@Serializable
data class AbsLibrary(
    val id: String,
    val name: String = "",
    val mediaType: String = "book",
    // Present on GET /api/libraries. A podcast subscription (POST /api/podcasts)
    // needs a target folder's absolute path to place the new podcast directory.
    val folders: List<AbsLibraryFolder> = emptyList(),
)

@Serializable
data class AbsLibraryFolder(
    val id: String = "",
    val fullPath: String = "",
)

@Serializable
data class AbsItemsPage(
    val results: List<AbsLibraryItem> = emptyList(),
    val total: Int = 0,
    val page: Int = 0,
    val limit: Int = 0,
)

/** GET /api/libraries/{id}/series — each entry's books carry the populated
 * "Name #seq" seriesName even when the item's own metadata series is empty. */
@Serializable
data class AbsSeriesPage(
    val results: List<AbsSeriesEntry> = emptyList(),
    val total: Int = 0,
    val limit: Int = 0,
)

@Serializable
data class AbsSeriesEntry(
    val id: String = "",
    val name: String = "",
    val books: List<AbsLibraryItem> = emptyList(),
)

/** GET /api/me/listening-stats: seconds all-time + today, a per-date map, and a
 * per-item map (its keys are the item ids that have any listening). */
@Serializable
data class AbsListeningStats(
    val totalTime: Double = 0.0,
    val today: Double = 0.0,
    val days: Map<String, Double> = emptyMap(),
    val items: Map<String, AbsStatItem> = emptyMap(),
)

@Serializable
data class AbsStatItem(val id: String = "", val timeListening: Double = 0.0)

@Serializable
data class AbsLibraryItem(
    val id: String,
    val libraryId: String = "",
    val mediaType: String = "book",
    val updatedAt: Long = 0,
    val media: AbsMedia = AbsMedia(),
)

@Serializable
data class AbsMedia(
    val metadata: AbsMetadata = AbsMetadata(),
    val duration: Double? = null,
    val numAudioFiles: Int = 0,
    val ebookFormat: String? = null,
    val chapters: List<AbsChapter> = emptyList(),
    // Present on the expanded item (GET /api/items/{id}?expanded=1); used for
    // per-file offline downloads (each file addressed by its `ino`).
    val audioFiles: List<AbsAudioFile> = emptyList(),
    val ebookFile: AbsEbookFile? = null,
    // --- podcast media (mediaType == "podcast") ---
    // Episodes are only present on the expanded item detail; the library-items
    // list projection carries `numEpisodes` but not the episode array.
    val episodes: List<AbsPodcastEpisode> = emptyList(),
    val numEpisodes: Int = 0,
    // Server-side "subscription" settings: with autoDownloadEpisodes on, the ABS
    // server polls the feed on its cron and pulls new episodes for the library.
    val autoDownloadEpisodes: Boolean = false,
    val autoDownloadSchedule: String? = null,
    val maxEpisodesToKeep: Int = 0,
    val maxNewEpisodesToDownload: Int = 0,
)

/** One podcast episode on a podcast library item (media.episodes[]). */
@Serializable
data class AbsPodcastEpisode(
    val id: String = "",
    val index: Int = 0,
    val season: String? = null,
    val episode: String? = null,
    val title: String = "",
    val subtitle: String? = null,
    val description: String? = null,
    // RSS pubDate string plus the parsed epoch-ms ABS derives from it.
    val pubDate: String? = null,
    val publishedAt: Long? = null,
    // Episode duration may live on the episode or only on its audioFile.
    val duration: Double? = null,
    val size: Long? = null,
    val audioFile: AbsAudioFile? = null,
) {
    /** Best available duration (episode field, else the audio file's). */
    val durationS: Double? get() = duration ?: audioFile?.duration?.takeIf { it > 0 }
}

@Serializable
data class AbsAudioFile(
    val index: Int = 0,
    val ino: String = "",
    val duration: Double = 0.0,
    val mimeType: String? = null,
    val metadata: AbsFileMetadata = AbsFileMetadata(),
)

@Serializable
data class AbsEbookFile(
    val ino: String = "",
    val ebookFormat: String? = null,
    val metadata: AbsFileMetadata = AbsFileMetadata(),
)

@Serializable
data class AbsFileMetadata(
    val filename: String = "",
    val ext: String? = null,
    val size: Long = 0,
)

@Serializable
data class AbsMetadata(
    val title: String? = null,
    val subtitle: String? = null,
    // Full arrays: present on the expanded item detail (GET /api/items/{id}?expanded=1).
    val authors: List<AbsAuthorRef> = emptyList(),
    val narrators: List<String> = emptyList(),
    val series: List<AbsSeriesRef> = emptyList(),
    // Minified strings: the library-items LIST endpoint returns ONLY these (the
    // arrays above are absent in the minified projection), comma-joined, with the
    // series sequence embedded as "Name #3". LibrarySyncer falls back to these
    // when the arrays are empty (verified against ABS 2.35.1 Book minified JSON).
    val authorName: String? = null,
    val narratorName: String? = null,
    val seriesName: String? = null,
    val isbn: String? = null,
    val asin: String? = null,
    val publishedYear: String? = null,
    val explicit: Boolean = false,
    val abridged: Boolean = false,
    // Expanded item detail only (never in the minified list projection).
    val description: String? = null,
    // --- podcast metadata (mediaType == "podcast") ---
    // Podcasts carry a single `author` string (not the authors[] array), plus the
    // RSS feed URL and a cover image URL from the feed.
    val author: String? = null,
    val feedUrl: String? = null,
    val imageUrl: String? = null,
)

@Serializable
data class AbsListeningSessionsResponse(
    val total: Int = 0,
    val numPages: Int = 0,
    val page: Int = 0,
    val sessions: List<AbsListeningSession> = emptyList(),
)

@Serializable
data class AbsListeningSession(
    val id: String = "",
    val libraryItemId: String = "",
    /** The position (seconds) this session ended at. */
    val currentTime: Double = 0.0,
)

@Serializable
data class AbsAuthorRef(val id: String = "", val name: String = "")

@Serializable
data class AbsSeriesRef(val id: String = "", val name: String = "", val sequence: String? = null)

@Serializable
data class AbsChapter(
    val id: Int = 0,
    val start: Double = 0.0,
    val end: Double = 0.0,
    val title: String = "",
)

@Serializable
data class AbsUser(
    val id: String,
    val username: String = "",
    // "root" | "admin" | "user" | "guest". Root/admin can always add podcasts.
    val type: String = "user",
    val permissions: AbsPermissions? = null,
    val mediaProgress: List<AbsMediaProgress> = emptyList(),
    val bookmarks: List<AbsBookmark> = emptyList(),
) {
    /** Whether this account may create a podcast subscription on the server. */
    val canUpload: Boolean
        get() = type == "root" || type == "admin" || permissions?.upload == true
}

@Serializable
data class AbsPermissions(
    val upload: Boolean = false,
    val update: Boolean = false,
    val delete: Boolean = false,
    val download: Boolean = true,
)

@Serializable
data class AbsBookmark(
    val libraryItemId: String = "",
    /** Position in seconds. Also the bookmark's identity for DELETE. */
    val time: Long = 0,
    val title: String = "",
    val createdAt: Long = 0,
)

@Serializable
data class AbsBookmarkRequest(val time: Long, val title: String)

@Serializable
data class AbsMediaProgress(
    /** The media-progress RECORD id (distinct from libraryItemId) — the key the
     * DELETE endpoint needs to discard progress. Absent in some projections. */
    val id: String? = null,
    val libraryItemId: String,
    // Set on podcast-episode progress records; null for books. Podcast progress
    // is keyed per (libraryItemId, episodeId).
    val episodeId: String? = null,
    val progress: Double = 0.0,
    val currentTime: Double = 0.0,
    val isFinished: Boolean = false,
    val ebookLocation: String? = null,
    val ebookProgress: Double? = null,
    val lastUpdate: Long = 0,
)

@Serializable
data class AbsPlaybackSession(
    val id: String,
    val libraryItemId: String = "",
    val currentTime: Double = 0.0,
    val audioTracks: List<AbsAudioTrack> = emptyList(),
    val chapters: List<AbsChapter> = emptyList(),
)

/**
 * Request body for POST /api/items/{id}/play (verified against ABS 2.35.1
 * PlaybackSessionManager.startSessionRequest). `supportedMimeTypes` gates
 * direct-play vs transcode: list the codecs ExoPlayer can decode so ABS serves
 * the original files (contentUrl → /api/items/{id}/file/{ino}) instead of HLS.
 */
@Serializable
data class AbsPlayRequest(
    val deviceInfo: AbsDeviceInfo = AbsDeviceInfo(),
    val mediaPlayer: String = "exoplayer",
    val forceDirectPlay: Boolean = false,
    val forceTranscode: Boolean = false,
    // Everything ExoPlayer decodes natively: ABS direct-plays files whose mime
    // is listed here and falls back to HLS otherwise (runtime-verified: an
    // EMPTY list made ABS transcode even common m4b/mp3 to HLS).
    val supportedMimeTypes: List<String> = listOf(
        "audio/flac",
        "audio/mpeg",
        "audio/mp3",
        "audio/mp4",
        "audio/aac",
        "audio/x-m4a",
        "audio/x-m4b",
        "audio/ogg",
        "audio/opus",
        "audio/webm",
        "audio/wav",
    ),
)

/**
 * `contentUrl` is a SERVER-RELATIVE path — `/api/items/{id}/file/{ino}` for
 * direct play (or an `/hls/...` path when transcoding). It carries NO `?token=`;
 * auth is the `Authorization: Bearer` header (verified against ABS 2.35.1
 * objects/files/AudioTrack.js). The ExoPlayer data source must resolve it
 * against the server base URL and attach the per-server bearer header.
 */
@Serializable
data class AbsAudioTrack(
    val index: Int = 0,
    val startOffset: Double = 0.0,
    val duration: Double = 0.0,
    val title: String? = null,
    val contentUrl: String = "",
    val mimeType: String? = null,
    val codec: String? = null,
)

@Serializable
data class AbsSessionSyncBody(
    val currentTime: Double,
    val timeListened: Double,
    val duration: Double? = null,
)

/**
 * A locally recorded listening session, uploaded in batch via
 * POST /api/session/local-all. This — never PATCH /api/me/progress — is the
 * correct channel for audio progress.
 *
 * Verified against ABS 2.35.1 (PlaybackSessionManager.syncLocalSession):
 *  - Dedupe is by `id`: the server looks up an existing PlaybackSession by this
 *    id and, if found, only advances `currentTime`, `timeListening`, `updatedAt`,
 *    `date`, `dayOfWeek`; otherwise it creates a new one. So re-uploading the
 *    same client-generated `id` is idempotent and safe — keep the id stable for
 *    the lifetime of a local session.
 *  - `displayTitle`, `date`, and `dayOfWeek` are consumed by the server; include
 *    them so a freshly-created (never-synced) session lands complete.
 *  - `startedAt`/`updatedAt` are epoch millis; `dayOfWeek` is the weekday name
 *    (e.g. "Monday") of `startedAt`.
 */
@Serializable
data class AbsLocalSession(
    val id: String,
    val libraryItemId: String,
    val episodeId: String? = null,
    val mediaPlayer: String = "exoplayer",
    val deviceInfo: AbsDeviceInfo = AbsDeviceInfo(),
    val displayTitle: String = "",
    val date: String = "",
    val dayOfWeek: String = "",
    val startTime: Double = 0.0,
    val currentTime: Double = 0.0,
    val timeListening: Double = 0.0,
    val duration: Double? = null,
    val startedAt: Long = 0,
    val updatedAt: Long = 0,
)

@Serializable
data class AbsDeviceInfo(
    val manufacturer: String = "",
    val model: String = "",
    val sdkVersion: Int = 0,
    val clientName: String = "audex",
    val clientVersion: String = "",
)

@Serializable
data class AbsLocalSessionsBody(val sessions: List<AbsLocalSession>)

/** Ebook position write — the one legitimate use of the progress PATCH. */
@Serializable
data class AbsEbookProgressBody(
    val ebookLocation: String? = null,
    val ebookProgress: Double? = null,
    val isFinished: Boolean? = null,
)

/**
 * Reset a book's media progress to the start (the "discard progress" action).
 * All fields zeroed so both the audio (currentTime/progress) and ebook
 * (ebookProgress) positions clear and the item is no longer marked finished.
 */
@Serializable
data class AbsProgressResetBody(
    val currentTime: Double = 0.0,
    val progress: Double = 0.0,
    val ebookProgress: Double = 0.0,
    val isFinished: Boolean = false,
)

/**
 * Response of POST /auth/refresh AND POST /login (verified against ABS 2.35.1).
 * The tokens are nested under `user`; `user.refreshToken` is only populated when
 * the refresh token was supplied via the `x-refresh-token` header (mobile), not
 * the cookie. `AbsTokenRefresher` parses this shape manually.
 */
@Serializable
data class AbsLoginResponse(val user: AbsLoginUser = AbsLoginUser())

@Serializable
data class AbsLoginUser(
    val id: String = "",
    val username: String = "",
    val accessToken: String? = null,
    val refreshToken: String? = null,
)

// --- podcasts: search, feed preview, subscribe ---

/**
 * One result of GET /api/search/podcasts?term= — the iTunes-normalized shape ABS
 * returns. `artistName` is the podcast author; `feedUrl` is what a subscription
 * is created from.
 */
@Serializable
data class AbsPodcastSearchResult(
    val id: Int? = null,
    val title: String = "",
    val artistName: String? = null,
    val description: String? = null,
    val descriptionPlain: String? = null,
    val cover: String? = null,
    val feedUrl: String = "",
    val genres: List<String> = emptyList(),
    val trackCount: Int = 0,
    val explicit: Boolean = false,
)

/** Body for POST /api/podcasts/feed — preview a raw RSS URL before subscribing. */
@Serializable
data class AbsPodcastFeedRequest(val rssFeed: String)

@Serializable
data class AbsPodcastFeedResponse(val podcast: AbsPodcastFeed = AbsPodcastFeed())

@Serializable
data class AbsPodcastFeed(
    val metadata: AbsPodcastFeedMetadata = AbsPodcastFeedMetadata(),
    val episodes: List<AbsPodcastFeedEpisode> = emptyList(),
    val numEpisodes: Int = 0,
)

@Serializable
data class AbsPodcastFeedMetadata(
    val title: String? = null,
    val author: String? = null,
    val description: String? = null,
    val descriptionPlain: String? = null,
    // ABS 2.35.1 sends `image` and `categories` here (NOT imageUrl/genres —
    // verified against the live feed endpoint); without the mapping the
    // subscribe preview silently showed no cover.
    @SerialName("image") val imageUrl: String? = null,
    val feedUrl: String? = null,
    @SerialName("categories") val genres: List<String> = emptyList(),
)

@Serializable
data class AbsPodcastFeedEpisode(
    val title: String? = null,
    val subtitle: String? = null,
    val description: String? = null,
    val pubDate: String? = null,
    val publishedAt: Long? = null,
    val duration: Double? = null,
    val enclosure: AbsEnclosure? = null,
)

@Serializable
data class AbsEnclosure(
    val url: String? = null,
    val length: String? = null,
    val type: String? = null,
)

/**
 * Body for POST /api/podcasts — creates the podcast library item (the
 * subscription). `path` is the absolute directory ABS will create for it
 * (folder.fullPath + "/" + sanitized title); `folderId`/`libraryId` target a
 * podcast library. `media.autoDownloadEpisodes` turns on server-side polling.
 */
@Serializable
data class AbsPodcastCreateRequest(
    val libraryId: String,
    val folderId: String,
    val path: String,
    val media: AbsPodcastCreateMedia,
)

@Serializable
data class AbsPodcastCreateMedia(
    val metadata: AbsPodcastCreateMetadata,
    val autoDownloadEpisodes: Boolean = false,
)

@Serializable
data class AbsPodcastCreateMetadata(
    val title: String,
    val author: String? = null,
    val description: String? = null,
    val feedUrl: String,
    val imageUrl: String? = null,
)

/**
 * Body for PATCH /api/items/{id}/media — updates a podcast's server-side
 * subscription settings. Nulls are omitted (explicitNulls=false), so this only
 * changes the fields you set.
 */
@Serializable
data class AbsPodcastSettingsBody(
    val autoDownloadEpisodes: Boolean? = null,
    val autoDownloadSchedule: String? = null,
    val maxEpisodesToKeep: Int? = null,
    val maxNewEpisodesToDownload: Int? = null,
)
