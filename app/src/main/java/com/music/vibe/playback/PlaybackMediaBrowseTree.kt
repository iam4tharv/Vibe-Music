package com.music.vibe.playback

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.music.vibe.R
import com.music.vibe.data.YtMusicRepository
import com.music.vibe.data.innertube.InnertubeParser
import com.music.vibe.data.model.BrowseItem
import com.music.vibe.data.model.NOTIFICATION_ART_PX
import com.music.vibe.data.model.SearchFilter
import com.music.vibe.data.model.SearchResult
import com.music.vibe.data.model.ShelfItem
import com.music.vibe.data.model.Song
import com.music.vibe.data.model.artworkAt
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Android Auto and MediaBrowser browse-tree builders, content-style hints,
 * and search pagination helpers extracted from [PlaybackService].
 */
internal object PlaybackMediaBrowseTree {

    const val MEDIA_ROOT_ID = "root"
    const val MEDIA_RECENTS_ID = "recents"
    const val MEDIA_QUICK_PICKS_ID = "quick_picks"
    const val MEDIA_PLAYLISTS_ID = "playlists"
    const val MEDIA_MORE_ID = "more"
    const val MEDIA_LIKED_ID = "liked"
    const val MEDIA_DOWNLOADS_ID = "downloads"
    const val MEDIA_LOCAL_MUSIC_ID = "local_music"
    const val MAX_AUTO_PAGE_SIZE = 50
    const val MAX_AUTO_SEARCH_QUERY_CHARS = 200
    const val MAX_AUTO_SEARCH_CACHE_ENTRIES = 20

    val ROOT_CHILDREN: List<String> = listOf(
        MEDIA_QUICK_PICKS_ID,
        MEDIA_RECENTS_ID,
        MEDIA_PLAYLISTS_ID,
        MEDIA_LIKED_ID,
        MEDIA_MORE_ID,
    )

    const val EXTRA_CONTENT_STYLE_SUPPORTED = "android.media.browse.extra.CONTENT_STYLE_SUPPORTED"
    const val EXTRA_CONTENT_STYLE_SUPPORTED_AX = "androidx.media.contentstyle.CONTENT_STYLE_SUPPORTED"
    const val EXTRA_CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.extra.CONTENT_STYLE_BROWSABLE_HINT"
    const val EXTRA_CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.extra.CONTENT_STYLE_PLAYABLE_HINT"
    const val EXTRA_CONTENT_STYLE_BROWSABLE_HINT_AX = "androidx.media.contentstyle.CONTENT_STYLE_BROWSABLE_HINT"
    const val EXTRA_CONTENT_STYLE_PLAYABLE_HINT_AX = "androidx.media.contentstyle.CONTENT_STYLE_PLAYABLE_HINT"
    const val EXTRA_CONTENT_STYLE_BROWSABLE_HINT_LEGACY = "androidx.media.utils.CONTENT_STYLE_BROWSABLE_HINT"
    const val EXTRA_CONTENT_STYLE_PLAYABLE_HINT_LEGACY = "androidx.media.utils.CONTENT_STYLE_PLAYABLE_HINT"
    const val CONTENT_STYLE_LIST_ITEM_HINT_VALUE = 1
    const val CONTENT_STYLE_GRID_ITEM_HINT_VALUE = 4

