package com.music.echo.workers

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.datastore.preferences.core.edit
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.music.echo.R
import com.music.echo.db.MusicDatabase
import com.music.echo.utils.dataStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.system.exitProcess
import com.music.echo.utils.reportException

class UserDataImportWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface UserDataImportWorkerEntryPoint {
        fun musicDatabase(): MusicDatabase
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val uriString = inputData.getString("uri") ?: return@withContext Result.failure()
        val uri = Uri.parse(uriString)

        val entryPoint = EntryPointAccessors.fromApplication(
            appContext,
            UserDataImportWorkerEntryPoint::class.java
        )
        val database = entryPoint.musicDatabase()

        runCatching {
            val payload = appContext.contentResolver.openInputStream(uri)?.use { inputStream ->
                val gson = com.google.gson.GsonBuilder()
                    .registerTypeAdapter(java.time.LocalDateTime::class.java, com.google.gson.JsonSerializer<java.time.LocalDateTime> { src, _, _ ->
                        com.google.gson.JsonPrimitive(src.toString())
                    })
                    .registerTypeAdapter(java.time.LocalDateTime::class.java, com.google.gson.JsonDeserializer<java.time.LocalDateTime> { json, _, _ ->
                        java.time.LocalDateTime.parse(json.asString)
                    })
                    .create()
                gson.fromJson(inputStream.reader(), com.music.echo.models.EchoBackupPayload::class.java)
            } ?: return@runCatching Result.failure()

            // Restore Settings
            appContext.dataStore.edit { mutablePrefs ->
                payload.settings.forEach { (key, value) ->
                    val prefKey = androidx.datastore.preferences.core.stringPreferencesKey(key)
                    val boolKey = androidx.datastore.preferences.core.booleanPreferencesKey(key)
                    val intKey = androidx.datastore.preferences.core.intPreferencesKey(key)

                    if (value == "true" || value == "false") {
                        mutablePrefs[boolKey] = value.toBoolean()
                    } else if (value.toIntOrNull() != null) {
                        mutablePrefs[intKey] = value.toInt()
                    } else {
                        mutablePrefs[prefKey] = value
                    }
                }
            }

            // Restore Database
            database.withTransaction {
                database.deleteAllEvents()
                database.deleteAllSearchHistory()
                database.deleteAllPlaylistSongMaps()
                database.deleteAllSongAlbumMaps()
                database.deleteAllSongArtistMaps()
                database.deleteAllAlbumArtistMaps()
                database.deleteAllPlaylists()
                database.deleteAllAlbums()
                database.deleteAllArtists()
                database.deleteAllSongs()

                database.insertSongs(payload.songs)
                database.insertArtists(payload.artists)
                database.insertAlbums(payload.albums)
                database.insertPlaylists(payload.playlists)
                database.insertSongArtistMaps(payload.songArtistMaps)
                database.insertSongAlbumMaps(payload.songAlbumMaps)
                database.insertAlbumArtistMaps(payload.albumArtistMaps)
                database.insertPlaylistSongMaps(payload.playlistSongMaps)
                database.insertSearchHistory(payload.searchHistory)
                database.insertEvents(payload.events)
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(appContext, R.string.backup_create_success, Toast.LENGTH_SHORT).show()
            }
            exitProcess(0)
            Result.success()
        }.onFailure {
            reportException(it)
            withContext(Dispatchers.Main) {
                Toast.makeText(appContext, R.string.backup_create_failed, Toast.LENGTH_SHORT).show()
            }
        }.getOrDefault(Result.failure())
    }
}
