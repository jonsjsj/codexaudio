package no.bellaybestia.audex.domain.reader

import kotlinx.coroutines.flow.Flow

/** Reader appearance themes (mapped to Readium's Theme in the feature layer). */
enum class ReaderTheme { LIGHT, SEPIA, DARK }

/** Where the reader's controls bar sits when revealed (folded away otherwise). */
enum class ReaderBarPosition { TOP, BOTTOM }

/** Persisted reader appearance. Font size is percent of publisher default. */
data class ReaderPrefs(
    val fontSizePct: Int = 100,
    val theme: ReaderTheme = ReaderTheme.LIGHT,
    /** Edge the controls bar appears at when you tap the centre of the page. */
    val barPosition: ReaderBarPosition = ReaderBarPosition.BOTTOM,
)

interface ReaderSettingsStore {
    val prefs: Flow<ReaderPrefs>
    suspend fun set(prefs: ReaderPrefs)
}
