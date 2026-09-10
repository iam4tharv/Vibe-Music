

package com.music.echo.utils

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.music.echo.extensions.toEnum
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.properties.ReadOnlyProperty

import androidx.datastore.core.DataMigration

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    produceMigrations = { context ->
        listOf(
            object : DataMigration<Preferences> {
                override suspend fun cleanUp() {}
                override suspend fun migrate(currentData: Preferences): Preferences {
                    val floatKeyNames = setOf(
                        "density_scale_factor", "custom_density_scale_value",
                        "thumbnailCornerRadius", "crossfadeDuration", "scrobbleDelayPercent",
                        "historyDuration", "lyricsTextSize", "lyricsLineSpacing",
                        "playerVolume", "swipeSensitivity", "spatial_audio_strength",
                        "bass_boost", "virtualizer", "liquidGlassSurfaceOpacity",
                        "liquidGlassVibrancy", "liquidGlassBlurRadius",
                        "liquidGlassLensHeight", "liquidGlassLensAmount"
                    )
                    val longKeyNames = setOf(
                        "discord_token_expires_at", "last_like_song_sync", "last_library_song_sync",
                        "last_album_sync", "last_artist_sync", "last_playlist_sync",
                        "last_full_sync", "lastLosslessSync", "CipherLastUpdatedKey",
                        "CipherManualUpdate1Key", "CipherManualUpdate2Key", "CipherManualUpdate3Key"
                    )
                    
                    var modified = false
                    val mutablePrefs = currentData.toMutablePreferences()
                    for ((key, value) in currentData.asMap()) {
                        if (value is String) {
                            if (floatKeyNames.contains(key.name)) {
                                val floatVal = value.toFloatOrNull()
                                if (floatVal != null) {
                                    mutablePrefs[androidx.datastore.preferences.core.floatPreferencesKey(key.name)] = floatVal
                                    modified = true
                                }
                            } else if (value == "true" || value == "false") {
                                mutablePrefs[androidx.datastore.preferences.core.booleanPreferencesKey(key.name)] = value.toBoolean()
                                modified = true
                            } else if (longKeyNames.contains(key.name)) {
                                val longVal = value.toLongOrNull()
                                if (longVal != null) {
                                    mutablePrefs[androidx.datastore.preferences.core.longPreferencesKey(key.name)] = longVal
                                    modified = true
                                }
                            }
                        } else if (value is Int) {
                            if (longKeyNames.contains(key.name)) {
                                mutablePrefs[androidx.datastore.preferences.core.longPreferencesKey(key.name)] = value.toLong()
                                modified = true
                            } else if (floatKeyNames.contains(key.name)) {
                                mutablePrefs[androidx.datastore.preferences.core.floatPreferencesKey(key.name)] = value.toFloat()
                                modified = true
                            }
                        } else if (value is Long) {
                            if (floatKeyNames.contains(key.name)) {
                                mutablePrefs[androidx.datastore.preferences.core.floatPreferencesKey(key.name)] = value.toFloat()
                                modified = true
                            }
                        }
                    }
                    return if (modified) mutablePrefs else currentData
                }
                override suspend fun shouldMigrate(currentData: Preferences): Boolean = true
            }
        )
    }
)

operator fun <T> DataStore<Preferences>.get(key: Preferences.Key<T>): T? =
    runBlocking(Dispatchers.IO) {
        data.first()[key]
    }

fun <T> DataStore<Preferences>.get(
    key: Preferences.Key<T>,
    defaultValue: T,
): T =
    runBlocking(Dispatchers.IO) {
        data.first()[key] ?: defaultValue
    }

fun <T> preference(
    context: Context,
    key: Preferences.Key<T>,
    defaultValue: T,
) = ReadOnlyProperty<Any?, T> { _, _ -> context.dataStore[key] ?: defaultValue }

inline fun <reified T : Enum<T>> enumPreference(
    context: Context,
    key: Preferences.Key<String>,
    defaultValue: T,
) = ReadOnlyProperty<Any?, T> { _, _ -> context.dataStore[key].toEnum(defaultValue) }

@Composable
fun <T> rememberPreference(
    key: Preferences.Key<T>,
    defaultValue: T,
): MutableState<T> {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val state =
        remember {
            context.dataStore.data
                .map { it[key] ?: defaultValue }
                .distinctUntilChanged()
        }.collectAsState(defaultValue)

    return remember {
        object : MutableState<T> {
            override var value: T
                get() = state.value
                set(value) {
                    coroutineScope.launch {
                        context.dataStore.edit {
                            it[key] = value
                        }
                    }
                }

            override fun component1() = value

            override fun component2(): (T) -> Unit = { value = it }
        }
    }
}

@Composable
inline fun <reified T : Enum<T>> rememberEnumPreference(
    key: Preferences.Key<String>,
    defaultValue: T,
): MutableState<T> {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val initialValue = defaultValue
    val state =
        remember {
            context.dataStore.data
                .map { it[key].toEnum(defaultValue = defaultValue) }
                .distinctUntilChanged()
        }.collectAsState(initialValue)

    return remember {
        object : MutableState<T> {
            override var value: T
                get() = state.value
                set(value) {
                    coroutineScope.launch {
                        context.dataStore.edit {
                            it[key] = value.name
                        }
                    }
                }

            override fun component1() = value

            override fun component2(): (T) -> Unit = { value = it }
        }
    }
}
