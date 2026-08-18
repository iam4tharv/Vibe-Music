
package com.music.echo.ui.vibee

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.music.echo.constants.*
import com.music.echo.utils.dataStore
import java.util.Locale

object SettingToggleHelper {
    val ALL_SETTINGS = listOf(
        "IsFirstRunKey",
        "EnableDynamicIconKey",
        "EnableHighRefreshRateKey",
        "EnableHapticsKey",
        "DynamicThemeKey",
        "PureBlackKey",
        "PureBlackMiniPlayerKey",
        "MiniPlayerOutlineKey",
        "SlimNavBarKey",
        "SquigglySliderKey",
        "SwipeToSongKey",
        "SwipeToRemoveSongKey",
        "UseNewPlayerDesignKey",
        "UseNewMiniPlayerDesignKey",
        "ShowCodecOnPlayerKey",
        "HidePlayerSliderKey",
        "HidePlayerThumbnailKey",
        "CropAlbumArtKey",
        "SeekExtraSeconds",
        "PauseOnMute",
        "ResumeOnBluetoothConnectKey",
        "KeepScreenOn",
        "DeveloperModeKey",
        "EnableKugouKey",
        "EnableLrcLibKey",
        "EnableBetterLyricsKey",
        "EnableSimpMusicKey",
        "EnableYouLyPlusKey",
        "EnablePaxsenixKey",
        "HideExplicitKey",
        "SponsorBlockEnabledKey",
        "HideVideoSongsKey",
        "HideYoutubeShortsKey",
        "ShowArtistDescriptionKey",
        "ShowArtistSubscriberCountKey",
        "ShowMonthlyListenersKey",
        "ShowArtistVideoKey",
        "ShowArtistBackgroundVideoKey",
        "ProxyEnabledKey",
        "YtmSyncKey",
        "ShowAudioFallbackToastKey",
        "AudioOffload",
        "PersistentQueueKey",
        "PersistentShuffleAcrossQueuesKey",
        "RememberShuffleAndRepeatKey",
        "ShuffleModeKey",
        "SkipSilenceKey",
        "SkipSilenceInstantKey",
        "AudioNormalizationKey",
        "AutoLoadMoreKey",
        "DisableLoadMoreWhenRepeatAllKey",
        "AutoDownloadOnLikeKey",
        "SimilarContent",
        "AutoSkipNextOnErrorKey",
        "StopMusicOnTaskClearKey",
        "ShufflePlaylistFirstKey",
        "PreventDuplicateTracksInQueueKey",
        "CrossfadeEnabledKey",
        "CrossfadeGaplessKey",
        "AutomixCrossfadeKey",
        "AutomixDebugOverlayKey",
        "EnableExportAsMp3Key",
        "PauseListenHistoryKey",
        "PauseSearchHistoryKey",
        "DisableScreenshotKey",
        "EnableGoogleCastKey",
        "EnableListenTogetherKey",
        "ListenTogetherAutoApprovalKey",
        "ListenTogetherSyncVolumeKey",
        "ListenTogetherSmartResyncKey",
        "ListenTogetherInTopBarKey",
        "ListenTogetherIsHostKey",
        "EnableLastFMScrobblingKey",
        "LastFMUseNowPlaying",
        "LastFMUseSendLikes",
        "SongSortDescendingKey",
        "PlaylistSongSortDescendingKey",
        "AutoPlaylistSongSortDescendingKey",
        "ArtistSortDescendingKey",
        "AlbumSortDescendingKey",
        "PlaylistSortDescendingKey",
        "AddToPlaylistSortDescendingKey",
        "ArtistSongSortDescendingKey",
        "MixSortDescendingKey",
        "EnableDiscordRPCKey",
        "DiscordShowWhenPausedKey",
        "DiscordActivityButton1EnabledKey",
        "DiscordActivityButton2EnabledKey",
        "LocalSongsSortDescendingKey",
        "PlaylistEditLockKey",
        "QueueEditLockKey",
        "RandomizeHomeOrderKey",
        "AlbumCanvasEnabledKey",
        "ShowLikedPlaylistKey",
        "ShowDownloadedPlaylistKey",
        "ShowExportedPlaylistKey",
        "ShowTopPlaylistKey",
        "ShowCachedPlaylistKey",
        "ShowUploadedPlaylistKey",
        "EnablePlayerSwipeKey",
        "ShowSpeedDialKey",
        "ShowCommentButtonKey",
        "ShowLyricsKey",
        "SwipeLyricsKey",
        "EnableLyricsThumbnailPlayPauseKey",
        "LyricsClickKey",
        "LyricsScrollKey",
        "LyricsRomanizeJapaneseKey",
        "LyricsRomanizeKoreanKey",
        "LyricsRomanizeChineseKey",
        "LyricsRomanizeRussianKey",
        "LyricsRomanizeUkrainianKey",
        "LyricsRomanizeSerbianKey",
        "LyricsRomanizeBulgarianKey",
        "LyricsRomanizeBelarusianKey",
        "LyricsRomanizeKyrgyzKey",
        "LyricsRomanizeMacedonianKey",
        "LyricsRomanizeHindiKey",
        "LyricsRomanizePunjabiKey",
        "LyricsRomanizeAsMainKey",
        "LyricsRomanizeCyrillicByLineKey",
        "TranslateLyricsKey",
        "AutoTranslateKey",
        "AiRecommendationsKey",
        "HeyVibeeEnabledKey",
        "LyricsGlowEffectKey",
        "AppleMusicLyricsBlurKey",
        "LyricsStandardBlurKey",
        "HideStatusBarOnFullscreenKey",
        "SwipeThumbnailKey",
        "RotatingThumbnailKey",
        "CanvasThumbnailAnimationKey",
        "UseLoginForBrowse",
        "SpatialAudioEnabledKey",
        "CrossfeedEnabledKey",
        "ListenBrainzEnabledKey",
        "LrcLibLyricsEnabledKey",
        "KuGouLyricsEnabledKey",
        "UnisonLyricsEnabledKey",
        "YouTubeSubtitleLyricsEnabledKey",
        "PreloadNextSongEnabledKey",
        "PreloadLyricsEnabledKey",
        "LiquidGlassGlobalEnabledKey",
        "LiquidGlassChromaticAberrationKey",
        "LiquidGlassDepthEffectKey",
        "LiquidGlassPlayerEnabledKey",
        "LiquidGlassMiniPlayerEnabledKey",
        "LiquidGlassNavBarEnabledKey",
        "UseFloatingNavBarKey",
    )

