package tomate.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class TomateSettings(
    val themeMode: String = "System",
    val dynamicColor: Boolean = false,
    val relayEnabled: Boolean = true,
    val batterySaver: Boolean = false,
)

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "tomate_settings")

class SettingsStore(private val context: Context) {

    private object Keys {
        val themeMode = stringPreferencesKey("theme_mode")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val relayEnabled = booleanPreferencesKey("relay_enabled")
        val batterySaver = booleanPreferencesKey("battery_saver")
    }

    val settings: Flow<TomateSettings> = context.settingsDataStore.data.map { prefs ->
        TomateSettings(
            themeMode = prefs[Keys.themeMode] ?: "System",
            dynamicColor = prefs[Keys.dynamicColor] ?: false,
            relayEnabled = prefs[Keys.relayEnabled] ?: true,
            batterySaver = prefs[Keys.batterySaver] ?: false,
        )
    }

    suspend fun setThemeMode(mode: String) {
        context.settingsDataStore.edit { it[Keys.themeMode] = mode }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.dynamicColor] = enabled }
    }

    suspend fun setRelayEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.relayEnabled] = enabled }
    }

    suspend fun setBatterySaver(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.batterySaver] = enabled }
    }
}
