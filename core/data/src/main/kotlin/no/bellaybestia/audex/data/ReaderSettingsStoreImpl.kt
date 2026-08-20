package no.bellaybestia.audex.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import no.bellaybestia.audex.domain.reader.ReaderBarPosition
import no.bellaybestia.audex.domain.reader.ReaderPrefs
import no.bellaybestia.audex.domain.reader.ReaderSettingsStore
import no.bellaybestia.audex.domain.reader.ReaderTheme

private val KEY_FONT_PCT = intPreferencesKey("reader_font_pct")
private val KEY_THEME = stringPreferencesKey("reader_theme")
private val KEY_BAR_POS = stringPreferencesKey("reader_bar_position")
private val KEY_READALONG = booleanPreferencesKey("reader_readalong")

@Singleton
class ReaderSettingsStoreImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : ReaderSettingsStore {

    override val prefs: Flow<ReaderPrefs> = context.appSettingsDataStore.data.map { p ->
        ReaderPrefs(
            fontSizePct = (p[KEY_FONT_PCT] ?: 100).coerceIn(70, 200),
            theme = p[KEY_THEME]?.let { stored ->
                ReaderTheme.entries.firstOrNull { it.name == stored }
            } ?: ReaderTheme.LIGHT,
            barPosition = p[KEY_BAR_POS]?.let { stored ->
                ReaderBarPosition.entries.firstOrNull { it.name == stored }
            } ?: ReaderBarPosition.BOTTOM,
            readAlong = p[KEY_READALONG] ?: true,
        )
    }

    override suspend fun set(prefs: ReaderPrefs) {
        context.appSettingsDataStore.edit { p ->
            p[KEY_FONT_PCT] = prefs.fontSizePct.coerceIn(70, 200)
            p[KEY_THEME] = prefs.theme.name
            p[KEY_BAR_POS] = prefs.barPosition.name
            p[KEY_READALONG] = prefs.readAlong
        }
    }
}
