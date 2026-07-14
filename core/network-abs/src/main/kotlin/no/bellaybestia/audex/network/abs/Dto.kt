package no.bellaybestia.audex.network.abs

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
)

@Serializable
data class AbsItemsPage(
    val results: List<AbsLibraryItem> = emptyList(),
    val total: Int = 0,
    val page: Int = 0,
    val limit: Int = 0,
)

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
)

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
    val mediaProgress: List<AbsMediaProgress> = emptyList(),
)

@Serializable
data class AbsMediaProgress(
    val libraryItemId: String,
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
    val forceDirectPlay: Boolean = true,
    val forceTranscode: Boolean = false,
    val supportedMimeTypes: List<String> = emptyList(),
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
