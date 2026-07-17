package no.bellaybestia.audex.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import no.bellaybestia.audex.domain.settings.PlaybackSettings
import javax.inject.Inject
import javax.inject.Singleton

private val KEY_SKIP_SILENCE = booleanPreferencesKey("playback_skip_silence")

@Singleton
class PlaybackSettingsImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : PlaybackSettings {

    override val skipSilence: Flow<Boolean> =
        context.appSettingsDataStore.data.map { it[KEY_SKIP_SILENCE] ?: false }

    override suspend fun setSkipSilence(enabled: Boolean) {
        context.appSettingsDataStore.edit { it[KEY_SKIP_SILENCE] = enabled }
    }
}
