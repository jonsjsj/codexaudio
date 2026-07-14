package no.bellaybestia.audex.domain.reader

/**
 * One anchor from a book's forced-alignment sync map (docs/10): the audio at
 * [t0]..[t1] seconds narrates the book around progression [p] (0..1 of the
 * whole spine text), inside chapter [href]. [text] (map v1.1) is the anchored
 * book string — the input for sentence highlighting.
 */
data class SyncAnchor(
    val t0: Double,
    val t1: Double,
    val p: Double,
    val href: String?,
    val text: String? = null,
    val c0: Int = 0,
)

/** Chapter char boundaries (map v1.1) — global offset → within-chapter progression. */
data class SyncChapter(val href: String, val c0: Int, val c1: Int)

/**
 * A book's audio↔text timing map produced by the audex-align service. Entries
 * are time-sorted. [progressionAt] answers "where in the book is the narration
 * at second t" — the precise replacement for proportional follow (docs/09).
 */
data class SyncMap(
    val durationS: Double,
    val anchors: List<SyncAnchor>,
    val chapters: List<SyncChapter> = emptyList(),
) {

    /** The anchor being narrated at [seconds] (nearest at-or-before by start time). */
    fun anchorAt(seconds: Double): SyncAnchor? {
        if (anchors.isEmpty() || seconds < anchors.first().t0) return null
        var lo = 0
        var hi = anchors.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (anchors[mid].t0 <= seconds) lo = mid else hi = mid - 1
        }
        return anchors[lo]
    }

    /** Progression of [anchor] within its own chapter (needs v1.1 chapter data). */
    fun chapterProgression(anchor: SyncAnchor): Double? {
        val chapter = chapters.firstOrNull { anchor.c0 in it.c0 until it.c1 } ?: return null
        val span = (chapter.c1 - chapter.c0).takeIf { it > 0 } ?: return null
        return ((anchor.c0 - chapter.c0).toDouble() / span).coerceIn(0.0, 1.0)
    }

    fun progressionAt(seconds: Double): Double? {
        if (anchors.isEmpty()) return null
        if (seconds <= anchors.first().t0) return anchors.first().p
        if (seconds >= anchors.last().t0) return anchors.last().p
        var lo = 0
        var hi = anchors.size - 1
        while (lo + 1 < hi) {
            val mid = (lo + hi) / 2
            if (anchors[mid].t0 <= seconds) lo = mid else hi = mid
        }
        // Linear interpolation between the surrounding anchors.
        val a = anchors[lo]
        val b = anchors[hi]
        val span = (b.t0 - a.t0).takeIf { it > 0.0 } ?: return a.p
        val f = ((seconds - a.t0) / span).coerceIn(0.0, 1.0)
        return a.p + (b.p - a.p) * f
    }
}

/** Job/availability status of word sync for one work. */
enum class WordSyncStatus { UNAVAILABLE, NONE, RUNNING, READY }

/**
 * Client for the self-hosted audex-align service (alignment-service/ in this
 * repo). The service URL is user configuration — the feature is hidden until
 * it's set. Maps are cached on disk so the reader works offline once fetched.
 */
interface AlignmentRepository {
    suspend fun serviceUrl(): String?
    suspend fun setServiceUrl(url: String?)

    /**
     * Queue alignment for a work: audio from [audioItemId]; the EPUB from
     * [ebookItemId] when the ebook is a separate ABS item on the same server.
     */
    suspend fun requestAlignment(
        serverId: String,
        audioItemId: String,
        ebookItemId: String?,
    ): Result<Unit>

    /** Fetch (or load cached) sync map for a work's audio item; null if none. */
    suspend fun syncMap(serverId: String, audioItemId: String): SyncMap?

    /** Coarse status for the work-detail row. */
    suspend fun status(serverId: String, audioItemId: String): WordSyncStatus
}
