package no.bellaybestia.audex.domain.model

data class Author(
    val id: String,
    val name: String,
    val workCount: Int = 0,
)

data class Series(
    val id: String,
    val name: String,
    val authorName: String? = null,
    val workCount: Int = 0,
    val completedCount: Int = 0,
)

/**
 * One canonical work with its dual progress. Fractions are 0..1 and follow
 * Codex `_edition_fraction` semantics: the max across that format's editions.
 */
data class Work(
    val id: String,
    val title: String,
    val authorName: String? = null,
    val seriesName: String? = null,
    val seriesPosition: Double? = null,
    val subSeriesName: String? = null,
    val year: Int? = null,
    val hasAudio: Boolean = false,
    val hasEbook: Boolean = false,
    val listenFraction: Double = 0.0,
    val readFraction: Double = 0.0,
)

enum class Format { AUDIO, EBOOK }

data class Edition(
    val id: String,
    val workId: String,
    val format: Format,
    val serverId: String,
    val libraryItemId: String,
    val durationS: Long? = null,
    val fraction: Double = 0.0,
)

data class ServerAccount(
    val serverId: String,
    val name: String,
    val baseUrl: String,
    val enabled: Boolean = true,
    val needsLogin: Boolean = false,
)
