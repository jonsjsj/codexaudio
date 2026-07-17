package no.bellaybestia.audex.domain.settings

import kotlinx.coroutines.flow.Flow

/** App-wide theme mode. Codex is dark-only; Audex defaults dark but stays flexible. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Accent choices. COVER is the default and the point of the "Evolved" design:
 * the whole palette is derived from the current book's cover art, so the app
 * re-tints as you move between books. The rest are fixed accents for anyone who
 * wants it to hold still (MONO is Codex's monochrome; BLUE/GOLD/CYAN pin a hue).
 */
enum class AccentChoice { COVER, MONO, BLUE, GOLD, CYAN }

/**
 * The home/library look. NIGHTFALL is bold and modern — a full-bleed hero for
 * the book you're mid-way through. STACKS keeps the dark identity but pushes big
 * cover art and a strong type scale. Chosen in Settings → Appearance.
 */
enum class HomeLook { NIGHTFALL, STACKS }

/**
 * How the "Go to…" jump control (audio player + ebook reader) takes and shows a
 * target. PERCENT = a 0–100% of the book. EXACT = the format's native unit: a
 * timestamp (h:mm:ss) for the audiobook, a page number for the ebook.
 */
enum class ProgressUnit { PERCENT, EXACT }

data class ThemePrefs(
    val mode: ThemeMode = ThemeMode.DARK,
    val accent: AccentChoice = AccentChoice.COVER,
    val look: HomeLook = HomeLook.NIGHTFALL,
    val progressUnit: ProgressUnit = ProgressUnit.PERCENT,
)

/** Persisted appearance settings (impl in :core:data). */
interface ThemeSettings {
    val prefs: Flow<ThemePrefs>
    suspend fun set(prefs: ThemePrefs)
}

/**
 * Which OTA channel the in-app updater follows. STABLE = releases from main
 * (audex-latest.json); BETA = the report-autofix channel (audex-beta-latest.json).
 */
enum class UpdateChannel { STABLE, BETA }

/** Persisted updater settings (impl in :core:data). */
interface UpdateSettings {
    val channel: Flow<UpdateChannel>
    suspend fun setChannel(channel: UpdateChannel)
}
