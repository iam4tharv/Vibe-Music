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
                    when (key) {
                        "density_scale_factor",
                        "custom_density_scale_value",
                        "thumbnailCornerRadius",
                        "crossfadeDuration",
                        "scrobbleDelayPercent",
                        "historyDuration",
                        "lyricsTextSize",
                        "lyricsLineSpacing",
                        "playerVolume",
                        "swipeSensitivity",
                        "spatial_audio_strength",
                        "bass_boost",
                        "virtualizer",
                        "liquidGlassSurfaceOpacity",
                        "liquidGlassVibrancy",
                        "liquidGlassBlurRadius",
                        "liquidGlassLensHeight",
                        "liquidGlassLensAmount" -> {
                            mutablePrefs[androidx.datastore.preferences.core.floatPreferencesKey(key)] = value.toFloatOrNull() ?: 0f
                        }
                        "selectedThemeColor",
                        "songsPlayedCount",
                        "maxImageCacheSize",
                        "maxSongCacheSize",
                        "scrobbleMinSongDuration",
                        "scrobbleDelaySeconds",
                        "local_songs_min_duration_seconds",
                        "repeatMode",
                        "lastOpenedVersionCode",
                        "songsListenedCount",
                        "preload_next_song_limit",
                        "liquidGlassTextColor",
                        "liquidGlassSurfaceTintColor" -> {
                            mutablePrefs[androidx.datastore.preferences.core.intPreferencesKey(key)] = value.toIntOrNull() ?: 0
                        }
                        "spotify_access_token_expires_at",
                        "listenTogetherSessionTimestamp",
                        "discord_token_expires_at",
                        "last_like_song_sync",
                        "last_library_song_sync",
                        "last_album_sync",
                        "last_artist_sync",
                        "last_playlist_sync",
                        "last_full_sync",
                        "lastLosslessSync",
                        "CipherLastUpdatedKey",
                        "CipherManualUpdate1Key",
                        "CipherManualUpdate2Key",
                        "CipherManualUpdate3Key" -> {
                            mutablePrefs[androidx.datastore.preferences.core.longPreferencesKey(key)] = value.toLongOrNull() ?: 0L
                        }
                        "isFirstRun",
                        "enableDynamicIcon",
                        "enableHighRefreshRate",
                        "enableHaptics",
                        "dynamicTheme",
                        "pureBlack",
                        "pureBlackMiniPlayer",
                        "miniPlayerOutline",
                        "slimNavBar",
                        "squigglySlider",
                        "SwipeToSong",
                        "SwipeToRemoveSong",
                        "useNewPlayerDesign",
                        "useNewMiniPlayerDesign",
                        "showCodecOnPlayer",
                        "hidePlayerSlider",
                        "hidePlayerThumbnail",
                        "cropAlbumArt",
                        "seekExtraSeconds",
                        "pauseOnMute",
                        "dataSaver",
                        "resumeOnBluetoothConnect",
                        "keepScreenOn",
                        "developerMode",
                        "enableKugou",
                        "enableLrclib",
                        "enableBetterLyrics",
                        "enableSimpMusic",
                        "enableYouLyPlus",
                        "enablePaxsenix",
                        "hideExplicit",
                        "sponsor_block_enabled",
                        "hideVideoSongs",
                        "hideYoutubeShorts",
                        "showArtistDescription",
                        "showArtistSubscriberCount",
                        "showMonthlyListeners",
                        "showArtistVideo",
                        "showArtistBackgroundVideo",
                        "proxyEnabled",
                        "ytmSync",
                        "show_audio_fallback_toast",
                        "enableOffload",
                        "persistentQueue",
                        "persistentShuffleAcrossQueues",
                        "rememberShuffleAndRepeat",
                        "shuffleMode",
                        "skipSilence",
                        "skipSilenceInstant",
                        "audioNormalization",
                        "autoLoadMore",
                        "disableLoadMoreWhenRepeatAll",
                        "autoDownloadOnLike",
                        "similarContent",
                        "autoSkipNextOnError",
                        "stopMusicOnTaskClear",
                        "shufflePlaylistFirst",
                        "preventDuplicateTracksInQueue",
                        "crossfadeEnabled",
                        "crossfadeGapless",
                        "automixCrossfade",
                        "automixDebugOverlay",
                        "enableExportAsMp3",
                        "pauseListenHistory",
                        "pauseSearchHistory",
                        "disableScreenshot",
                        "enableGoogleCast",
                        "enableListenTogether",
                        "listenTogetherAutoApproval",
                        "listenTogetherSyncVolume",
                        "listenTogetherSmartResync",
                        "listenTogetherInTopBar",
                        "listenTogetherIsHost",
                        "lastfmScrobblingEnable",
                        "lastfmUseNowPlaying",
                        "lastfmUseSendLikes",
                        "songSortDescending",
                        "playlistSongSortDescending",
                        "autoPlaylistSongSortDescending",
                        "artistSortDescending",
                        "albumSortDescending",
                        "playlistSortDescending",
                        "addToPlaylistSortDescending",
                        "artistSongSortDescending",
                        "albumSortDescending",
                        "enable_discord_rpc",
                        "discord_show_when_paused",
                        "discord_activity_button1_enabled",
                        "discord_activity_button2_enabled",
                        "local_songs_sort_descending",
                        "playlistEditLock",
                        "queueEditLockV2",
                        "randomizeHomeOrder",
                        "albumCanvasEnabled",
                        "show_liked_playlist",
                        "show_downloaded_playlist",
                        "show_exported_playlist",
                        "show_top_playlist",
                        "show_cached_playlist",
                        "show_uploaded_playlist",
                        "enable_player_swipe",
                        "showSpeedDial",
                        "show_comment_button",
                        "showLyrics",
                        "swipeLyrics",
                        "enableLyricsThumbnailPlayPause",
                        "lyricsClick",
                        "lyricsScrollKey",
                        "lyricsRomanizeJapanese",
                        "lyricsRomanizeKorean",
                        "lyricsRomanizeChinese",
                        "lyricsRomanizeRussian",
                        "lyricsRomanizeUkrainian",
                        "lyricsRomanizeSerbian",
                        "lyricsRomanizeBulgarian",
                        "lyricsRomanizeBelarusian",
                        "lyricsRomanizeKyrgyz",
                        "lyricsRomanizeMacedonian",
                        "lyricsRomanizeHindi",
                        "lyricsRomanizePunjabi",
                        "lyricsRomanizeAsMain",
                        "lyricsRomanizeCyrillicByLine",
                        "translateLyrics",
                        "autoTranslate",
                        "aiRecommendations",
                        "heyVibeeEnabled",
                        "lyricsGlowEffect",
                        "appleMusicLyricsBlur",
                        "lyricsStandardBlur",
                        "hideStatusBarOnFullscreen",
                        "swipeThumbnail",
                        "rotatingThumbnail",
                        "canvasThumbnailAnimation",
                        "useLoginForBrowse",
                        "showDonationDialog",
                        "advanced_resampling",
                        "spatial_audio_enabled",
                        "crossfeed_enabled",
                        "listenbrainz_enabled",
                        "lrclib_lyrics_enabled",
                        "kugou_lyrics_enabled",
                        "unison_lyrics_enabled",
                        "youtube_subtitle_lyrics_enabled",
                        "preload_next_song_enabled",
                        "preload_lyrics_enabled",
                        "liquidGlassGlobalEnabled",
                        "liquidGlassChromaticAberration",
                        "liquidGlassDepthEffect",
                        "liquidGlassPlayerEnabled",
                        "liquidGlassMiniPlayerEnabled",
                        "liquidGlassNavBarEnabled",
                        "shakeToPlayNext",
                        "useFloatingNavBar" -> {
                            mutablePrefs[androidx.datastore.preferences.core.booleanPreferencesKey(key)] = value.toBoolean()
                        }
                        "spotify_sp_dc",
                        "spotify_sp_key",
                        "spotify_account_name",
                        "spotify_account_avatar_url",
                        "spotify_access_token",
                        "darkMode",
                        "defaultOpenTab",
                        "gridItemSize",
                        "sliderStyle",
                        "appLanguage",
                        "contentLanguage",
                        "contentCountry",
                        "suggestionRegion",
                        "proxyUrl",
                        "proxyType",
                        "proxyUsername",
                        "proxyPassword",
                        "selectedYtmPlaylists",
                        "audioQuality",
                        "ipVersion",
                        "downloadQuality",
                        "exportDirectoryUri",
                        "exportingSongIds",
                        "exportedSongIds",
                        "listenTogetherServerUrl",
                        "listenTogetherUsername",
                        "listenTogetherBlockedUsers",
                        "listenTogetherSessionToken",
                        "listenTogetherRoomCode",
                        "listenTogetherUserId",
                        "lastfmSession",
                        "lastfmUsername",
                        "chipSortType",
                        "songSortType",
                        "playlistSongSortType",
                        "autoPlaylistSongSortType",
                        "artistSortType",
                        "albumSortType",
                        "playlistSortType",
                        "addToPlaylistSortType",
                        "artistSongSortType",
                        "mixSortType",
                        "discord_token",
                        "discord_refresh_token",
                        "discord_name",
                        "discord_username",
                        "discord_avatar_url",
                        "discord_presence_status",
                        "discord_activity_type",
                        "discord_activity_name",
                        "discord_activity_details",
                        "discord_activity_state",
                        "discord_activity_platform",
                        "discord_large_image_type",
                        "discord_large_image_custom_url",
                        "discord_small_image_type",
                        "discord_small_image_custom_url",
                        "discord_activity_button1_label",
                        "discord_activity_button1_url_source",
                        "discord_activity_button1_custom_url",
                        "discord_activity_button2_label",
                        "discord_activity_button2_url_source",
                        "discord_activity_button2_custom_url",
                        "local_songs_sort_type",
                        "songFilter",
                        "artistFilter",
                        "albumFilter",
                        "losslessGithubToken",
                        "losslessGithubUsername",
                        "losslessGithubAvatar",
                        "artistViewType",
                        "albumViewType",
                        "playlistViewType",
                        "discover",
                        "lyricsProvider",
                        "lyricsProviderOrder",
                        "topSize",
                        "player_buttons_style",
                        "playerBackgroundStyle",
                        "miniPlayerBackgroundStyle",
                        "lyricsTextPosition",
                        "openRouterApiKey",
                        "aiProvider",
                        "openRouterBaseUrl",
                        "openRouterModel",
                        "translateMode",
                        "translateLanguage",
                        "deeplApiKey",
                        "deeplFormality",
                        "lyricsAnimationStyle",
                        "searchSource",
                        "visitorData",
                        "dataSyncId",
                        "innerTubeCookie",
                        "accountName",
                        "accountEmail",
                        "accountChannelHandle",
                        "listenbrainz_token" -> {
                            mutablePrefs[androidx.datastore.preferences.core.stringPreferencesKey(key)] = value
                        }
                        else -> {
                            // fallback
                            if (value == "true" || value == "false") {
                                mutablePrefs[androidx.datastore.preferences.core.booleanPreferencesKey(key)] = value.toBoolean()
                            } else if (value.toIntOrNull() != null) {
                                mutablePrefs[androidx.datastore.preferences.core.intPreferencesKey(key)] = value.toInt()
                            } else if (value.toFloatOrNull() != null) {
                                mutablePrefs[androidx.datastore.preferences.core.floatPreferencesKey(key)] = value.toFloat()
                            } else {
                                mutablePrefs[androidx.datastore.preferences.core.stringPreferencesKey(key)] = value
                            }
                        }
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
                val backfilledPlaylistSongMaps = payload.playlistSongMaps.groupBy { it.playlistId }.flatMap { (_, maps) -> maps.mapIndexed { index, map -> map.copy(position = index) } }; database.insertPlaylistSongMaps(backfilledPlaylistSongMaps)
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
