package com.music.vibe.playback

import android.os.Bundle
import androidx.core.os.bundleOf
import com.music.vibe.data.model.ArtistRef
import com.music.vibe.data.model.PlaybackSourceType
import com.music.vibe.data.model.QueueTier
import com.music.vibe.data.model.Song
import com.music.vibe.data.model.SongJsonCodec
import com.music.vibe.data.runCatchingLogged

/**
 * Canonical IPC and `MediaItem.mediaMetadata.extras` codec for [Song].
 *
 * Packs a canonical `@Serializable` JSON payload (`vibemusic.songJson`) into the
 * [Bundle] so every [Song] property (including [Song.localDateAddedSeconds],
 * [Song.localDateModifiedSeconds], [Song.downloadFormat], [Song.sourceQuality],
 * [Song.artists], [Song.isVideoOrigin], [Song.trackNumber], etc.) survives
 * MediaSession / MediaController / Bundle round-trips without loss, while also
 * writing legacy individual Bundle keys so external callers and Media3 metadata
 * readers remain 100% compatible.
 */
object SongBundleCodec {

    private const val TAG = "SongBundleCodec"

    const val EXTRA_SONG_PAYLOAD_JSON = "vibemusic.songJson"

    const val KEY_VIDEO_ID = "videoId"
    const val KEY_TITLE = "title"
    const val KEY_ARTIST = "artist"
    const val KEY_THUMBNAIL = "thumbnailUrl"
    const val KEY_DURATION = "durationText"
    const val KEY_ARTIST_ID = "artistId"
    const val KEY_ARTIST_NAMES = "artistNames"
    const val KEY_ARTIST_IDS = "artistIds"
    const val KEY_ALBUM_ID = "albumId"
    const val KEY_ALBUM_NAME = "albumName"
    const val KEY_EXPLICIT = "isExplicit"
    const val KEY_IS_VIDEO = "isVideo"
    const val KEY_VIDEO_ORIGIN = "isVideoOrigin"
    const val KEY_SET_VIDEO_ID = "setVideoId"
    const val KEY_FROM_AUTOPLAY = "fromAutoplay"
    const val KEY_QUEUE_TIER = "queueTier"
    const val KEY_QUEUE_ENTRY_ID = "queueEntryId"
    const val KEY_RADIO_NAME = "radioName"
    const val KEY_PLAYBACK_SOURCE = "playbackSource"
    const val KEY_PLAYBACK_SOURCE_TYPE = "playbackSourceType"
    const val KEY_PLAYBACK_SOURCE_ID = "playbackSourceId"
    const val KEY_LOCAL_URI = "localUri"
    const val KEY_DOWNLOAD_FORMAT = "downloadFormat"
    const val KEY_LOCAL_PATH = "localPath"
    const val KEY_SOURCE_QUALITY = "sourceQuality"
    const val KEY_TRACK_NUMBER = "trackNumber"
    const val KEY_LOCAL_DATE_ADDED = "localDateAddedSeconds"
    const val KEY_LOCAL_DATE_MODIFIED = "localDateModifiedSeconds"

    fun encodeToBundle(song: Song): Bundle = bundleOf(
        EXTRA_SONG_PAYLOAD_JSON to runCatchingLogged(TAG, "encodeSong") {
            SongJsonCodec.encode(song)
        }.getOrNull(),
        KEY_VIDEO_ID to song.videoId,
        KEY_TITLE to song.title,
        KEY_ARTIST to song.artist,
        KEY_THUMBNAIL to song.thumbnailUrl,
        KEY_DURATION to song.durationText,
        KEY_ARTIST_ID to song.artistId,
        KEY_ARTIST_NAMES to ArrayList(song.artists.map { it.name }),
        KEY_ARTIST_IDS to ArrayList(song.artists.map { it.browseId.orEmpty() }),
        KEY_ALBUM_ID to song.albumId,
        KEY_ALBUM_NAME to song.albumName,
        KEY_IS_VIDEO to song.isVideo,
        KEY_VIDEO_ORIGIN to song.isVideoOrigin,
        KEY_SET_VIDEO_ID to song.setVideoId,
        KEY_FROM_AUTOPLAY to song.fromAutoplay,
        KEY_QUEUE_TIER to song.queueTier.name,
        KEY_QUEUE_ENTRY_ID to song.queueEntryId,
        KEY_RADIO_NAME to song.radioName,
        KEY_PLAYBACK_SOURCE to song.playbackSource,
        KEY_PLAYBACK_SOURCE_TYPE to song.playbackSourceType?.name,
        KEY_PLAYBACK_SOURCE_ID to song.playbackSourceId,
        KEY_LOCAL_URI to song.localUri,
        KEY_DOWNLOAD_FORMAT to song.downloadFormat,
        KEY_LOCAL_PATH to song.localPath,
        KEY_SOURCE_QUALITY to song.sourceQuality,
    ).apply {
        song.isExplicit?.let { putBoolean(KEY_EXPLICIT, it) }
        song.trackNumber?.let { putInt(KEY_TRACK_NUMBER, it) }
        song.localDateAddedSeconds?.let { putLong(KEY_LOCAL_DATE_ADDED, it) }
        song.localDateModifiedSeconds?.let { putLong(KEY_LOCAL_DATE_MODIFIED, it) }
    }

