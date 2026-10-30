package com.music.vibe.data.spotify

import android.content.Context
import android.content.SharedPreferences
import com.music.vibe.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class LocalPlaylist(
    val id: String,
    val title: String,
    val songs: List<Song>,
) {
    val browseId: String get() = "local:playlist:$id"
}

object LocalPlaylistStore {
    private const val PREF_NAME = "vibemusic_local_playlists"
    private const val KEY_PLAYLISTS = "playlists_json"

    private lateinit var prefs: SharedPreferences

    private val _playlists = MutableStateFlow<List<LocalPlaylist>>(emptyList())
    val playlists = _playlists.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        load()
    }

    private fun load() {
        if (!this::prefs.isInitialized) return
        val raw = prefs.getString(KEY_PLAYLISTS, null) ?: return
        runCatching {
            val list = mutableListOf<LocalPlaylist>()
            val jsonArray = JSONArray(raw)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.getString("id")
                val title = obj.getString("title")
                val songsArray = obj.getJSONArray("songs")
                val songs = mutableListOf<Song>()
                for (j in 0 until songsArray.length()) {
                    val sObj = songsArray.getJSONObject(j)
                    val song = Song(
                        videoId = sObj.optString("videoId", ""),
                        title = sObj.optString("title", ""),
                        artist = sObj.optString("artist", ""),
                        thumbnailUrl = sObj.optString("thumbnailUrl", null).takeIf { !it.isNullOrBlank() && it != "null" },
                        durationText = sObj.optString("durationText", null).takeIf { !it.isNullOrBlank() && it != "null" },
                        artistId = sObj.optString("artistId", null).takeIf { !it.isNullOrBlank() && it != "null" },
                        albumId = sObj.optString("albumId", null).takeIf { !it.isNullOrBlank() && it != "null" },
                        albumName = sObj.optString("albumName", null).takeIf { !it.isNullOrBlank() && it != "null" },
                        isVideo = sObj.optBoolean("isVideo", false),
                    )
                    songs.add(song)
                }
                list.add(LocalPlaylist(id, title, songs))
            }
            _playlists.value = list
        }
    }

    fun savePlaylist(title: String, songs: List<Song>): LocalPlaylist {
        val id = "sp_local_" + System.currentTimeMillis()
        val playlist = LocalPlaylist(id = id, title = title, songs = songs)
        val updated = listOf(playlist) + _playlists.value.filterNot { it.id == id }
        _playlists.value = updated
        persist(updated)
        return playlist
    }

    fun deletePlaylist(id: String) {
        val updated = _playlists.value.filterNot { it.id == id || it.browseId == id || it.id == id.removePrefix("local:playlist:") }
        _playlists.value = updated
        persist(updated)
    }

    fun renamePlaylist(id: String, newTitle: String) {
        val cleanId = id.removePrefix("local:playlist:").removePrefix("VL")
        val updated = _playlists.value.map {
            if (it.id == cleanId || it.id == id || it.browseId == id) {
                it.copy(title = newTitle)
            } else it
        }
        _playlists.value = updated
        persist(updated)
    }

    fun getPlaylist(id: String): LocalPlaylist? {
        val cleanId = id.removePrefix("local:playlist:").removePrefix("VL")
        return _playlists.value.firstOrNull { it.id == cleanId || it.id == id || it.browseId == id }
    }

    fun isLocalPlaylist(browseId: String?): Boolean {
        if (browseId.isNullOrBlank()) return false
        return browseId.startsWith("local:playlist:") || browseId.startsWith("sp_local_") || getPlaylist(browseId) != null
    }

    /**
     * Appends [newSongs] to the local playlist identified by [id], deduplicating by [Song.videoId].
     * Returns `(addedCount, skippedDuplicateCount)`.
     */
    fun addSongsToPlaylist(id: String, newSongs: List<Song>): Pair<Int, Int> {
        val cleanId = id.removePrefix("local:playlist:").removePrefix("VL")
        val target = _playlists.value.firstOrNull { it.id == cleanId || it.id == id || it.browseId == id }
            ?: return 0 to 0
        val existingIds = target.songs.mapTo(HashSet()) { it.videoId }
        val toAdd = mutableListOf<Song>()
        var duplicates = 0
        for (song in newSongs) {
            if (existingIds.add(song.videoId)) {
                toAdd.add(song)
            } else {
                duplicates++
            }
        }
        if (toAdd.isNotEmpty()) {
            val updated = _playlists.value.map {
                if (it.id == target.id) it.copy(songs = it.songs + toAdd) else it
            }
            _playlists.value = updated
            persist(updated)
        }
        return toAdd.size to duplicates
    }

    /**
     * Removes songs from the local playlist identified by [id].
     * When [indicesToRemove] is non-empty, removes those exact indices; otherwise removes all matching [videoIds].
     */
    fun removeSongsFromPlaylist(
        id: String,
        videoIds: Set<String>,
        indicesToRemove: Set<Int> = emptySet(),
    ): LocalPlaylist? {
        val cleanId = id.removePrefix("local:playlist:").removePrefix("VL")
        val target = _playlists.value.firstOrNull { it.id == cleanId || it.id == id || it.browseId == id }
            ?: return null
        val remaining = if (indicesToRemove.isNotEmpty()) {
            target.songs.filterIndexed { index, _ -> index !in indicesToRemove }
        } else {
            target.songs.filterNot { it.videoId in videoIds }
        }
        val updatedPlaylist = target.copy(songs = remaining)
        val updated = _playlists.value.map {
            if (it.id == target.id) updatedPlaylist else it
        }
        _playlists.value = updated
        persist(updated)
        return updatedPlaylist
    }

    private fun persist(list: List<LocalPlaylist>) {
        if (!this::prefs.isInitialized) return
        runCatching {
            val jsonArray = JSONArray()
            for (playlist in list) {
                val pObj = JSONObject()
                pObj.put("id", playlist.id)
                pObj.put("title", playlist.title)
                val songsArray = JSONArray()
                for (song in playlist.songs) {
                    val sObj = JSONObject()
                    sObj.put("videoId", song.videoId)
                    sObj.put("title", song.title)
                    sObj.put("artist", song.artist)
                    sObj.put("thumbnailUrl", song.thumbnailUrl)
                    sObj.put("durationText", song.durationText)
                    sObj.put("artistId", song.artistId)
                    sObj.put("albumId", song.albumId)
                    sObj.put("albumName", song.albumName)
                    sObj.put("isVideo", song.isVideo)
                    songsArray.put(sObj)
                }
                pObj.put("songs", songsArray)
                jsonArray.put(pObj)
            }
            prefs.edit().putString(KEY_PLAYLISTS, jsonArray.toString()).apply()
        }
    }
}
