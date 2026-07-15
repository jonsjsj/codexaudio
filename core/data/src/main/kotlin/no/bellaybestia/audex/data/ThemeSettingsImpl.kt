package no.bellaybestia.audex.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import no.bellaybestia.audex.domain.settings.AccentChoice
import no.bellaybestia.audex.domain.settings.ThemeMode
import no.bellaybestia.audex.domain.settings.ThemePrefs
import no.bellaybestia.audex.domain.settings.ThemeSettings

private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
private val KEY_THEME_ACCENT = stringPreferencesKey("theme_accent")

@Singleton
class ThemeSettingsImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : ThemeSettings {

    override val prefs: Flow<ThemePrefs> = context.appSettingsDataStore.data.map { p ->
        ThemePrefs(
            mode = p[KEY_THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.DARK,
            accent = p[KEY_THEME_ACCENT]?.let { runCatching { AccentChoice.valueOf(it) }.getOrNull() }
                ?: AccentChoice.BLUE,
        )
    }

    override suspend fun set(prefs: ThemePrefs) {
        context.appSettingsDataStore.edit { p ->
            p[KEY_THEME_MODE] = prefs.mode.name
            p[KEY_THEME_ACCENT] = prefs.accent.name
        }
    }
}
