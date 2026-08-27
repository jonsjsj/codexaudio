package no.bellaybestia.audex.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import no.bellaybestia.audex.domain.settings.NotificationPrefs
import no.bellaybestia.audex.domain.settings.NotificationSettings

private val KEY_NOTIF_UPDATES = booleanPreferencesKey("notif_app_updates")
private val KEY_NOTIF_READALONG = booleanPreferencesKey("notif_readalong")

@Singleton
class NotificationSettingsImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : NotificationSettings {

    override val prefs: Flow<NotificationPrefs> = context.appSettingsDataStore.data.map { p ->
        NotificationPrefs(
            appUpdates = p[KEY_NOTIF_UPDATES] ?: true,
            readAlong = p[KEY_NOTIF_READALONG] ?: true,
        )
    }

    override suspend fun set(prefs: NotificationPrefs) {
        context.appSettingsDataStore.edit { p ->
            p[KEY_NOTIF_UPDATES] = prefs.appUpdates
            p[KEY_NOTIF_READALONG] = prefs.readAlong
        }
    }
}
