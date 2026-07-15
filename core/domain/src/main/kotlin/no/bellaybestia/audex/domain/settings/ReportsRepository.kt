package no.bellaybestia.audex.domain.settings

/** What kind of report the user is filing (mirrors Codex's reporter). */
enum class ReportKind { BUG, IDEA, FEEDBACK }

/** A filed report's public reference (GitHub issue). */
data class FiledReport(val number: Int, val url: String)

/**
 * Files user reports (bug/idea/feedback) to the project's issue tracker via the
 * self-hosted alignment service, which holds the GitHub token server-side — the
 * app never embeds credentials. Impl in :core:data.
 */
interface ReportsRepository {
    /**
     * Submit a report. [appVersion] is stamped into the body so triage knows
     * the build. Throws on failure (no service configured / network / server).
     */
    suspend fun submit(kind: ReportKind, title: String, body: String, appVersion: String): FiledReport
}
