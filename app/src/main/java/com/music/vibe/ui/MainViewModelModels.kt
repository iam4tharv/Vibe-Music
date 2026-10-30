package com.music.vibe.ui

import com.music.vibe.auth.YouTubeProfile
import com.music.vibe.auth.profileId
import com.music.vibe.data.model.Account
import com.music.vibe.data.model.AccountChannel
import com.music.vibe.data.model.LibraryPage
import com.music.vibe.data.model.Song
import com.music.vibe.data.model.UiState
import com.music.vibe.data.model.UserPlaylist

/** What the Search tab searches: YouTube Music, or the Local Music folder on this device. */
enum class SearchSource { YOUTUBE, LIBRARY }

/** In-memory cache of signed-in listener state partitioned by account and profile. */
internal data class ListenerSnapshot(
    val account: Account?,
    val library: UiState<LibraryPage>,
    val history: UiState<List<Song>>,
    val playlists: List<UserPlaylist>,
    val owned: Map<String, Boolean>,
)

/**
 * Merges newly detected [AccountChannel]s from YouTube Music with previously persisted
 * [YouTubeProfile]s on the active Google session, dropping provisional `profile:` placeholders
 * once real channel IDs arrive.
 */
internal fun mergeDetectedProfiles(
    existingProfiles: List<YouTubeProfile>,
    activeProfileId: String?,
    channels: List<AccountChannel>,
): Pair<List<YouTubeProfile>, String?> {
    val detected = channels.map { channel ->
        YouTubeProfile(
            profileId(channel.pageId, channel.dataSyncId, channel.name),
            channel.name,
            channel.subtitle,
            channel.thumbnailUrl,
            channel.pageId,
            channel.dataSyncId,
            isBrandAccount = channel.pageId != null,
        )
    }
    val stalePlaceholder = if (detected.isEmpty()) {
        emptySet()
    } else {
        existingProfiles
            .filter { it.profileId.startsWith("profile:") }
            .map { it.profileId }
            .toSet()
    }
    val profiles = existingProfiles.filter { known ->
        known.profileId !in stalePlaceholder && detected.none { it.profileId == known.profileId }
    } + detected
    val selected = when {
        activeProfileId != null && activeProfileId !in stalePlaceholder -> activeProfileId
        activeProfileId in stalePlaceholder -> detected.singleOrNull()?.profileId
        else -> null
    } ?: profiles.firstOrNull()?.profileId
    return profiles to selected
}
