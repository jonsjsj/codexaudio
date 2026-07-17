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
import no.bellaybestia.audex.domain.settings.HomeLook
import no.bellaybestia.audex.domain.settings.ProgressUnit
import no.bellaybestia.audex.domain.settings.ThemeMode
import no.bellaybestia.audex.domain.settings.ThemePrefs
import no.bellaybestia.audex.domain.settings.ThemeSettings
import no.bellaybestia.audex.domain.settings.UpdateChannel
import no.bellaybestia.audex.domain.settings.UpdateSettings

private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")

/**
 * Accent moved to a v2 key when COVER became the default ("Evolved" redesign).
 * The legacy `theme_accent` is deliberately retired rather than migrated: it was
 * written on every save, so every existing install carries the OLD default
 * (BLUE) whether or not the user ever chose it — reading it would keep them off
 * the new cover-derived look forever. Absent v2 = "never picked an accent" =
 * COVER. Once the user picks one, it lands here and sticks.
 */
private val KEY_THEME_ACCENT_V2 = stringPreferencesKey("theme_accent_v2")
private val KEY_HOME_LOOK = stringPreferencesKey("home_look")
private val KEY_PROGRESS_UNIT = stringPreferencesKey("progress_unit")
private val KEY_UPDATE_CHANNEL = stringPreferencesKey("update_channel")

@Singleton
class ThemeSettingsImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : ThemeSettings {

    override val prefs: Flow<ThemePrefs> = context.appSettingsDataStore.data.map { p ->
        ThemePrefs(
            mode = p[KEY_THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.DARK,
            accent = p[KEY_THEME_ACCENT_V2]?.let { runCatching { AccentChoice.valueOf(it) }.getOrNull() }
                ?: AccentChoice.COVER,
            look = p[KEY_HOME_LOOK]?.let { runCatching { HomeLook.valueOf(it) }.getOrNull() }
                ?: HomeLook.NIGHTFALL,
            progressUnit = p[KEY_PROGRESS_UNIT]
                ?.let { runCatching { ProgressUnit.valueOf(it) }.getOrNull() }
                ?: ProgressUnit.PERCENT,
        )
    }

    override suspend fun set(prefs: ThemePrefs) {
        context.appSettingsDataStore.edit { p ->
            p[KEY_THEME_MODE] = prefs.mode.name
            p[KEY_THEME_ACCENT_V2] = prefs.accent.name
            p[KEY_HOME_LOOK] = prefs.look.name
            p[KEY_PROGRESS_UNIT] = prefs.progressUnit.name
        }
    }
}

@Singleton
class UpdateSettingsImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : UpdateSettings {

    override val channel: Flow<UpdateChannel> = context.appSettingsDataStore.data.map { p ->
        p[KEY_UPDATE_CHANNEL]?.let { runCatching { UpdateChannel.valueOf(it) }.getOrNull() }
            ?: UpdateChannel.STABLE
    }

    override suspend fun setChannel(channel: UpdateChannel) {
        context.appSettingsDataStore.edit { p -> p[KEY_UPDATE_CHANNEL] = channel.name }
    }
}
