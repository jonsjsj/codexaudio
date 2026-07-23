package no.bellaybestia.audex.domain.model

/**
 * Podcast domain models. Podcasts live in a pipeline parallel to the
 * Authors→Series→Works catalog graph: they are never de-duplicated across
 * servers and never paired into editions, so they carry their own (serverId,
 * libraryItemId) identity end to end.
 */
data class Podcast(
    val serverId: String,
    val libraryItemId: String,
    val title: String,
    val author: String? = null,
    val description: String? = null,
    val feedUrl: String? = null,
    /** Server-side auto-download ("subscription") is on for this podcast. */
    val autoDownload: Boolean = false,
    val numEpisodes: Int = 0,
    val coverUrl: String? = null,
)

/** One episode of a subscribed podcast, with the listener's progress. */
data class Episode(
    val serverId: String,
    val libraryItemId: String,
    val episodeId: String,
    val title: String,
    val subtitle: String? = null,
    val pubDate: String? = null,
    val publishedAt: Long? = null,
    val durationS: Double? = null,
    /** 0..1 listen progress; 1.0 when finished. */
    val progressFraction: Double = 0.0,
    val isFinished: Boolean = false,
    val currentTimeS: Double? = null,
) {
    val inProgress: Boolean get() = !isFinished && progressFraction > 0.0
}

/** A result from searching the server's podcast index (iTunes). */
data class PodcastSearchResult(
    val title: String,
    val author: String? = null,
    val description: String? = null,
    val coverUrl: String? = null,
    val feedUrl: String,
    val numEpisodes: Int = 0,
)

/** Preview of a raw RSS feed, shown before the user confirms a subscription. */
data class PodcastFeedPreview(
    val title: String,
    val author: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    val feedUrl: String,
    val numEpisodes: Int = 0,
    val recentEpisodes: List<FeedEpisode> = emptyList(),
)

data class FeedEpisode(
    val title: String,
    val pubDate: String? = null,
    val publishedAt: Long? = null,
)

/**
 * A podcast library on a connected server the user can subscribe into. Subscribe
 * needs both a podcast library with a folder AND an account with upload
 * permission — [canSubscribe] gates the "Subscribe" affordance in the UI.
 */
data class PodcastLibraryTarget(
    val serverId: String,
    val serverName: String,
    val libraryId: String,
    val libraryName: String,
    val folderId: String,
    val folderPath: String,
    val canSubscribe: Boolean,
)
