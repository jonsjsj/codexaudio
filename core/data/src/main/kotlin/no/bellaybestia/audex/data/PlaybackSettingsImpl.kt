package no.bellaybestia.audex.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import no.bellaybestia.audex.domain.settings.PlaybackSettings
import javax.inject.Inject
import javax.inject.Singleton

private val KEY_SKIP_SILENCE = booleanPreferencesKey("playback_skip_silence")
private val KEY_SKIP_SECONDS = intPreferencesKey("playback_skip_seconds")
private val KEY_MERGE_PROGRESS = booleanPreferencesKey("playback_merge_progress")

/** Default skip amount, and the values the Settings toggle offers. */
const val DEFAULT_SKIP_SECONDS = 30
val SKIP_SECONDS_OPTIONS = listOf(10, 30)

@Singleton
class PlaybackSettingsImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : PlaybackSettings {

    override val skipSilence: Flow<Boolean> =
        context.appSettingsDataStore.data.map { it[KEY_SKIP_SILENCE] ?: false }

    override suspend fun setSkipSilence(enabled: Boolean) {
        context.appSettingsDataStore.edit { it[KEY_SKIP_SILENCE] = enabled }
    }

    override val skipSeconds: Flow<Int> =
        context.appSettingsDataStore.data.map { it[KEY_SKIP_SECONDS] ?: DEFAULT_SKIP_SECONDS }

    override suspend fun setSkipSeconds(seconds: Int) {
        context.appSettingsDataStore.edit { it[KEY_SKIP_SECONDS] = seconds }
    }

    override val mergeProgress: Flow<Boolean> =
        context.appSettingsDataStore.data.map { it[KEY_MERGE_PROGRESS] ?: false }

    override suspend fun setMergeProgress(merged: Boolean) {
        context.appSettingsDataStore.edit { it[KEY_MERGE_PROGRESS] = merged }
    }
}