    fun decodeFromBundle(bundle: Bundle): Song {
        val jsonPayload = bundle.getString(EXTRA_SONG_PAYLOAD_JSON)
        if (!jsonPayload.isNullOrBlank()) {
            val decoded = SongJsonCodec.decode(jsonPayload)
            if (decoded != null) return decoded
        }
        val tier = when (bundle.getString(KEY_QUEUE_TIER)) {
            "USER_QUEUE" -> QueueTier.USER_QUEUE
            "CONTEXT" -> QueueTier.CONTEXT
            "AUTOPLAY" -> QueueTier.AUTOPLAY
            else -> if (bundle.getBoolean(KEY_FROM_AUTOPLAY)) QueueTier.AUTOPLAY else QueueTier.CONTEXT
        }
        return Song(
            videoId = bundle.getString(KEY_VIDEO_ID).orEmpty(),
            title = bundle.getString(KEY_TITLE).orEmpty(),
            artist = bundle.getString(KEY_ARTIST).orEmpty(),
            thumbnailUrl = bundle.getString(KEY_THUMBNAIL),
            durationText = bundle.getString(KEY_DURATION),
            artistId = bundle.getString(KEY_ARTIST_ID),
            artists = decodeArtists(bundle),
            albumId = bundle.getString(KEY_ALBUM_ID),
            albumName = bundle.getString(KEY_ALBUM_NAME),
            isExplicit = if (bundle.containsKey(KEY_EXPLICIT)) bundle.getBoolean(KEY_EXPLICIT) else null,
            isVideo = bundle.getBoolean(KEY_IS_VIDEO),
            isVideoOrigin = bundle.getBoolean(KEY_VIDEO_ORIGIN) || bundle.getBoolean(KEY_IS_VIDEO),
            setVideoId = bundle.getString(KEY_SET_VIDEO_ID),
            queueTier = tier,
            queueEntryId = bundle.getString(KEY_QUEUE_ENTRY_ID),
            radioName = bundle.getString(KEY_RADIO_NAME),
            playbackSource = bundle.getString(KEY_PLAYBACK_SOURCE),
            playbackSourceType = bundle.getString(KEY_PLAYBACK_SOURCE_TYPE)
                ?.let { runCatchingLogged(TAG, "parsePlaybackSourceType") { PlaybackSourceType.valueOf(it) }.getOrNull() },
            playbackSourceId = bundle.getString(KEY_PLAYBACK_SOURCE_ID),
            localUri = bundle.getString(KEY_LOCAL_URI),
            downloadFormat = bundle.getString(KEY_DOWNLOAD_FORMAT),
            localPath = bundle.getString(KEY_LOCAL_PATH),
            sourceQuality = bundle.getString(KEY_SOURCE_QUALITY),
            trackNumber = if (bundle.containsKey(KEY_TRACK_NUMBER)) bundle.getInt(KEY_TRACK_NUMBER) else null,
            localDateAddedSeconds = if (bundle.containsKey(KEY_LOCAL_DATE_ADDED)) bundle.getLong(KEY_LOCAL_DATE_ADDED) else null,
            localDateModifiedSeconds = if (bundle.containsKey(KEY_LOCAL_DATE_MODIFIED)) bundle.getLong(KEY_LOCAL_DATE_MODIFIED) else null,
        )
    }

    fun decodeArtists(bundle: Bundle): List<ArtistRef> {
        val names = bundle.getStringArrayList(KEY_ARTIST_NAMES).orEmpty()
        val ids = bundle.getStringArrayList(KEY_ARTIST_IDS).orEmpty()
        if (names.isEmpty()) return emptyList()
        return names.indices.map { index ->
            ArtistRef(
                name = names[index],
                browseId = ids.getOrNull(index)?.ifBlank { null },
            )
        }
    }
}
