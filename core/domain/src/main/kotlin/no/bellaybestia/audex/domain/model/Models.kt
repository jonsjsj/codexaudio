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
    val authorId: String? = null,
    val authorName: String? = null,
    val seriesId: String? = null,
    val seriesName: String? = null,
    val seriesPosition: Double? = null,
    val subSeriesName: String? = null,
    val year: Int? = null,
    val hasAudio: Boolean = false,
    val hasEbook: Boolean = false,
    val listenFraction: Double = 0.0,
    val readFraction: Double = 0.0,
    /** ABS cover URL of a representative edition (null → placeholder). */
    val coverUrl: String? = null,
    /** Latest remote updatedAt (epoch ms) across the work's items — "recently added" proxy. */
    val updatedAt: Long? = null,
    /** When the work was last listened to / read (epoch ms, from the server's
     * progress timestamp) — used to feature the most recent book on Home. */
    val listenedAt: Long? = null,
)

/**
 * The ABS cover endpoint for an item. The app-wide image loader attaches the
 * right Bearer per host, so this is safe to hand straight to Coil
 * (ABS 2.35.1: GET /api/items/{id}/cover?width=).
 */
fun absCoverUrl(baseUrl: String, libraryItemId: String, width: Int = 400): String =
    "${baseUrl.trimEnd('/')}/api/items/$libraryItemId/cover?width=$width"

enum class Format { AUDIO, EBOOK }

data class Edition(
    val id: String,
    val workId: String,
    val format: Format,
    val serverId: String,
    val libraryItemId: String,
    val durationS: Long? = null,
    val fraction: Double = 0.0,
    val coverUrl: String? = null,
)

data class ServerAccount(
    val serverId: String,
    val name: String,
    val baseUrl: String,
    val enabled: Boolean = true,
    val needsLogin: Boolean = false,
)
