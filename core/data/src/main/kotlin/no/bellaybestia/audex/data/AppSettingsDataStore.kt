package no.bellaybestia.audex.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * The single app-settings DataStore. All settings-ish stores in :core:data must
 * share this delegate — declaring a second preferencesDataStore with the same
 * file name crashes at runtime ("multiple DataStores active for the same file").
 */
internal val Context.appSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app_settings",
)
