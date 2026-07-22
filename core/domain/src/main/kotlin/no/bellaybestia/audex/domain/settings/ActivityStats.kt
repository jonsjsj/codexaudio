package no.bellaybestia.audex.domain.settings

/** The two things Audex measures time on. Maps to the DB `activity.kind`. */
enum class ActivityKind(val db: String) {
    LISTEN("AUDIO"),
    READ("EBOOK"),
}

/** Summed figures for one kind of activity. Seconds, so the UI formats them. */
data class KindStats(
    val totalSeconds: Long = 0,
    val todaySeconds: Long = 0,
    val weekSeconds: Long = 0,
    val daysActive: Int = 0,
    val books: Int = 0,
)

/** Both halves of "Your activity" — computed locally from recorded time. */
data class ActivityStats(
    val listen: KindStats = KindStats(),
    val read: KindStats = KindStats(),
)

/** One book's contribution to a kind's total — the drill-down row. */
data class BookActivity(
    val serverId: String,
    val libraryItemId: String,
    val title: String,
    val coverUrl: String?,
    val seconds: Long,
)

/** Reads the local activity ledger and (once) seeds it from the server. */
interface ActivityStatsRepository {
    suspend fun stats(): ActivityStats
    /** Per-book breakdown for one kind, largest first. */
    suspend fun breakdown(kind: ActivityKind): List<BookActivity>
}

/** Records time actually spent listening / reading a book (accumulates). */
interface ActivityRecorder {
    suspend fun record(serverId: String, libraryItemId: String, kind: ActivityKind, seconds: Double)
}
