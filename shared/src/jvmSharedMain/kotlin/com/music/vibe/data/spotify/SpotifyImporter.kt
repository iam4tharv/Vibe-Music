package com.music.vibe.data.spotify

import com.music.vibe.data.Http
import com.music.vibe.data.YtMusicRepository
import com.music.vibe.data.model.SearchFilter
import com.music.vibe.data.model.SearchResult
import com.music.vibe.data.model.Song
import com.music.vibe.data.sources.TrackMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.concurrent.atomic.AtomicInteger

/** Single track metadata extracted from a Spotify playlist or album. */
@Serializable
data class SpotifyImportTrack(
    val title: String,
    val artist: String,
    val durationMs: Int = 0,
    val album: String? = null,
)

/** Kind of Spotify entity that can be imported by URL or URI. */
enum class SpotifyImportType(val pathSegment: String, val displayName: String) {
    PLAYLIST("playlist", "playlist"),
    ALBUM("album", "album"),
}

/** Parsed Spotify import target (playlist or album) and its 22-character base-62 ID. */
data class SpotifyImportTarget(
    val type: SpotifyImportType,
    val id: String,
)

/** The result of fetching and resolving a Spotify playlist or album's tracks. */
data class SpotifyImportResult(
    val title: String,
    val description: String,
    val thumbnailUrl: String?,
    val totalTracks: Int,
    val resolvedVideoIds: List<String>,
    val unmatchedTracks: List<SpotifyImportTrack>,
)

object SpotifyImporter {

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** A Spotify playlist or album id is 22 base-62 characters. */
    private val ENTITY_ID = Regex("""[A-Za-z0-9]{22}""")

    private fun isSpotifyHost(host: String) =
        host == "spotify.com" || host.endsWith(".spotify.com")

    /** Parses a raw user string or link into a [SpotifyImportTarget] (playlist or album) if valid. */
    fun extractTarget(input: String): SpotifyImportTarget? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        for (type in SpotifyImportType.entries) {
            val prefix = "spotify:${type.pathSegment}:"
            if (trimmed.startsWith(prefix)) {
                val id = trimmed.removePrefix(prefix).substringBefore("?").substringBefore("/")
                return id.takeIf { ENTITY_ID.matches(it) }?.let { SpotifyImportTarget(type, it) }
            }
        }