    fun buildContentStyleBundle(browsableHint: Int, playableHint: Int): Bundle =
        Bundle().apply {
            putInt(EXTRA_CONTENT_STYLE_BROWSABLE_HINT, browsableHint)
            putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT, playableHint)
            putInt(EXTRA_CONTENT_STYLE_BROWSABLE_HINT_AX, browsableHint)
            putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT_AX, playableHint)
            putInt(EXTRA_CONTENT_STYLE_BROWSABLE_HINT_LEGACY, browsableHint)
            putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT_LEGACY, playableHint)
            putInt("android.media.extras.CONTENT_STYLE_BROWSABLE_HINT", browsableHint)
            putInt("android.media.extras.CONTENT_STYLE_PLAYABLE_HINT", playableHint)
            putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", browsableHint)
            putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", playableHint)
            putBoolean(EXTRA_CONTENT_STYLE_SUPPORTED, true)
            putBoolean(EXTRA_CONTENT_STYLE_SUPPORTED_AX, true)
        }

    fun <T> paginate(items: List<T>, page: Int, pageSize: Int): List<T> {
        if (page < 0 || pageSize <= 0 || items.isEmpty()) return emptyList()
        val safePageSize = pageSize.coerceAtMost(MAX_AUTO_PAGE_SIZE)
        val fromLong = page.toLong() * safePageSize.toLong()
        if (fromLong >= items.size.toLong()) return emptyList()
        val from = fromLong.toInt()
        val to = (fromLong + safePageSize).coerceAtMost(items.size.toLong()).toInt()
        return items.subList(from, to)
    }

    fun createGridPlaylistItem(
        mediaId: String,
        title: String,
        subtitle: String? = null,
        artworkUrl: String? = null,
        folderType: Int = MediaMetadata.FOLDER_TYPE_PLAYLISTS,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtworkUri(artworkUrl?.artworkAt(NOTIFICATION_ART_PX)?.let(Uri::parse))
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setFolderType(folderType)
                .setExtras(buildContentStyleBundle(CONTENT_STYLE_GRID_ITEM_HINT_VALUE, CONTENT_STYLE_LIST_ITEM_HINT_VALUE))
                .build(),
        )
        .build()

    fun shelfToGridPlaylistItem(playlist: ShelfItem): MediaItem {
        val browseId = playlist.browseId ?: "VL${playlist.videoId}"
        val mediaId = if (browseId.startsWith("VL")) "playlist:${browseId.removePrefix("VL")}" else "browse:$browseId"
        return createGridPlaylistItem(
            mediaId = mediaId,
            title = playlist.title,
            subtitle = playlist.subtitle,
            artworkUrl = playlist.thumbnailUrl,
        )
    }

    suspend fun searchAll(query: String): Pair<List<Song>, List<BrowseItem>> {
        val safeQuery = query.trim().take(MAX_AUTO_SEARCH_QUERY_CHARS)
        if (safeQuery.isEmpty()) return emptyList<Song>() to emptyList()
        val results = try {
            withTimeoutOrNull(3_500L) {
                YtMusicRepository.search(safeQuery, SearchFilter.ALL).getOrNull()
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        val songs = results.mapNotNull {
            when (it) {
                is SearchResult.Track -> it.song
                is SearchResult.TopTrack -> it.song
                else -> null
            }
        }.distinctBy { it.videoId }
        val browses = results.mapNotNull { (it as? SearchResult.Browse)?.item }
            .distinctBy { it.browseId }
        return songs to browses
    }

    fun browseItemToGridItem(item: BrowseItem): MediaItem = createGridPlaylistItem(
        mediaId = "browse:${item.browseId}",
        title = item.title,
        subtitle = item.subtitle,
        artworkUrl = item.thumbnailUrl,
        folderType = if (item.browseId.startsWith("MPREb_")) MediaMetadata.FOLDER_TYPE_ALBUMS else MediaMetadata.FOLDER_TYPE_PLAYLISTS,
    )

    fun createFolderItem(
        mediaId: String,
        title: String,
        subtitle: String? = null,
        folderType: Int = MediaMetadata.FOLDER_TYPE_MIXED,
        isGrid: Boolean = false,
    ): MediaItem {
        val style = if (isGrid) CONTENT_STYLE_GRID_ITEM_HINT_VALUE else CONTENT_STYLE_LIST_ITEM_HINT_VALUE
        val extras = buildContentStyleBundle(style, CONTENT_STYLE_LIST_ITEM_HINT_VALUE)
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setFolderType(if (isGrid && folderType == MediaMetadata.FOLDER_TYPE_MIXED) MediaMetadata.FOLDER_TYPE_ALBUMS else folderType)
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }

    fun MediaItem.withGridStyle(): MediaItem {
        val currentExtras = mediaMetadata.extras ?: Bundle()
        val newExtras = Bundle(currentExtras).apply {
            putInt(EXTRA_CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_GRID_ITEM_HINT_VALUE)
            putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_GRID_ITEM_HINT_VALUE)
            putInt(EXTRA_CONTENT_STYLE_BROWSABLE_HINT_AX, CONTENT_STYLE_GRID_ITEM_HINT_VALUE)
            putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT_AX, CONTENT_STYLE_GRID_ITEM_HINT_VALUE)
            putInt(EXTRA_CONTENT_STYLE_BROWSABLE_HINT_LEGACY, CONTENT_STYLE_GRID_ITEM_HINT_VALUE)
            putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT_LEGACY, CONTENT_STYLE_GRID_ITEM_HINT_VALUE)
            putInt("android.media.extras.CONTENT_STYLE_BROWSABLE_HINT", CONTENT_STYLE_GRID_ITEM_HINT_VALUE)
            putInt("android.media.extras.CONTENT_STYLE_PLAYABLE_HINT", CONTENT_STYLE_GRID_ITEM_HINT_VALUE)
            putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", CONTENT_STYLE_GRID_ITEM_HINT_VALUE)
            putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", CONTENT_STYLE_GRID_ITEM_HINT_VALUE)
            putBoolean(EXTRA_CONTENT_STYLE_SUPPORTED, true)
            putBoolean(EXTRA_CONTENT_STYLE_SUPPORTED_AX, true)
        }
        return buildUpon()
            .setMediaMetadata(
                mediaMetadata.buildUpon()
                    .setIsBrowsable(true)
                    .setIsPlayable(true)
                    .setFolderType(MediaMetadata.FOLDER_TYPE_ALBUMS)
                    .setExtras(newExtras)
                    .build(),
            )
            .build()
    }

    fun ShelfItem.toMediaItemOrNull(songCache: MutableMap<String, Song>): MediaItem? {
        val vid = videoId
        if (vid != null) {
            val song = Song(
                videoId = vid,
                title = title,
                artist = InnertubeParser.artistFromSubtitle(subtitle),
                thumbnailUrl = thumbnailUrl,
            )
            songCache[vid] = song
            return song.toMediaItem()
        }
        val bid = browseId
        if (bid != null) {
            return MediaItem.Builder()
                .setMediaId("browse:$bid")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setSubtitle(subtitle)
                        .setArtworkUri(thumbnailUrl.artworkAt(NOTIFICATION_ART_PX)?.let { Uri.parse(it) })
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setFolderType(MediaMetadata.FOLDER_TYPE_ALBUMS)
                        .build(),
                )
                .build()
        }
        return null
    }

    fun ShelfItem.toSongOrNull(): Song? {
        val id = videoId ?: return null
        return Song(
            videoId = id,
            title = title,
            artist = InnertubeParser.artistFromSubtitle(subtitle),
            thumbnailUrl = thumbnailUrl,
        )
    }

    fun createLoginPromptItem(context: Context): MediaItem =
        MediaItem.Builder()
            .setMediaId("msg:login_required")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(context.getString(R.string.auto_login_title))
                    .setSubtitle(context.getString(R.string.auto_login_subtitle))
                    .setIsBrowsable(false)
                    .setIsPlayable(false)
                    .build(),
            )
            .build()

    fun createInfoItem(mediaId: String, title: String, subtitle: String? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(false)
                    .setIsPlayable(false)
                    .setExtras(buildContentStyleBundle(CONTENT_STYLE_LIST_ITEM_HINT_VALUE, CONTENT_STYLE_LIST_ITEM_HINT_VALUE))
                    .build(),
            )
            .build()
}
