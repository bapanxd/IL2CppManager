package dev.ruri.il2cppmanager.preferences

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.managerPreferences by preferencesDataStore(name = "manager_preferences")

internal class ThemePreferenceStore(context: Context) {
    private val applicationContext = context.applicationContext

    val darkTheme: Flow<Boolean?> = applicationContext.managerPreferences.data
        .catch { exception ->
            if (exception !is IOException) throw exception
            Log.w(LogTag, "Unable to read the theme preference", exception)
            emit(emptyPreferences())
        }
        .map { preferences -> preferences[DarkThemeKey] ?: false }

    suspend fun setDarkTheme(enabled: Boolean) {
        applicationContext.managerPreferences.edit { preferences ->
            preferences[DarkThemeKey] = enabled
        }
    }

    private companion object {
        const val LogTag = "ThemePreferenceStore"
        val DarkThemeKey = booleanPreferencesKey("dark_theme")
    }
}
