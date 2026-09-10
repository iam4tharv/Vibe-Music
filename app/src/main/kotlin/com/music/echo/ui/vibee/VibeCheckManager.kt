package com.music.echo.ui.vibee

import android.content.Context
import androidx.compose.runtime.Immutable
import com.music.echo.db.MusicDatabase
import com.music.echo.db.entities.EventWithSong
import com.music.echo.models.toMediaMetadata
import com.music.echo.playback.PlayerConnection
import com.music.echo.playback.queues.YouTubeQueue
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import com.music.innertube.models.WatchEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Calendar

@Immutable
data class VibeCheckInfo(
    val title: String,
    val description: String,
    val dominantArtist: String?,
    val dominantGenreOrMood: String,
    val recentSongTitles: List<String>,
    val timestamp: Long = System.currentTimeMillis()
)

object VibeCheckManager {
    private const val PREF_VIBE_DISMISSED_AT = "vibe_check_dismissed_at"
    private const val DISMISS_COOLDOWN_MS = 2 * 60 * 60 * 1000L // 2 hours cooldown

    suspend fun computeCurrentVibe(
        context: Context,
        database: MusicDatabase
    ): VibeCheckInfo? = withContext(Dispatchers.IO) {
        try {
            val prefs = context.getSharedPreferences("vibee_vibe_prefs", Context.MODE_PRIVATE)
            val lastDismissed = prefs.getLong(PREF_VIBE_DISMISSED_AT, 0L)
            val now = System.currentTimeMillis()
            
            if (now - lastDismissed < DISMISS_COOLDOWN_MS) {
                return@withContext null
            }

            val recentEvents = database.events().first().take(12)
            if (recentEvents.size < 3) {
                return@withContext null
            }

            // Extract artist frequency
            val artists = recentEvents.mapNotNull { it.song.artists.firstOrNull()?.name }
            val artistCounts = artists.groupingBy { it }.eachCount()
            val topArtist = artistCounts.maxByOrNull { it.value }?.key

            val songTitles = recentEvents.take(4).map { it.song.title }

            // Time of day detection
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val (timeOfDay, timeMood) = when (hour) {
                in 5..11 -> "Morning" to "uplifting acoustic & morning flow"
                in 12..16 -> "Afternoon" to "energetic beats & afternoon groove"
                in 17..21 -> "Evening" to "chill sunset & melodic vibes"
                else -> "Late Night" to "atmospheric lo-fi & night mood"
            }

            val vibeTitle = if (!topArtist.isNullOrBlank()) {
                "$timeOfDay Vibe • $topArtist"
            } else {
                "$timeOfDay Vibe Check"
            }

            val vibeDesc = if (!topArtist.isNullOrBlank()) {
                "You've been listening to $topArtist and similar tracks. Ready to keep the flow going?"
            } else {
                "You've been grooving to $timeMood. Want fresh tracks matching this vibe?"
            }

            VibeCheckInfo(
                title = vibeTitle,
                description = vibeDesc,
                dominantArtist = topArtist,
                dominantGenreOrMood = timeMood,
                recentSongTitles = songTitles
            )
        } catch (e: Exception) {
            null
        }
    }

    fun dismissVibeCheck(context: Context) {
        val prefs = context.getSharedPreferences("vibee_vibe_prefs", Context.MODE_PRIVATE)
        prefs.edit().putLong(PREF_VIBE_DISMISSED_AT, System.currentTimeMillis()).apply()
    }

    suspend fun keepVibeGoing(
        context: Context,
        playerConnection: PlayerConnection?,
        vibeInfo: VibeCheckInfo
    ) = withContext(Dispatchers.IO) {
        val query = if (!vibeInfo.dominantArtist.isNullOrBlank()) {
            "${vibeInfo.dominantArtist} radio mix"
        } else {
            "${vibeInfo.dominantGenreOrMood} mix"
        }

        val searchResult = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull()
        val songItem = searchResult?.items?.filterIsInstance<SongItem>()?.firstOrNull()
        
        withContext(Dispatchers.Main) {
            if (songItem != null && playerConnection?.player != null) {
                playerConnection.playQueue(
                    YouTubeQueue(
                        songItem.endpoint ?: WatchEndpoint(videoId = songItem.id),
                        songItem.toMediaMetadata()
                    )
                )
            }
        }
    }
}
