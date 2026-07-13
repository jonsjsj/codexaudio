package no.bellaybestia.codexaudio.network.abs

import kotlinx.serialization.Serializable

/**
 * Minimal DTOs for the Audiobookshelf REST API — only the fields this app
 * consumes. Field names follow the ABS payloads; anything uncertain is flagged
 * [verify] in docs/03-abs-api-usage.md and must be checked against the deployed
 * server version before Phase-1 completion.
 */
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
)

@Serializable
data class AbsMetadata(
    val title: String? = null,
    val subtitle: String? = null,
    val authors: List<AbsAuthorRef> = emptyList(),
    val narrators: List<String> = emptyList(),
    val series: List<AbsSeriesRef> = emptyList(),
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

@Serializable
data class AbsAudioTrack(
    val index: Int = 0,
    val startOffset: Double = 0.0,
    val duration: Double = 0.0,
    val contentUrl: String = "",
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
 * correct channel for audio progress; the schema must be [verify]-checked
 * against the deployed ABS version (docs/03 §3.3).
 */
@Serializable
data class AbsLocalSession(
    val id: String,
    val libraryItemId: String,
    val episodeId: String? = null,
    val mediaPlayer: String = "exoplayer",
    val deviceInfo: AbsDeviceInfo = AbsDeviceInfo(),
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
    val clientName: String = "codex-audio",
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

@Serializable
data class AbsRefreshResponse(
    val accessToken: String? = null,
    val refreshToken: String? = null,
)