        val uri = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
        if (!isSpotifyHost(uri.host?.lowercase().orEmpty())) return null
        val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
        for (type in SpotifyImportType.entries) {
            val idx = segments.indexOf(type.pathSegment)
            if (idx != -1 && idx + 1 < segments.size) {
                val id = segments[idx + 1]
                if (ENTITY_ID.matches(id)) {
                    return SpotifyImportTarget(type, id)
                }
            }
        }
        return null
    }

    /** Parses a raw user string or link into a Spotify playlist ID if valid. */
    fun extractPlaylistId(input: String): String? =
        extractTarget(input)?.takeIf { it.type == SpotifyImportType.PLAYLIST }?.id

    /** Parses a raw user string or link into a Spotify album ID if valid. */
    fun extractAlbumId(input: String): String? =
        extractTarget(input)?.takeIf { it.type == SpotifyImportType.ALBUM }?.id

    /**
     * Fetches public Spotify playlist or album details from Spotify's embed endpoint.
     */
    suspend fun fetchTracks(target: SpotifyImportTarget): Pair<String, List<SpotifyImportTrack>> =
        withContext(Dispatchers.IO) {
            val url = "https://open.spotify.com/embed/${target.type.pathSegment}/${target.id}"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()

            val html = Http.client.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful || body.isBlank()) {
                    error("Failed to load Spotify ${target.type.displayName} (HTTP ${response.code})")
                }
                body
            }

            // Extract <script id="__NEXT_DATA__" type="application/json">...</script>
            val scriptRegex = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            val match = scriptRegex.find(html)
                ?: error("Could not parse Spotify ${target.type.displayName} metadata. Make sure it is public.")

            val parsed = parseEmbedNextData(match.groupValues[1], target.type)
            val tracks = parsed.second.toMutableList()

            // The embed page stops at its first 100 tracks. The web player's
            // own query, with the anonymous token the embed hands out, pages
            // through the rest; if it refuses, the 100 already read stand.
            if (tracks.size >= EMBED_TRACK_LIMIT && parsed.third != null) {
                val extra = when (target.type) {
                    SpotifyImportType.PLAYLIST ->
                        runCatching { fetchRemainingTracks(target.id, parsed.third!!, tracks.size) }.getOrNull()
                    SpotifyImportType.ALBUM ->
                        runCatching {
                            val fallbackArtist = tracks.firstOrNull()?.artist.orEmpty()
                            fetchRemainingAlbumTracks(target.id, parsed.third!!, tracks.size, fallbackArtist, parsed.first)
                        }.getOrNull()
                }
                if (extra != null) {
                    tracks.addAll(extra)
                }
            }

            Pair(parsed.first, tracks)
        }

    /**
     * Fetches public Spotify playlist details from Spotify's embed endpoint.
     */
    suspend fun fetchPlaylistTracks(playlistId: String): Pair<String, List<SpotifyImportTrack>> =
        fetchTracks(SpotifyImportTarget(SpotifyImportType.PLAYLIST, playlistId))

    /**
     * Fetches public Spotify album details from Spotify's embed endpoint.
     */
    suspend fun fetchAlbumTracks(albumId: String): Pair<String, List<SpotifyImportTrack>> =
        fetchTracks(SpotifyImportTarget(SpotifyImportType.ALBUM, albumId))

    /**
     * Parses the `__NEXT_DATA__` JSON payload from a Spotify playlist or album embed page.
     * Returns `(title, tracks, sessionAccessToken)`.
     */
    internal fun parseEmbedNextData(
        jsonString: String,
        type: SpotifyImportType,
    ): Triple<String, List<SpotifyImportTrack>, String?> {
        val root = json.parseToJsonElement(jsonString).jsonObject

        val props = root["props"]?.jsonObject
            ?.get("pageProps")?.jsonObject
        val stateData = props?.get("state")?.jsonObject
            ?.get("data")?.jsonObject
            ?.get("entity")?.jsonObject
            ?: props?.get("entity")?.jsonObject
            ?: error("Spotify ${type.displayName} entity missing or private")

        val defaultTitle = when (type) {
            SpotifyImportType.PLAYLIST -> "Imported Spotify Playlist"
            SpotifyImportType.ALBUM -> "Imported Spotify Album"
        }
        val entityTitle = stateData["name"]?.jsonPrimitive?.content
            ?: stateData["title"]?.jsonPrimitive?.content
            ?: defaultTitle

        val entityArtist = stateData["subtitle"]?.jsonPrimitive?.content
            ?: stateData["artists"]?.jsonArray?.joinToString(", ") {
                it.jsonObject["name"]?.jsonPrimitive?.content.orEmpty()
            }
            ?: ""

        val trackListJson = stateData["trackList"]?.jsonArray
            ?: stateData["tracks"]?.jsonArray
            ?: JsonArray(emptyList())

        val tracks = mutableListOf<SpotifyImportTrack>()
        for (element in trackListJson) {
            val obj = element.jsonObject
            val title = obj["title"]?.jsonPrimitive?.content
                ?: obj["name"]?.jsonPrimitive?.content
                ?: continue
            val trackSubtitle = obj["subtitle"]?.jsonPrimitive?.content
                ?: obj["artists"]?.jsonArray?.joinToString(", ") {
                    it.jsonObject["name"]?.jsonPrimitive?.content.orEmpty()
                }
                ?: ""
            val artist = trackSubtitle.ifBlank { entityArtist }
            val durationMs = obj["duration"]?.jsonPrimitive?.intOrNull
                ?: obj["duration_ms"]?.jsonPrimitive?.intOrNull
                ?: 0
            val album = if (type == SpotifyImportType.ALBUM) entityTitle else null
            if (title.isNotBlank()) {
                tracks.add(
                    SpotifyImportTrack(
                        title = title.replace('\u00A0', ' ').trim(),
                        artist = artist.replace('\u00A0', ' ').trim(),
                        durationMs = durationMs,
                        album = album,
                    ),
                )
            }
        }

        if (tracks.isEmpty()) {
            error("No tracks found in public Spotify ${type.displayName}.")
        }

        val token = props?.get("state")?.jsonObject
            ?.get("settings")?.jsonObject
            ?.get("session")?.jsonObject
            ?.get("accessToken")?.jsonPrimitive?.content

        return Triple(entityTitle, tracks, token)
    }

    /**
     * The YouTube Music song that is [track], or null if there is none: the
     * best candidate by title, artist, length and album, and failing that the
     * search's first hit.
     */
    suspend fun matchTrack(track: SpotifyTrack): Song? {
        val query = listOf(track.title, track.artist).filter { it.isNotBlank() }.joinToString(" ")
        val candidates = YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull()
            ?.filterIsInstance<SearchResult.Track>()
            ?.map { it.song }
            .orEmpty()
        return TrackMatcher.best(
            candidates,
            TrackMatcher.Target(
                title = track.title,
                artist = track.artist,
                durationSec = track.durationMs.takeIf { it > 0 }?.div(1000),
                album = track.album,
            ),
        ) ?: candidates.firstOrNull()
    }

    /** Tracks the embed page lists before it cuts off. */
    private const val EMBED_TRACK_LIMIT = 100
    private const val PAGE_SIZE = 100
    private const val PATHFINDER_URL = "https://api-partner.spotify.com/pathfinder/v1/query"
    private const val FETCH_PLAYLIST_HASH = "19ff1327c29e99c208c86d7a9d8f1929cfdf3d3202a0ff4253c821f1901aa94d"
    private const val QUERY_ALBUM_TRACKS_HASH = "f387592b8a1d259b833237a51ed9b23d7d8ac83da7cd76f0f0f8a0f65d0d270a"

    /** Tracks from [start] to the end of the playlist, a page at a time. */
    private fun fetchRemainingTracks(
        playlistId: String,
        token: String,
        start: Int,
    ): List<SpotifyImportTrack> {
        val out = mutableListOf<SpotifyImportTrack>()
        var offset = start
        while (true) {
            val variables = """{"uri":"spotify:playlist:$playlistId","offset":$offset,"limit":$PAGE_SIZE,"enableWatchFeedEntrypoint":false}"""
            val extensions = """{"persistedQuery":{"version":1,"sha256Hash":"$FETCH_PLAYLIST_HASH"}}"""
            val url = PATHFINDER_URL.toHttpUrl().newBuilder()
                .addQueryParameter("operationName", "fetchPlaylist")
                .addQueryParameter("variables", variables)
                .addQueryParameter("extensions", extensions)
                .build()
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Authorization", "Bearer $token")
                .header("App-platform", "WebPlayer")
                .header("Accept", "application/json")
                .build()
            val body = Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                response.body.string()
            }
            val content = json.parseToJsonElement(body).jsonObject["data"]?.jsonObject
                ?.get("playlistV2")?.jsonObject?.get("content")?.jsonObject
                ?: break
            val items = content["items"]?.jsonArray ?: break
            for (item in items) {
                val data = item.jsonObject["itemV2"]?.jsonObject?.get("data")?.jsonObject ?: continue
                val title = data["name"]?.jsonPrimitive?.content?.trim().orEmpty()
                if (title.isEmpty()) continue
                val artist = data["artists"]?.jsonObject?.get("items")?.jsonArray
                    ?.mapNotNull { it.jsonObject["profile"]?.jsonObject?.get("name")?.jsonPrimitive?.content }
                    ?.joinToString(", ")
                    .orEmpty()
                val durationMs = (data["trackDuration"]?.jsonObject ?: data["duration"]?.jsonObject)
                    ?.get("totalMilliseconds")?.jsonPrimitive?.intOrNull ?: 0
                val album = data["albumOfTrack"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                out.add(SpotifyImportTrack(title = title, artist = artist, durationMs = durationMs, album = album))
            }
            val total = content["totalCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: break
            offset += items.size
            if (items.isEmpty() || offset >= total) break
        }
        return out
    }

    /** Tracks from [start] to the end of a large album, a page at a time. */
    private fun fetchRemainingAlbumTracks(
        albumId: String,
        token: String,
        start: Int,
        fallbackArtist: String,
        albumTitle: String? = null,
    ): List<SpotifyImportTrack> {
        val out = mutableListOf<SpotifyImportTrack>()
        var offset = start
        while (true) {
            val variables = """{"uri":"spotify:album:$albumId","offset":$offset,"limit":$PAGE_SIZE}"""
            val extensions = """{"persistedQuery":{"version":1,"sha256Hash":"$QUERY_ALBUM_TRACKS_HASH"}}"""
            val url = PATHFINDER_URL.toHttpUrl().newBuilder()
                .addQueryParameter("operationName", "queryAlbumTracks")
                .addQueryParameter("variables", variables)
                .addQueryParameter("extensions", extensions)
                .build()
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Authorization", "Bearer $token")
                .header("App-platform", "WebPlayer")
                .header("Accept", "application/json")
                .build()
            val body = Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                response.body.string()
            }
            val tracksV2 = json.parseToJsonElement(body).jsonObject["data"]?.jsonObject
                ?.get("albumUnion")?.jsonObject?.get("tracksV2")?.jsonObject
                ?: break
            val items = tracksV2["items"]?.jsonArray ?: break
            for (item in items) {
                val track = item.jsonObject["track"]?.jsonObject ?: continue
                val title = track["name"]?.jsonPrimitive?.content?.trim().orEmpty()
                if (title.isEmpty()) continue
                val artist = track["artists"]?.jsonObject?.get("items")?.jsonArray
                    ?.mapNotNull { it.jsonObject["profile"]?.jsonObject?.get("name")?.jsonPrimitive?.content }
                    ?.joinToString(", ")
                    .orEmpty()
                    .ifBlank { fallbackArtist }
                val durationMs = (track["duration"]?.jsonObject ?: track["trackDuration"]?.jsonObject)
                    ?.get("totalMilliseconds")?.jsonPrimitive?.intOrNull ?: 0
                out.add(SpotifyImportTrack(title = title, artist = artist, durationMs = durationMs, album = albumTitle))
            }
            val total = tracksV2["totalCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: break
            offset += items.size
            if (items.isEmpty() || offset >= total) break
        }
        return out
    }

    /**
     * Resolves a list of Spotify tracks to full Song objects using Innertube search
     * and [TrackMatcher.best]. Supports resuming from [startIndex] and persisting
     * incremental progress via [onTrackResolved].
     */
    suspend fun resolveToSongs(
        tracks: List<SpotifyImportTrack>,
        startIndex: Int = 0,
        onProgress: (completed: Int, total: Int) -> Unit,
        onTrackResolved: (suspend (index: Int, song: Song?) -> Unit)? = null,
    ): Pair<List<Song>, List<SpotifyImportTrack>> = coroutineScope {
        val total = tracks.size
        if (startIndex >= total) return@coroutineScope Pair(emptyList(), emptyList())
        val completedCount = AtomicInteger(startIndex)
        val semaphore = Semaphore(4) // Bounded concurrency for search queries

        val deferredResults = tracks.drop(startIndex).mapIndexed { offset, track ->
            val index = startIndex + offset
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val query = "${track.title} ${track.artist}".trim()
                    val candidates = YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull()
                        ?.filterIsInstance<SearchResult.Track>()
                        ?.map { it.song }
                        .orEmpty()
                    val matchedSong = TrackMatcher.best(
                        candidates,
                        TrackMatcher.Target(
                            title = track.title,
                            artist = track.artist,
                            durationSec = track.durationMs.takeIf { it > 0 }?.div(1000),
                            album = track.album?.takeIf { it.isNotBlank() },
                        ),
                    ) ?: candidates.firstOrNull()

                    onTrackResolved?.invoke(index, matchedSong)
                    val done = completedCount.incrementAndGet()
                    onProgress(done, total)

                    if (matchedSong != null) {
                        Pair(matchedSong, null)
                    } else {
                        Pair(null, track)
                    }
                }
            }
        }

        val results = deferredResults.awaitAll()
        val songs = results.mapNotNull { it.first }
        val unmatched = results.mapNotNull { it.second }

        Pair(songs, unmatched)
    }
}
