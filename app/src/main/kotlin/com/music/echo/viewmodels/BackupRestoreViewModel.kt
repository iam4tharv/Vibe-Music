

package com.music.echo.viewmodels

import android.content.Context
import androidx.datastore.preferences.core.edit
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.ViewModel
import com.music.echo.MainActivity
import com.music.echo.R
import com.music.echo.db.InternalDatabase
import com.music.echo.db.MusicDatabase
import com.music.echo.db.entities.ArtistEntity
import com.music.echo.db.entities.Song
import com.music.echo.db.entities.SongEntity
import com.music.echo.extensions.div
import com.music.echo.extensions.tryOrNull
import com.music.echo.extensions.zipInputStream
import com.music.echo.extensions.zipOutputStream
import com.music.echo.playback.MusicService
import com.music.echo.playback.MusicService.Companion.PERSISTENT_QUEUE_FILE
import com.music.echo.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

import androidx.datastore.preferences.core.edit
import com.music.echo.utils.dataStore
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull

import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewModelScope
import timber.log.Timber
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import javax.inject.Inject
import kotlin.system.exitProcess

data class CsvImportState(
    val previewRows: List<List<String>> = emptyList(),
    val artistColumnIndex: Int = 0,
    val titleColumnIndex: Int = 1,
    val urlColumnIndex: Int = -1,
    val hasHeader: Boolean = true,
)

data class ConvertedSongLog(
    val title: String,
    val artists: String,
)

