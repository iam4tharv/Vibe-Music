package com.music.vibe.data.spotify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpotifyLibraryTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun playlistsIncludeFolderLeaves() {
        val root = json.parseToJsonElement(
            """
            {"data":{"me":{"libraryV3":{"totalCount":1,"items":[
              {"item":{"__typename":"PlaylistResponseWrapper","_uri":"spotify:playlist:abc","data":{
                "__typename":"Playlist","name":"Road","ownerV2":{"data":{"name":"Amit"}},
                "images":{"items":[{"sources":[{"url":"https://i.scdn.co/x"}]}]}
              }}}
            ]}}}}
            """.trimIndent(),
        ).jsonObject
        val (items, total) = parsePlaylistPage(root)
        assertEquals(1, total)
        assertEquals("abc", items.single().id)
        assertEquals("Road", items.single().name)
        assertEquals("Amit", items.single().owner)
        assertEquals("https://i.scdn.co/x", items.single().imageUrl)
    }

    @Test
    fun tracksReadTitleArtistAndDuration() {
        val root = json.parseToJsonElement(
            """
            {"data":{"playlistV2":{"content":{"totalCount":1,"items":[
              {"itemV2":{"data":{
                "name":"Song","uri":"spotify:track:t1",
                "artists":{"items":[{"uri":"spotify:artist:a","profile":{"name":"Artist"}}]},
                "albumOfTrack":{"name":"Album","coverArt":{"sources":[{"url":"https://img"}]}},
                "duration":{"totalMilliseconds":180000}
              }}}
            ]}}}}
            """.trimIndent(),
        ).jsonObject
        val track = parseTrackPage(root).first.single()
        assertEquals("t1", track.id)
        assertEquals("Song", track.title)
        assertEquals("Artist", track.artist)
        assertEquals("Album", track.album)
        assertEquals(180000, track.durationMs)
        assertEquals("https://img", track.imageUrl)
    }

    @Test
    fun playlistIdsComeFromLinksAndUris() {
        val id = "37i9dQZF1DXcBWIGoYBM5M"
        assertEquals(id, SpotifyImporter.extractPlaylistId("https://open.spotify.com/playlist/$id"))
        assertEquals(id, SpotifyImporter.extractPlaylistId("  https://open.spotify.com/playlist/$id?si=abc123  "))
        assertEquals(id, SpotifyImporter.extractPlaylistId("https://open.spotify.com/intl-id/playlist/$id"))
        assertEquals(id, SpotifyImporter.extractPlaylistId("spotify:playlist:$id"))
        assertNull(SpotifyImporter.extractPlaylistId("https://open.spotify.com/album/$id"))
        assertNull(SpotifyImporter.extractPlaylistId("https://notspotify.com/playlist/$id"))
        assertNull(SpotifyImporter.extractPlaylistId("ftp://open.spotify.com/playlist/$id"))
        assertNull(SpotifyImporter.extractPlaylistId("https://open.spotify.com/playlist/short"))
        assertNull(SpotifyImporter.extractPlaylistId(""))
    }

    @Test
    fun albumIdsAndTargetsComeFromLinksAndUris() {
        val albumId = "4aawyAB9vmqN3uQ7FjRGTy"
        val playlistId = "37i9dQZF1DXcBWIGoYBM5M"

        assertEquals(albumId, SpotifyImporter.extractAlbumId("https://open.spotify.com/album/$albumId"))
        assertEquals(albumId, SpotifyImporter.extractAlbumId("  https://open.spotify.com/album/$albumId?si=xyz789  "))
        assertEquals(albumId, SpotifyImporter.extractAlbumId("https://open.spotify.com/intl-en/album/$albumId"))
        assertEquals(albumId, SpotifyImporter.extractAlbumId("spotify:album:$albumId"))
        assertNull(SpotifyImporter.extractAlbumId("https://open.spotify.com/playlist/$playlistId"))

        assertEquals(
            SpotifyImportTarget(SpotifyImportType.ALBUM, albumId),
            SpotifyImporter.extractTarget("https://open.spotify.com/album/$albumId?si=123"),
        )
        assertEquals(
            SpotifyImportTarget(SpotifyImportType.ALBUM, albumId),
            SpotifyImporter.extractTarget("https://open.spotify.com/intl-de/album/$albumId"),
        )
        assertEquals(
            SpotifyImportTarget(SpotifyImportType.ALBUM, albumId),
            SpotifyImporter.extractTarget("spotify:album:$albumId"),
        )
        assertEquals(
            SpotifyImportTarget(SpotifyImportType.PLAYLIST, playlistId),
            SpotifyImporter.extractTarget("https://open.spotify.com/playlist/$playlistId"),
        )
        assertNull(SpotifyImporter.extractTarget("https://open.spotify.com/track/$albumId"))
    }

    @Test
    fun parseAlbumEmbedNextDataUsesEntityArtistFallback() {
        val embedJson = """
            {
              "props": {
                "pageProps": {
                  "state": {
                    "data": {
                      "entity": {
                        "type": "album",
                        "name": "Random Access Memories",
                        "subtitle": "Daft Punk",
                        "trackList": [
                          {"title": "Give Life Back to Music", "subtitle": "Daft Punk"},
                          {"title": "Instant Crush", "subtitle": ""}
                        ]
                      }
                    },
                    "settings": {
                      "session": {
                        "accessToken": "tok_123"
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent()
        val (title, tracks, token) = SpotifyImporter.parseEmbedNextData(embedJson, SpotifyImportType.ALBUM)
        assertEquals("Random Access Memories", title)
        assertEquals("tok_123", token)
        assertEquals(
            listOf(
                SpotifyImportTrack("Give Life Back to Music", "Daft Punk", album = "Random Access Memories"),
                SpotifyImportTrack("Instant Crush", "Daft Punk", album = "Random Access Memories"),
            ),
            tracks,
        )
    }
}
