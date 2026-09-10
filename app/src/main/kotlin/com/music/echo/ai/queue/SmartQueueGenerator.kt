package com.music.echo.ai.queue

import android.content.Context
import androidx.media3.common.MediaItem
import com.music.echo.db.InternalDatabase
import com.music.echo.db.entities.PlayHistoryEntity
import com.music.echo.utils.dataStore
import com.music.echo.constants.OpenRouterApiKey
import com.music.echo.BuildConfig
import com.music.echo.constants.AiProviderKey
import com.music.echo.constants.OpenRouterBaseUrlKey
import com.music.echo.constants.OpenRouterModelKey
import com.music.echo.extensions.toMediaItem
import com.music.echo.models.MediaMetadata
import com.music.echo.utils.get
import com.music.innertube.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber

data class TasteProfile(
    val languageShare: Map<String, Float>,
    val genreShare: Map<String, Float>,
    val topArtists: List<String>,
    val historyCount: Int
)

fun computeTasteProfile(history: List<PlayHistoryEntity>): TasteProfile {
    val decay = 0.95f
    val langWeights = mutableMapOf<String, Float>()
    val genreWeights = mutableMapOf<String, Float>()
    var totalWeight = 0f

    history.forEachIndexed { i, entry ->
        val w = Math.pow(decay.toDouble(), i.toDouble()).toFloat()
        entry.language?.let { langWeights[it] = (langWeights[it] ?: 0f) + w }
        entry.genre?.let { genreWeights[it] = (genreWeights[it] ?: 0f) + w }
        totalWeight += w
    }

    fun normalize(m: Map<String, Float>) =
        if (totalWeight == 0f) emptyMap() else m.mapValues { it.value / totalWeight }

    val minShare = 0.08f
    val languageShare = normalize(langWeights).filterValues { it >= minShare }
    val genreShare = normalize(genreWeights).filterValues { it >= minShare }

    val topArtists = history.groupBy { it.artist }
        .mapValues { it.value.size }
        .entries.sortedByDescending { it.value }
        .take(8).map { it.key }

    return TasteProfile(languageShare, genreShare, topArtists, history.size)
}

object SmartQueueGenerator {
    private val client = OkHttpClient()

    private fun buildSystemPrompt() = """
        You are a music recommendation engine for an Android music app called Vibe Music.
        Given a user's weighted listening-history profile, generate song suggestions that
        authentically reflect their real taste distribution across languages and genres.
        Never overfit to only the single most recently played song — a user who listens to a
        mix of languages should get a queue that mixes them in roughly the same proportion as
        their real history, not a queue that collapses into one language just because the last
        song happened to be in that language.
        Return ONLY valid JSON matching the given schema. No prose, no markdown fences.
    """.trimIndent()

    private fun buildUserPrompt(
        n: Int,
        profile: TasteProfile,
        currentSong: PlayHistoryEntity,
        recentHistoryForDedup: List<PlayHistoryEntity>,
        explorationCount: Int
    ): String {
        val langStr = profile.languageShare.entries
            .joinToString(", ") { "${it.key} ${(it.value * 100).toInt()}%" }
        val genreStr = profile.genreShare.entries
            .joinToString(", ") { "${it.key} ${(it.value * 100).toInt()}%" }
        val historyStr = recentHistoryForDedup.joinToString("\n") { "- ${it.title} — ${it.artist}" }

        return """
            Generate $n song suggestions for the next queue.

            LISTENING PROFILE (from last ${profile.historyCount} plays, recency-weighted):
            Languages: $langStr
            Genres: $genreStr
            Top artists (recent): ${profile.topArtists.joinToString(", ")}
            Currently playing / just finished: "${currentSong.title}" by ${currentSong.artist} (${currentSong.language ?: "unknown"})

            RECENT HISTORY (do not repeat any of these exact tracks):
            $historyStr

            RULES:
            1. The language/genre mix of your suggestions should approximate the listening
               profile above, NOT just match the currently playing song's language.
            2. Do not put more than 2 songs from the same artist in a row.
            3. Do not suggest anything listed in RECENT HISTORY above.
            4. Reserve about $explorationCount of the $n suggestions for discovery — same general
               genre/language neighborhood, but an artist or track the user hasn't played recently.
            5. Order the list so the first 2–3 songs lean slightly toward the language/mood of the
               currently playing song (smooth transition), then rebalance toward the full profile
               distribution for the rest of the queue.
            6. Only suggest real, existing songs by real artists you're confident exist — never
               invent songs.

            Return JSON only, in exactly this schema:
            {
              "suggestions": [
                {"title": "...", "artist": "...", "language": "...", "genre": "...", "reason": "short phrase"}
              ]
            }
        """.trimIndent()
    }

    suspend fun generateSmartQueue(
        context: Context,
        n: Int = 15,
        currentSong: PlayHistoryEntity
    ): List<MediaItem>? = withContext(Dispatchers.IO) {
        try {
            val database = InternalDatabase.newInstance(context)
            val history = database.getRecentPlayHistory()
            if (history.size < 10) return@withContext null

            val profile = computeTasteProfile(history)
            val exploration = (n * 0.15).toInt().coerceAtLeast(1)
            val recentForDedup = history.take(40)

            val openRouterKey = context.dataStore.get(OpenRouterApiKey, BuildConfig.QUOUE)
            val baseUrl = context.dataStore.get(OpenRouterBaseUrlKey, "https://openrouter.ai/api/v1/chat/completions")
            
            // The prompt asks to use a slightly stronger model. Let's use llama-3.1-8b-instruct or similar.
            val model = context.dataStore.get(OpenRouterModelKey, "meta-llama/llama-3.1-8b-instruct")

            val jsonBody = JSONObject().apply {
                put("model", model)
                put("response_format", JSONObject().apply { put("type", "json_object") })
                put("messages", org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", buildSystemPrompt())
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", buildUserPrompt(n, profile, currentSong, recentForDedup, exploration))
                    })
                })
            }

            val request = Request.Builder()
                .url(baseUrl)
                .header("Authorization", "Bearer ${openRouterKey.ifEmpty { BuildConfig.QUOUE }}")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Timber.e("SmartQueue API failed: ${response.code}")
                return@withContext null
            }

            val responseBody = response.body.string()
            val responseObj = JSONObject(responseBody)
            val content = responseObj.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
            
            val cleanedContent = content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val parsed = JSONObject(cleanedContent)
            val suggestionsArray = parsed.getJSONArray("suggestions")
            val mediaItems = mutableListOf<MediaItem>()

            for (i in 0 until suggestionsArray.length()) {
                val suggestion = suggestionsArray.getJSONObject(i)
                val title = suggestion.getString("title")
                val artist = suggestion.getString("artist")
                
                val result = YouTube.search("$title $artist", filter = YouTube.SearchFilter.FILTER_SONG).getOrNull()
                val topSong = result?.items?.firstOrNull() as? com.music.innertube.models.SongItem
                if (topSong != null) {
                    val mediaItem = topSong.toMediaItem()
                    mediaItems.add(mediaItem)
                }
            }

            return@withContext mediaItems.distinctBy { it.mediaId }.ifEmpty { null }
        } catch (e: Exception) {
            Timber.e(e, "Error generating smart queue")
            null
        }
    }
}