    suspend fun toggleSetting(context: Context, setting: String, value: Boolean): Boolean {
        val keyToUse = ALL_SETTINGS.find { it.equals(setting, ignoreCase = true) } ?: return false
        val prefKey = when (keyToUse) {
            "IsFirstRunKey" -> IsFirstRunKey
            "EnableDynamicIconKey" -> EnableDynamicIconKey
            "EnableHighRefreshRateKey" -> EnableHighRefreshRateKey
            "EnableHapticsKey" -> EnableHapticsKey
            "DynamicThemeKey" -> DynamicThemeKey
            "PureBlackKey" -> PureBlackKey
            "PureBlackMiniPlayerKey" -> PureBlackMiniPlayerKey
            "MiniPlayerOutlineKey" -> MiniPlayerOutlineKey
            "SlimNavBarKey" -> SlimNavBarKey
            "SquigglySliderKey" -> SquigglySliderKey
            "SwipeToSongKey" -> SwipeToSongKey
            "SwipeToRemoveSongKey" -> SwipeToRemoveSongKey
            "UseNewPlayerDesignKey" -> UseNewPlayerDesignKey
            "UseNewMiniPlayerDesignKey" -> UseNewMiniPlayerDesignKey
            "ShowCodecOnPlayerKey" -> ShowCodecOnPlayerKey
            "HidePlayerSliderKey" -> HidePlayerSliderKey
            "HidePlayerThumbnailKey" -> HidePlayerThumbnailKey
            "CropAlbumArtKey" -> CropAlbumArtKey
            "SeekExtraSeconds" -> SeekExtraSeconds
            "PauseOnMute" -> PauseOnMute
            "ResumeOnBluetoothConnectKey" -> ResumeOnBluetoothConnectKey
            "KeepScreenOn" -> KeepScreenOn
            "DeveloperModeKey" -> DeveloperModeKey
            "EnableKugouKey" -> EnableKugouKey
            "EnableLrcLibKey" -> EnableLrcLibKey
            "EnableBetterLyricsKey" -> EnableBetterLyricsKey
            "EnableSimpMusicKey" -> EnableSimpMusicKey
            "EnableYouLyPlusKey" -> EnableYouLyPlusKey
            "EnablePaxsenixKey" -> EnablePaxsenixKey
            "HideExplicitKey" -> HideExplicitKey
            "SponsorBlockEnabledKey" -> SponsorBlockEnabledKey
            "HideVideoSongsKey" -> HideVideoSongsKey
            "HideYoutubeShortsKey" -> HideYoutubeShortsKey
            "ShowArtistDescriptionKey" -> ShowArtistDescriptionKey
            "ShowArtistSubscriberCountKey" -> ShowArtistSubscriberCountKey
            "ShowMonthlyListenersKey" -> ShowMonthlyListenersKey
            "ShowArtistVideoKey" -> ShowArtistVideoKey
            "ShowArtistBackgroundVideoKey" -> ShowArtistBackgroundVideoKey
            "ProxyEnabledKey" -> ProxyEnabledKey
            "YtmSyncKey" -> YtmSyncKey
            "ShowAudioFallbackToastKey" -> ShowAudioFallbackToastKey
            "AudioOffload" -> AudioOffload
            "PersistentQueueKey" -> PersistentQueueKey
            "PersistentShuffleAcrossQueuesKey" -> PersistentShuffleAcrossQueuesKey
            "RememberShuffleAndRepeatKey" -> RememberShuffleAndRepeatKey
            "ShuffleModeKey" -> ShuffleModeKey
            "SkipSilenceKey" -> SkipSilenceKey
            "SkipSilenceInstantKey" -> SkipSilenceInstantKey
            "AudioNormalizationKey" -> AudioNormalizationKey
            "AutoLoadMoreKey" -> AutoLoadMoreKey
            "DisableLoadMoreWhenRepeatAllKey" -> DisableLoadMoreWhenRepeatAllKey
            "AutoDownloadOnLikeKey" -> AutoDownloadOnLikeKey
            "SimilarContent" -> SimilarContent
            "AutoSkipNextOnErrorKey" -> AutoSkipNextOnErrorKey
            "StopMusicOnTaskClearKey" -> StopMusicOnTaskClearKey
            "ShufflePlaylistFirstKey" -> ShufflePlaylistFirstKey
            "PreventDuplicateTracksInQueueKey" -> PreventDuplicateTracksInQueueKey
            "CrossfadeEnabledKey" -> CrossfadeEnabledKey
            "CrossfadeGaplessKey" -> CrossfadeGaplessKey
            "AutomixCrossfadeKey" -> AutomixCrossfadeKey
            "AutomixDebugOverlayKey" -> AutomixDebugOverlayKey
            "EnableExportAsMp3Key" -> EnableExportAsMp3Key
            "PauseListenHistoryKey" -> PauseListenHistoryKey
            "PauseSearchHistoryKey" -> PauseSearchHistoryKey
            "DisableScreenshotKey" -> DisableScreenshotKey
            "EnableGoogleCastKey" -> EnableGoogleCastKey
            "EnableListenTogetherKey" -> EnableListenTogetherKey
            "ListenTogetherAutoApprovalKey" -> ListenTogetherAutoApprovalKey
            "ListenTogetherSyncVolumeKey" -> ListenTogetherSyncVolumeKey
            "ListenTogetherSmartResyncKey" -> ListenTogetherSmartResyncKey
            "ListenTogetherInTopBarKey" -> ListenTogetherInTopBarKey
            "ListenTogetherIsHostKey" -> ListenTogetherIsHostKey
            "EnableLastFMScrobblingKey" -> EnableLastFMScrobblingKey
            "LastFMUseNowPlaying" -> LastFMUseNowPlaying
            "LastFMUseSendLikes" -> LastFMUseSendLikes
            "SongSortDescendingKey" -> SongSortDescendingKey
            "PlaylistSongSortDescendingKey" -> PlaylistSongSortDescendingKey
            "AutoPlaylistSongSortDescendingKey" -> AutoPlaylistSongSortDescendingKey
            "ArtistSortDescendingKey" -> ArtistSortDescendingKey
            "AlbumSortDescendingKey" -> AlbumSortDescendingKey
            "PlaylistSortDescendingKey" -> PlaylistSortDescendingKey
            "AddToPlaylistSortDescendingKey" -> AddToPlaylistSortDescendingKey
            "ArtistSongSortDescendingKey" -> ArtistSongSortDescendingKey
            "MixSortDescendingKey" -> MixSortDescendingKey
            "EnableDiscordRPCKey" -> EnableDiscordRPCKey
            "DiscordShowWhenPausedKey" -> DiscordShowWhenPausedKey
            "DiscordActivityButton1EnabledKey" -> DiscordActivityButton1EnabledKey
            "DiscordActivityButton2EnabledKey" -> DiscordActivityButton2EnabledKey
            "LocalSongsSortDescendingKey" -> LocalSongsSortDescendingKey
            "PlaylistEditLockKey" -> PlaylistEditLockKey
            "QueueEditLockKey" -> QueueEditLockKey
            "RandomizeHomeOrderKey" -> RandomizeHomeOrderKey
            "AlbumCanvasEnabledKey" -> AlbumCanvasEnabledKey
            "ShowLikedPlaylistKey" -> ShowLikedPlaylistKey
            "ShowDownloadedPlaylistKey" -> ShowDownloadedPlaylistKey
            "ShowExportedPlaylistKey" -> ShowExportedPlaylistKey
            "ShowTopPlaylistKey" -> ShowTopPlaylistKey
            "ShowCachedPlaylistKey" -> ShowCachedPlaylistKey
            "ShowUploadedPlaylistKey" -> ShowUploadedPlaylistKey
            "EnablePlayerSwipeKey" -> EnablePlayerSwipeKey
            "ShowSpeedDialKey" -> ShowSpeedDialKey
            "ShowCommentButtonKey" -> ShowCommentButtonKey
            "ShowLyricsKey" -> ShowLyricsKey
            "SwipeLyricsKey" -> SwipeLyricsKey
            "EnableLyricsThumbnailPlayPauseKey" -> EnableLyricsThumbnailPlayPauseKey
            "LyricsClickKey" -> LyricsClickKey
            "LyricsScrollKey" -> LyricsScrollKey
            "LyricsRomanizeJapaneseKey" -> LyricsRomanizeJapaneseKey
            "LyricsRomanizeKoreanKey" -> LyricsRomanizeKoreanKey
            "LyricsRomanizeChineseKey" -> LyricsRomanizeChineseKey
            "LyricsRomanizeRussianKey" -> LyricsRomanizeRussianKey
            "LyricsRomanizeUkrainianKey" -> LyricsRomanizeUkrainianKey
            "LyricsRomanizeSerbianKey" -> LyricsRomanizeSerbianKey
            "LyricsRomanizeBulgarianKey" -> LyricsRomanizeBulgarianKey
            "LyricsRomanizeBelarusianKey" -> LyricsRomanizeBelarusianKey
            "LyricsRomanizeKyrgyzKey" -> LyricsRomanizeKyrgyzKey
            "LyricsRomanizeMacedonianKey" -> LyricsRomanizeMacedonianKey
            "LyricsRomanizeHindiKey" -> LyricsRomanizeHindiKey
            "LyricsRomanizePunjabiKey" -> LyricsRomanizePunjabiKey
            "LyricsRomanizeAsMainKey" -> LyricsRomanizeAsMainKey
            "LyricsRomanizeCyrillicByLineKey" -> LyricsRomanizeCyrillicByLineKey
            "TranslateLyricsKey" -> TranslateLyricsKey
            "AutoTranslateKey" -> AutoTranslateKey
            "AiRecommendationsKey" -> AiRecommendationsKey
            "HeyVibeeEnabledKey" -> HeyVibeeEnabledKey
            "LyricsGlowEffectKey" -> LyricsGlowEffectKey
            "AppleMusicLyricsBlurKey" -> AppleMusicLyricsBlurKey
            "LyricsStandardBlurKey" -> LyricsStandardBlurKey
            "HideStatusBarOnFullscreenKey" -> HideStatusBarOnFullscreenKey
            "SwipeThumbnailKey" -> SwipeThumbnailKey
            "RotatingThumbnailKey" -> RotatingThumbnailKey
            "CanvasThumbnailAnimationKey" -> CanvasThumbnailAnimationKey
            "UseLoginForBrowse" -> UseLoginForBrowse
            "SpatialAudioEnabledKey" -> SpatialAudioEnabledKey
            "CrossfeedEnabledKey" -> CrossfeedEnabledKey
            "ListenBrainzEnabledKey" -> ListenBrainzEnabledKey
            "LrcLibLyricsEnabledKey" -> LrcLibLyricsEnabledKey
            "KuGouLyricsEnabledKey" -> KuGouLyricsEnabledKey
            "UnisonLyricsEnabledKey" -> UnisonLyricsEnabledKey
            "YouTubeSubtitleLyricsEnabledKey" -> YouTubeSubtitleLyricsEnabledKey
            "PreloadNextSongEnabledKey" -> PreloadNextSongEnabledKey
            "PreloadLyricsEnabledKey" -> PreloadLyricsEnabledKey
            "LiquidGlassGlobalEnabledKey" -> LiquidGlassGlobalEnabledKey
            "LiquidGlassChromaticAberrationKey" -> LiquidGlassChromaticAberrationKey
            "LiquidGlassDepthEffectKey" -> LiquidGlassDepthEffectKey
            "LiquidGlassPlayerEnabledKey" -> LiquidGlassPlayerEnabledKey
            "LiquidGlassMiniPlayerEnabledKey" -> LiquidGlassMiniPlayerEnabledKey
            "LiquidGlassNavBarEnabledKey" -> LiquidGlassNavBarEnabledKey
            "UseFloatingNavBarKey" -> UseFloatingNavBarKey
            else -> null
        }
        
        if (prefKey != null) {
            context.dataStore.edit { it[prefKey] = value }
            return true
        }
        return false
    }
}
