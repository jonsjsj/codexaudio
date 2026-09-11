package no.bellaybestia.audex.domain.settings

import kotlinx.coroutines.flow.Flow

/** What kind of report the user is filing (mirrors Codex's reporter). */
enum class ReportKind { BUG, IDEA, FEEDBACK }

/** A filed report's public reference (GitHub issue). */
data class FiledReport(val number: Int, val url: String)

/** One of the user's own filed reports, with its tracked status (Codex pattern). */
data class MyReport(
    val number: Int,
    val url: String,
    val kind: ReportKind,
    val title: String,
    val createdAt: Long,
    /** "open" | "closed" (refreshed from the tracker). */
    val state: String = "open",
    /** Release that fixed it, when known — the closed loop. */
    val fixedIn: String? = null,
)

/**
 * Files user reports (bug/idea/feedback) to the project's issue tracker via the
 * self-hosted alignment service, which holds the GitHub token server-side — the
 * app never embeds credentials. Impl in :core:data.
 */
interface ReportsRepository {
    /**
     * Submit a report. [appVersion] is stamped into the body so triage knows
     * the build; [screen] (when known) tells triage where the user was.
     * [diagnostics], when provided (see [buildDiagnostics]), is attached as a
     * collapsed section in the issue body. Throws on failure (no service
     * configured / network / server).
     */
    suspend fun submit(
        kind: ReportKind,
        title: String,
        body: String,
        appVersion: String,
        screen: String? = null,
        diagnostics: String? = null,
    ): FiledReport

    /**
     * An anonymous diagnostic snapshot for "Send report": every saved progress
     * row (title, format, percent, seconds listened, ebook page fraction,
     * where it came from, when it was last touched) and the last ~300 in-app
     * log lines. No server URLs, tokens, or account info — just enough to
     * trace a reported bug without the user having to describe internal state
     * by hand.
     */
    suspend fun buildDiagnostics(): String

    /** Reports filed from this device, newest first. */
    val myReports: Flow<List<MyReport>>

    /** Refresh open reports' state/fixed-in from the tracker (best-effort). */
    suspend fun refreshMyReports()
}
