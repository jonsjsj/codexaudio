package no.bellaybestia.audex.domain.settings

import kotlinx.coroutines.flow.Flow

/** App-wide theme mode. Codex is dark-only; Audex defaults dark but stays flexible. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Accent choices, lifted from Codex's own palette themes (its CSS `--accent`
 * variants): monochrome near-white, blue, and gold — with their matching
 * on-accent contrast colors.
 */
enum class AccentChoice { MONO, BLUE, GOLD }

data class ThemePrefs(
    val mode: ThemeMode = ThemeMode.DARK,
    val accent: AccentChoice = AccentChoice.BLUE,
)

/** Persisted appearance settings (impl in :core:data). */
interface ThemeSettings {
    val prefs: Flow<ThemePrefs>
    suspend fun set(prefs: ThemePrefs)
}
