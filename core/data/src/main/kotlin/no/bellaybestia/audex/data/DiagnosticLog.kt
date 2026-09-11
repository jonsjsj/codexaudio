package no.bellaybestia.audex.data

import android.util.Log
import timber.log.Timber

/**
 * Small in-memory ring buffer of recent log lines, for the "Send report"
 * diagnostic attachment (see ReportsRepository.buildDiagnostics) — the app
 * ships as a debug build, but logcat still isn't something a user can hand
 * over, so this keeps a copy in-process that a report CAN carry along.
 * Nothing sensitive is logged here beyond what the app already logs normally
 * (no server URLs or tokens are ever put through Timber).
 */
object DiagnosticLog {
    private const val MAX_LINES = 300
    private val lock = Any()
    private val lines = ArrayDeque<String>()

    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    fun add(line: String) {
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
    }
}

/** Timber tree that mirrors every log line into [DiagnosticLog]. Planted
 *  unconditionally in AudexApp — separate from the DebugTree (which only
 *  forwards to logcat and is gated to debuggable builds). */
class DiagnosticLogTree : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val level = when (priority) {
            Log.VERBOSE -> "V"
            Log.DEBUG -> "D"
            Log.INFO -> "I"
            Log.WARN -> "W"
            Log.ERROR -> "E"
            else -> "?"
        }
        val line = "$level/${tag.orEmpty()}: $message"
        DiagnosticLog.add(if (t != null) "$line\n${t.stackTraceToString()}" else line)
    }
}
