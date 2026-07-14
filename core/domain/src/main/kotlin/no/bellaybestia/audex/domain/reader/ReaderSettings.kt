package no.bellaybestia.audex.domain.reader

import kotlinx.coroutines.flow.Flow

/** Reader appearance themes (mapped to Readium's Theme in the feature layer). */
enum class ReaderTheme { LIGHT, SEPIA, DARK }

/** Persisted reader appearance. Font size is percent of publisher default. */
data class ReaderPrefs(
    val fontSizePct: Int = 100,
    val theme: ReaderTheme = ReaderTheme.LIGHT,
)

interface ReaderSettingsStore {
    val prefs: Flow<ReaderPrefs>
    suspend fun set(prefs: ReaderPrefs)
}
