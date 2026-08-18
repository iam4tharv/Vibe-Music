package com.music.echo.models

import com.music.echo.db.entities.*

data class EchoBackupPayload(
    val version: Int = 1,
    val exportDate: Long = System.currentTimeMillis(),
    val settings: Map<String, String>,
    
    val songs: List<SongEntity>,
    val artists: List<ArtistEntity>,
    val albums: List<AlbumEntity>,
    val songArtistMaps: List<SongArtistMap>,
    val songAlbumMaps: List<SongAlbumMap>,
    val albumArtistMaps: List<AlbumArtistMap>,
    
    val playlists: List<PlaylistEntity>,
    val playlistSongMaps: List<PlaylistSongMap>,
    
    val searchHistory: List<SearchHistory>,
    val events: List<Event>
)
