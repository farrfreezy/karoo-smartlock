package io.github.farrfreezy.karoosmartlock.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.farrfreezy.karoosmartlock.core.SmartLockSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

val Context.smartLockDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler {
        Log.w("SmartLock", "Error reading settings, using defaults")
        emptyPreferences()
    },
)

/**
 * Settings persisted as a single JSON blob in Preferences DataStore — the
 * community-standard pattern for Karoo extensions. The running extension
 * service observes [settingsFlow] so UI edits apply live.
 */
class SettingsRepository(private val context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    val settingsFlow: Flow<SmartLockSettings> = context.smartLockDataStore.data
        .map { prefs -> decode(prefs[SETTINGS_KEY]) }
        .distinctUntilChanged()

    suspend fun update(transform: (SmartLockSettings) -> SmartLockSettings) {
        context.smartLockDataStore.edit { prefs ->
            prefs[SETTINGS_KEY] = json.encodeToString(
                SmartLockSettings.serializer(),
                transform(decode(prefs[SETTINGS_KEY])),
            )
        }
    }

    private fun decode(raw: String?): SmartLockSettings = raw?.let {
        runCatching { json.decodeFromString(SmartLockSettings.serializer(), it) }.getOrNull()
    } ?: SmartLockSettings()

    companion object {
        private val SETTINGS_KEY = stringPreferencesKey("smartlock_settings")
    }
}
