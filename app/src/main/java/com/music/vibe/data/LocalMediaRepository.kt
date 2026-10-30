package com.music.vibe.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.music.vibe.data.DebugLog as Log
import androidx.core.content.ContextCompat
import com.music.vibe.data.model.Song
import com.music.vibe.data.settings.AppSettings
import com.music.vibe.download.DownloadStore
import com.music.vibe.download.Downloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.Locale

object LocalMediaRepository {

    private const val TAG = "VibeMusic"
    private const val MIN_LOCAL_MUSIC_DURATION_MS = 30_000L
    private const val MAX_METADATA_CACHE_ENTRIES = 2_000

    private data class MetadataCacheKey(
        val uriString: String,
        val lastModified: Long,
        val size: Long,
    )

    private data class CachedExtractedMetadata(
        val title: String,
        val artist: String,
        val albumName: String?,
        val durationText: String?,
        val trackNumber: Int?,
    )

    private val metadataCache: MutableMap<MetadataCacheKey, CachedExtractedMetadata> =
        Collections.synchronizedMap(
            object : LinkedHashMap<MetadataCacheKey, CachedExtractedMetadata>(256, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<MetadataCacheKey, CachedExtractedMetadata>?,
                ): Boolean = size > MAX_METADATA_CACHE_ENTRIES
            },
        )

    // `mp4` only ever reaches this list as an audio row — MediaStore files a
    // video .mp4 under Video, not Audio — so it is the audio-only MP4s Dolby
    // and Apple music downloads come as (AC-4, E-AC-3, ALAC). `dsf`/`dff` are
    // DSD, which DsdExtractor plays.
    private val localMusicExtensions = setOf(
        "mp3", "m4a", "mp4", "flac", "ogg", "opus", "aac", "webm", "dsf", "dff", "alac", "wav",
    )

    /** Parses track numbers from standard integer, "track/total", "disc-track" or multi-disc formats. */
    fun parseTrackNumber(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim()
        val trackPart = when {
            cleaned.contains('/') -> cleaned.substringBefore('/')
            cleaned.contains('-') -> cleaned.substringAfter('-')
            else -> cleaned
        }.trim()
        val num = trackPart.toIntOrNull() ?: return null
        if (num <= 0) return null
        return if (num >= 1000) num % 1000 else num
    }

    private val nonMusicPathSegments = listOf(
        "/alarms/",
        "/notifications/",
        "/ringtones/",
        "/podcasts/",
        "/audiobooks/",
        "/recordings/",
        "/voice recorder/",
        "/sound_recorder/",
        "/call_rec/",
        "/whatsapp voice notes/",
    )

    /** Check if storage/audio permission is granted to query device local music. */
    fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Retrieves all songs in the `Music/Vibe Music` directory, combining app downloads
     * with any local audio files present in that folder.
     *
     * The download record is the better source for a title and a credit — it
     * holds what the catalogue row said, not what a scanner guessed off a
     * filename — but it only started carrying the album at all recently, and
     * an album page's rows never name their own release. So whatever the media
     * scanner read off each file is collected alongside and used to fill the
     * gaps, which is what keeps the Albums tab from being empty for everything
     * downloaded before that field existed.
     */
    suspend fun getDownloadedSongs(context: Context): List<Song> = withContext(Dispatchers.IO) {
        val appDownloads = Downloads.getDownloadedSongs(context)
        val knownUris = appDownloads.mapNotNull { it.localUri }.toSet()
        val extraSongs = mutableListOf<Pair<Song, Pair<String, Long>?>>()

        /** uri to what the media scanner read off that file. */
        val scanned = mutableMapOf<String, ScannedTags>()
        val scannedNameSizeByUri = mutableMapOf<String, Pair<String, Long>>()

        val customTreeUri = DownloadStore.activeCustomTreeUri()
        val sameAsDefaultMusic = customTreeUri != null &&
            DownloadStore.isCustomFolderSameAsDefaultMusic(customTreeUri.toString())

        // 1. Scan active custom SAF folder via single-query SAF index (skipping .vibe-part-* files)
        if (customTreeUri != null) {
            runCatching {
                val safEntries = DownloadStore.getActiveSafEntries(context)
                for (entry in safEntries) {
                    val uriStr = entry.uri.toString()
                    val nameLower = entry.displayName.lowercase(Locale.ROOT)
                    if (entry.size > 0L) {
                        scannedNameSizeByUri[uriStr] = nameLower to entry.size
                    }
                    if (uriStr !in knownUris && isAudioFileName(entry.displayName)) {
                        val modSeconds = entry.lastModified.takeIf { it > 0L }?.div(1_000L)
                        val tags = ScannedTags(
                            albumName = null,
                            artworkUrl = null,
                            dateAddedSeconds = modSeconds,
                            dateModifiedSeconds = modSeconds,
                        )
                        val song = buildSongFromUri(
                            context = context,
                            uriStr = uriStr,
                            fileName = entry.displayName,
                            scanned = tags,
                            lastModified = entry.lastModified,
                            size = entry.size,
                        )
                        val dedupKey = if (entry.size > 0L) nameLower to entry.size else null
                        extraSongs.add(song to dedupKey)
                    }
                }
            }.onFailure { Log.w(TAG, "Failed scanning custom SAF download folder: ${it.message}") }
        }

        // 2. Scan default Music/Vibe Music directory (skip adding duplicate extraSongs if custom folder is Music/Vibe Music)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val projection = arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.RELATIVE_PATH,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.ALBUM_ID,
                    MediaStore.Audio.Media.DATE_ADDED,
                    MediaStore.Audio.Media.DATE_MODIFIED,
                    MediaStore.Audio.Media.TRACK,
                    MediaStore.Audio.Media.SIZE,
                )
                val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
                val selectionArgs = arrayOf("%${DownloadStore.FOLDER}%")

                context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    null,
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                    val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                    val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                    val dateModifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                    val trackCol = cursor.getColumnIndex(MediaStore.Audio.Media.TRACK)
                    val sizeCol = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
                    val albumArtBaseUri = Uri.parse("content://media/external/audio/albumart")

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: continue
                        if (name.startsWith(DownloadStore.PART_PREFIX) || name.startsWith(".")) continue
                        val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString()
                        val albumId = cursor.getLong(albumIdCol)
                        val trackRaw = if (trackCol >= 0) cursor.getString(trackCol) else null
                        val dateMod = cursor.getLong(dateModifiedCol)
                        val size = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else 0L
                        val nameLower = name.lowercase(Locale.ROOT)
                        if (size > 0L) {
                            scannedNameSizeByUri[contentUri] = nameLower to size
                        }
                        val tags = ScannedTags(
                            albumName = cursor.getString(albumCol).cleanTag(),
                            artworkUrl = if (albumId > 0) {
                                ContentUris.withAppendedId(albumArtBaseUri, albumId).toString()
                            } else {
                                null
                            },
                            dateAddedSeconds = cursor.getLong(dateAddedCol),
                            dateModifiedSeconds = dateMod,
                            trackNumber = parseTrackNumber(trackRaw),
                        )
                        scanned[contentUri] = tags
                        if (!sameAsDefaultMusic && contentUri !in knownUris && isAudioFileName(name)) {
                            val song = buildSongFromUri(
                                context = context,
                                uriStr = contentUri,
                                fileName = name,
                                scanned = tags,
                                lastModified = dateMod * 1_000L,
                                size = size,
                            )
                            val dedupKey = if (size > 0L) nameLower to size else null
                            extraSongs.add(song to dedupKey)
                        }
                    }
                }
            } else if (!sameAsDefaultMusic) {
                val folder = File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_MUSIC,
                    ),
                    DownloadStore.FOLDER,
                )
                if (folder.exists() && folder.isDirectory) {
                    folder.listFiles()?.forEach { file ->
                        if (file.isFile && !file.name.startsWith(".") && isAudioFileName(file.name)) {
                            val uriStr = Uri.fromFile(file).toString()
                            val size = file.length()
                            val nameLower = file.name.lowercase(Locale.ROOT)
                            if (size > 0L) {
                                scannedNameSizeByUri[uriStr] = nameLower to size
                            }
                            if (uriStr !in knownUris) {
                                val modifiedMs = file.lastModified()
                                val modified = modifiedMs.takeIf { it > 0 }?.div(1_000)
                                val song = buildSongFromUri(
                                    context = context,
                                    uriStr = uriStr,
                                    fileName = file.name,
                                    scanned = ScannedTags(null, null, modified, modified),
                                    lastModified = modifiedMs,
                                    size = size,
                                )
                                val dedupKey = if (size > 0L) nameLower to size else null
                                extraSongs.add(song to dedupKey)
                            }
                        }
                    }
                }
            }
        }.onFailure { Log.w(TAG, "Failed scanning Music/Vibe Music directory: ${it.message}") }

        val filled = appDownloads.map { song ->
            val uri = song.localUri ?: return@map song
            val tags = scanned[uri] ?: return@map song
            song.copy(
                albumName = song.albumName ?: tags.albumName,
                localDateAddedSeconds = tags.dateAddedSeconds,
                localDateModifiedSeconds = tags.dateModifiedSeconds,
                trackNumber = song.trackNumber ?: tags.trackNumber,
            )
        }

        // Deduplicate by (normalizedDisplayName, size) so a file indexed by both MediaStore
        // and SAF (or tracked in Downloads.saved under one URI and scanned under the other)
        // never appears twice. Prefer the canonical entry from Downloads.saved (`filled`).
        val seenNameSizes = HashSet<Pair<String, Long>>()
        for (song in filled) {
            val uri = song.localUri ?: continue
            scannedNameSizeByUri[uri]?.let { seenNameSizes.add(it) }
        }

        val deduplicatedExtras = mutableListOf<Song>()
        for ((song, dedupKey) in extraSongs) {
            if (dedupKey != null && !seenNameSizes.add(dedupKey)) continue
            deduplicatedExtras.add(song)
        }

        (filled + deduplicatedExtras).distinctBy { it.localUri ?: it.videoId }
    }

    /**
     * The parts of a scanner row worth reading back — everything else about a
     * download is better known from the record that made it.
     */
    private class ScannedTags(
        val albumName: String?,
        val artworkUrl: String?,
        val dateAddedSeconds: Long?,
        val dateModifiedSeconds: Long?,
        val trackNumber: Int? = null,
    )

    /** What MediaStore writes into a column it has nothing for. */
    private fun String?.cleanTag(): String? =
        takeUnless { it.isNullOrBlank() || it == "<unknown>" }

    /**
     * Queries MediaStore for all audio files available on the device.
     */
    suspend fun getLocalMusic(context: Context): List<Song> = withContext(Dispatchers.IO) {
        if (!hasStoragePermission(context)) return@withContext emptyList()

        val songs = mutableListOf<Song>()
        // This scan runs over every audio file on the device, which includes
        // whatever this app has downloaded into Music/Vibe Music alongside
        // everything else — but by content URI, the only thing MediaStore
        // offers here, that download is indistinguishable from a file the
        // user copied on by hand. Reversing [Downloads.saved] hands a
        // downloaded track its real YouTube id back, which is what lets
        // PlaybackTracker recognise it as a video worth registering a play
        // for — a content URI fails its id check on purpose, since most rows
        // here really are just local files with nothing to sync.
        val videoIdByUri = Downloads.saved.value.entries.associate { (id, uri) -> uri to id }
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.TRACK,
        )

        val filterNonMusic = AppSettings.filterNonMusicAudio.value
        val baseMusicPredicate = "(${MediaStore.Audio.Media.IS_MUSIC} != 0 OR " +
            "${MediaStore.Audio.Media.MIME_TYPE} LIKE 'audio/%' OR " +
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '%.m4a' OR " +
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '%.alac')"
        val selection = if (filterNonMusic) {
            buildString {
                append(baseMusicPredicate)
                // A duration of 0 or NULL is the scanner failing to read one,
                // not a short clip — fragmented MP4 (how ALAC downloads often
                // come) reads as 0, high-rate DSD as NULL — so it is let
                // through to the extension and path checks rather than
                // dropped as too short.
                append(
                    " AND (${MediaStore.Audio.Media.DURATION} >= ?" +
                        " OR ${MediaStore.Audio.Media.DURATION} <= 0" +
                        " OR ${MediaStore.Audio.Media.DURATION} IS NULL)",
                )
                append(" AND ${MediaStore.Audio.Media.IS_ALARM} = 0")
                append(" AND ${MediaStore.Audio.Media.IS_NOTIFICATION} = 0")
                append(" AND ${MediaStore.Audio.Media.IS_RINGTONE} = 0")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    append(" AND ${MediaStore.Audio.Media.IS_PODCAST} = 0")
                }
            }
        } else {
            baseMusicPredicate
        }
        val selectionArgs = if (filterNonMusic) {
            arrayOf(MIN_LOCAL_MUSIC_DURATION_MS.toString())
        } else {
            null
        }
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val displayNameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val dateModifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                val trackCol = cursor.getColumnIndex(MediaStore.Audio.Media.TRACK)

                val albumArtBaseUri = Uri.parse("content://media/external/audio/albumart")

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val displayName = cursor.getString(displayNameCol).orEmpty()
                    val rawTitle = cursor.getString(titleCol)
                    val rawArtist = cursor.getString(artistCol)
                    val rawAlbum = cursor.getString(albumCol)
                    val albumId = cursor.getLong(albumIdCol)
                    val durationMs = cursor.getLong(durationCol)
                    val path = cursor.getString(dataCol)
                    val trackRaw = if (trackCol >= 0) cursor.getString(trackCol) else null

                    if (filterNonMusic && !isEligibleLocalMusic(durationMs, displayName, path)) continue
                    if (!isInSelectedFolder(path, AppSettings.localMusicFolderUri.value)) continue

                    val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString()
                    val title = rawTitle.cleanTag()?.removeAudioExtension()
                        ?: displayName.substringBeforeLast('.').takeIf { it.isNotBlank() }
                        ?: "Track $id"
                    val artist = rawArtist.takeUnless { it.isNullOrBlank() || it == "<unknown>" } ?: "Unknown Artist"
                    val albumName = rawAlbum.takeUnless { it.isNullOrBlank() || it == "<unknown>" }
                    val artworkUrl = if (albumId > 0) ContentUris.withAppendedId(albumArtBaseUri, albumId).toString() else null
                    val durationText = durationMs.takeIf { it > 0 }?.let(::formatDuration)

                    songs.add(
                        Song(
                            videoId = videoIdByUri[contentUri] ?: contentUri,
                            title = title,
                            artist = artist,
                            thumbnailUrl = artworkUrl,
                            durationText = durationText,
                            albumName = albumName,
                            localUri = contentUri,
                            localPath = path,
                            localDateAddedSeconds = cursor.getLong(dateAddedCol),
                            localDateModifiedSeconds = cursor.getLong(dateModifiedCol),
                            trackNumber = parseTrackNumber(trackRaw),
                        )
                    )
                }
            }
        }.onFailure { Log.w(TAG, "Failed scanning device local music: ${it.message}") }

        songs
    }

    /** Human-readable path for the folder setting without exposing provider internals. */
    fun selectedFolderLabel(treeUri: String): String? = selectedFolder(treeUri)?.label

    /**
     * Formats a custom SAF download tree URI into a clean user-facing path such as
     * `Internal storage/Music/MyVibe` or `SD card/Music/MyVibe`.
     */
    fun downloadFolderLabel(treeUri: String): String? = runCatching {
        if (treeUri.isBlank()) return@runCatching null
        val documentId = DocumentsContract.getTreeDocumentId(Uri.parse(treeUri))
        if (documentId.startsWith("raw:")) {
            val path = documentId.removePrefix("raw:").trimEnd('/')
            return@runCatching path.substringAfterLast('/').ifBlank { path }
        }
        val volume = documentId.substringBefore(':')
        val relative = documentId.substringAfter(':', "").trim('/')
        val volumeLabel = if (volume.equals("primary", ignoreCase = true)) {
            "Internal storage"
        } else {
            "SD card"
        }
        if (relative.isBlank()) volumeLabel else "$volumeLabel/$relative"
    }.getOrNull()

    internal fun isInSelectedFolder(path: String?, treeUri: String): Boolean {
        if (treeUri.isBlank()) return true
        val folder = selectedFolder(treeUri) ?: return false
        val candidate = path?.normalizedPath() ?: return false
        return candidate == folder.absolutePath || candidate.startsWith("${folder.absolutePath}/")
    }

    private data class SelectedFolder(val absolutePath: String, val label: String)

    private fun selectedFolder(treeUri: String): SelectedFolder? = runCatching {
        val documentId = DocumentsContract.getTreeDocumentId(Uri.parse(treeUri))
        if (documentId.startsWith("raw:")) {
            val path = documentId.removePrefix("raw:").normalizedPath()
            return@runCatching SelectedFolder(path, path.substringAfterLast('/'))
        }
        val volume = documentId.substringBefore(':')
        val relative = documentId.substringAfter(':', "").trim('/')
        val volumeRoot = if (volume.equals("primary", ignoreCase = true)) {
            "/storage/emulated/0"
        } else {
            "/storage/$volume"
        }
        SelectedFolder(
            absolutePath = listOf(volumeRoot, relative).filter { it.isNotBlank() }.joinToString("/").normalizedPath(),
            label = relative.ifBlank { volume },
        )
    }.getOrNull()

    private fun String.normalizedPath(): String =
        replace('\\', '/').trimEnd('/').lowercase(Locale.ROOT)

    /**
     * MediaStore's `IS_MUSIC` flag is advisory and often includes notification
     * sounds, voice notes and recorder output. Keep this second gate independent
     * of scanner metadata so the same bad rows stay out across Android vendors.
     */
    internal fun isEligibleLocalMusic(durationMs: Long, displayName: String, path: String?): Boolean {
        if (durationMs in 1 until MIN_LOCAL_MUSIC_DURATION_MS) return false

        val fileName = path?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: displayName
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension !in localMusicExtensions) return false

        val normalizedPath = path
            ?.replace('\\', '/')
            ?.lowercase(Locale.ROOT)
            ?: return true
        return nonMusicPathSegments.none(normalizedPath::contains)
    }

    private fun String.removeAudioExtension(): String {
        val extension = substringAfterLast('.', "").lowercase(Locale.ROOT)
        return if (extension in localMusicExtensions || extension == "wav") {
            substringBeforeLast('.').ifBlank { this }
        } else {
            this
        }
    }

    private fun isAudioFileName(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".mp4") ||
            lower.endsWith(".flac") || lower.endsWith(".wav") ||
            lower.endsWith(".ogg") || lower.endsWith(".opus") ||
            lower.endsWith(".aac") || lower.endsWith(".webm") ||
            lower.endsWith(".3gp") || lower.endsWith(".alac") ||
            lower.endsWith(".dsf") || lower.endsWith(".dff")
    }

    /**
     * A song built from a file in the downloads folder the app has no record of
     * — one copied in by hand, or left behind by an install whose record is
     * gone. The file's own tags are the only thing there is to go on; [scanned]
     * fills in what the retriever couldn't read, since the media scanner and
     * `MediaMetadataRetriever` do not agree on every container.
     */
    private fun buildSongFromUri(
        context: Context,
        uriStr: String,
        fileName: String,
        scanned: ScannedTags? = null,
        lastModified: Long = 0L,
        size: Long = 0L,
    ): Song {
        val cacheKey = if (lastModified > 0L || size > 0L) {
            MetadataCacheKey(uriStr, lastModified, size)
        } else {
            null
        }
        val cached = cacheKey?.let { metadataCache[it] }
        if (cached != null) {
            return Song(
                videoId = uriStr,
                title = cached.title,
                artist = cached.artist,
                thumbnailUrl = scanned?.artworkUrl,
                durationText = cached.durationText,
                albumName = cached.albumName ?: scanned?.albumName,
                localUri = uriStr,
                localDateAddedSeconds = scanned?.dateAddedSeconds,
                localDateModifiedSeconds = scanned?.dateModifiedSeconds,
                trackNumber = cached.trackNumber ?: scanned?.trackNumber,
            )
        }

        var title = fileName.substringBeforeLast(".")
        var artist = "Unknown Artist"
        var albumName: String? = null
        var durationText: String? = null
        var trackNumber: Int? = null

        val retriever = MediaMetadataRetriever()
        try {
            runCatching {
                retriever.setDataSource(context, Uri.parse(uriStr))
                val metaTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                val metaArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                val metaAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                val metaDur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                val metaTrack = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)

                if (!metaTitle.isNullOrBlank()) title = metaTitle
                if (!metaArtist.isNullOrBlank()) artist = metaArtist
                albumName = metaAlbum.cleanTag()
                if (metaDur != null && metaDur > 0) durationText = formatDuration(metaDur)
                trackNumber = parseTrackNumber(metaTrack)
            }
        } finally {
            runCatching { retriever.release() }
        }

        if (cacheKey != null) {
            metadataCache[cacheKey] = CachedExtractedMetadata(
                title = title,
                artist = artist,
                albumName = albumName,
                durationText = durationText,
                trackNumber = trackNumber,
            )
        }

        return Song(
            videoId = uriStr,
            title = title,
            artist = artist,
            thumbnailUrl = scanned?.artworkUrl,
            durationText = durationText,
            albumName = albumName ?: scanned?.albumName,
            localUri = uriStr,
            localDateAddedSeconds = scanned?.dateAddedSeconds,
            localDateModifiedSeconds = scanned?.dateModifiedSeconds,
            trackNumber = trackNumber ?: scanned?.trackNumber,
        )
    }

    private fun formatDuration(ms: Long): String {
        val totalSecs = ms / 1000
        val minutes = totalSecs / 60
        val secs = totalSecs % 60
        return String.format(Locale.ROOT, "%d:%02d", minutes, secs)
    }
}