@HiltViewModel
class BackupRestoreViewModel @Inject constructor(
    val database: MusicDatabase,
) : ViewModel() {


    suspend fun backup(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        runCatching {
            
            var settingsMap = emptyMap<String, String>()
            kotlinx.coroutines.runBlocking {
                settingsMap = context.dataStore.data.first().asMap().mapKeys { it.key.name }.mapValues { it.value.toString() } as Map<String, String>
            }
            
            val payload = com.music.echo.models.EchoBackupPayload(
                settings = settingsMap,
                songs = database.getAllSongs(),
                artists = database.getAllArtists(),
                albums = database.getAllAlbums(),
                songArtistMaps = database.getAllSongArtistMaps(),
                songAlbumMaps = database.getAllSongAlbumMaps(),
                albumArtistMaps = database.getAllAlbumArtistMaps(),
                playlists = database.getAllPlaylists(),
                playlistSongMaps = database.getAllPlaylistSongMaps(),
                searchHistory = database.getAllSearchHistory(),
                events = database.getAllEvents()
            )
            
            val gson = com.google.gson.GsonBuilder()
                .registerTypeAdapter(java.time.LocalDateTime::class.java, com.google.gson.JsonSerializer<java.time.LocalDateTime> { src, _, _ ->
                    com.google.gson.JsonPrimitive(src.toString())
                })
                .registerTypeAdapter(java.time.LocalDateTime::class.java, com.google.gson.JsonDeserializer<java.time.LocalDateTime> { json, _, _ ->
                    java.time.LocalDateTime.parse(json.asString)
                })
                .create()
                
            val jsonString = gson.toJson(payload)
            context.applicationContext.contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(jsonString.toByteArray())
            }
        }.onSuccess {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, R.string.backup_create_success, Toast.LENGTH_SHORT).show()
            }
        }.onFailure {
            reportException(it)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, R.string.backup_create_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun restore(context: Context, uri: Uri) {
        val data = androidx.work.workDataOf("uri" to uri.toString())
        val request = androidx.work.OneTimeWorkRequestBuilder<com.music.echo.workers.UserDataImportWorker>()
            .setInputData(data)
            .build()
        androidx.work.WorkManager.getInstance(context).enqueue(request)
        Toast.makeText(context, "Backup restore started in background...", Toast.LENGTH_SHORT).show()
    }

    suspend fun previewCsvFile(context: Context, uri: Uri): CsvImportState = withContext(Dispatchers.IO) {
        var csvState = CsvImportState()
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader().useLines { linesSeq ->
                    val rowsToPreviewRaw = linesSeq.take(6).toList()
                    val previewRows = rowsToPreviewRaw.map { parseCsvLine(it) }
                    val hasHeader = rowsToPreviewRaw.isNotEmpty() && rowsToPreviewRaw[0].contains(",")
                    csvState = CsvImportState(
                        previewRows = previewRows,
                        hasHeader = hasHeader,
                    )
                }
            }
        }.onFailure {
            reportException(it)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Failed to preview CSV file", Toast.LENGTH_SHORT).show()
            }
        }
        csvState
    }

    suspend fun importPlaylistFromCsv(
        context: Context,
        uri: Uri,
        columnMapping: CsvImportState,
        onProgress: (Int) -> Unit = {},
        onLogUpdate: (List<ConvertedSongLog>) -> Unit = {},
    ): ArrayList<Song> = withContext(Dispatchers.IO) {
        val songs = arrayListOf<Song>()
        val recentLogs = mutableListOf<ConvertedSongLog>()

        runCatching {
            val totalLines = context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader().useLines { it.count() }
            } ?: 0

            context.contentResolver.openInputStream(uri)?.use { stream ->
                val startIndex = if (columnMapping.hasHeader) 1 else 0
                val linesToProcess = totalLines - startIndex

                stream.bufferedReader().useLines { linesSeq ->
                    linesSeq.drop(startIndex).forEachIndexed { index, line ->
                        val parts = parseCsvLine(line)

                        if (parts.isNotEmpty()) {
                            if (columnMapping.artistColumnIndex < parts.size && columnMapping.titleColumnIndex < parts.size) {
                                val title = parts[columnMapping.titleColumnIndex].trim()
                                val artistStr = parts[columnMapping.artistColumnIndex].trim()
                                val url = if (columnMapping.urlColumnIndex >= 0 && columnMapping.urlColumnIndex < parts.size) {
                                    parts[columnMapping.urlColumnIndex].trim()
                                } else {
                                    ""
                                }

                                if (title.isNotEmpty() && artistStr.isNotEmpty()) {
                                    val artists = artistStr.split(";", ",").map { it.trim() }
                                        .filter { it.isNotEmpty() }
                                        .map { ArtistEntity(id = "", name = it) }

                                    val mockSong = Song(
                                        song = SongEntity(
                                            id = "",
                                            title = title,
                                        ),
                                        artists = artists,
                                    )
                                    songs.add(mockSong)

                                    val logEntry = ConvertedSongLog(
                                        title = title,
                                        artists = artists.joinToString(", ") { it.name },
                                    )
                                    recentLogs.add(0, logEntry)
                                    if (recentLogs.size > 3) {
                                        recentLogs.removeAt(recentLogs.size - 1)
                                    }
                                }
                            }
                        }

                        if (linesToProcess > 0) {
                            if (index % 50 == 0 || index == linesToProcess - 1) {
                                val progress = ((index + 1) * 100) / linesToProcess
                                val currentLogs = recentLogs.toList()
                                withContext(Dispatchers.Main) {
                                    onLogUpdate(currentLogs)
                                    onProgress(progress.coerceIn(0, 99))
                                }
                            }
                        }
                    }
                }
            }
        }.onFailure {
            reportException(it)
            Timber.tag("CSV_IMPORT").e(it, "CSV import failed")
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    "Failed to import CSV file",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        withContext(Dispatchers.Main) {
            if (songs.isEmpty()) {
                Toast.makeText(
                    context,
                    "No songs found. Invalid file, or perhaps no song matches were found.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        
        songs
    }

    suspend fun importPlaylistFromCsv(context: Context, uri: Uri): ArrayList<Song> {
        return importPlaylistFromCsv(context, uri, CsvImportState())
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false

        for (char in line) {
            when {
                char == '"' -> inQuotes = !inQuotes
                char == ',' && !inQuotes -> {
                    result.add(current.toString())
                    current = StringBuilder()
                }
                else -> current.append(char)
            }
        }
        result.add(current.toString())
        return result.map { it.trim().trim('"') }
    }

    suspend fun loadM3UOnline(
        context: Context,
        uri: Uri,
    ): ArrayList<Song> = withContext(Dispatchers.IO) {
        val songs = ArrayList<Song>()

        runCatching {
            context.applicationContext.contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader().useLines { linesSeq ->
                    val lines = linesSeq.iterator()
                    if (lines.hasNext() && lines.next().startsWith("#EXTM3U")) {
                        while (lines.hasNext()) {
                            val rawLine = lines.next()
                            if (rawLine.startsWith("#EXTINF:")) {
                                val artists =
                                    rawLine.substringAfter("#EXTINF:").substringAfter(',').substringBefore(" - ").split(';')
                                val title = rawLine.substringAfter("#EXTINF:").substringAfter(',').substringAfter(" - ")

                                val mockSong = Song(
                                    song = SongEntity(
                                        id = "",
                                        title = title,
                                    ),
                                    artists = artists.map { ArtistEntity("", it) },
                                )
                                songs.add(mockSong)
                            }
                        }
                    }
                }
            }
        }

        withContext(Dispatchers.Main) {
            if (songs.isEmpty()) {
                Toast.makeText(
                    context,
                    "No songs found. Invalid file, or perhaps no song matches were found.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        
        songs
    }

    companion object {
        const val SETTINGS_FILENAME = "settings.preferences_pb"
    }
}
