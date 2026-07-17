package no.bellaybestia.audex.domain.settings

/** Listening figures from the server (all clients, all time). */
data class ListeningStats(
    val totalMinutes: Long,
    val todayMinutes: Long,
    val weekMinutes: Long,
    val daysActive: Int,
    val booksStarted: Int,
)

interface StatsRepository {
    /** Fetch listening stats from the connected server; null offline / no server. */
    suspend fun listening(): ListeningStats?
}
