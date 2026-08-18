package com.music.echo.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

data class JioSaavnTrack(
    val song: String,
    val artist: String,
    val url: String,
    val quality: String = "320kbps"
)

object JioSaavnAPI {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun cleanString(input: String): String {
        return input.replace(Regex("&amp;"), "&")
            .replace(Regex("&quot;"), "\"")
            .replace(Regex("&#039;"), "'")
            .replace(Regex("\\(.*?\\)|\\[.*?\\]"), "")
            .lowercase()
            .replace(Regex("\\s*-\\s*topic\\b"), "")
            .replace(Regex("vevo\\b"), "")
            .trim()
    }

    private fun upgradeTo320kbps(url: String): String {
        if (url.isEmpty()) return url
        var result = url
        if (result.startsWith("http://")) {
            result = result.replace("http://", "https://")
        }
        // JioSaavn CDN url quality upgrade: replace _128, _160, _96, _48 with _320
        result = result.replace("_128.mp4", "_320.mp4")
            .replace("_160.mp4", "_320.mp4")
            .replace("_96.mp4", "_320.mp4")
            .replace("_48.mp4", "_320.mp4")
            .replace("_128.m4a", "_320.m4a")
            .replace("_160.m4a", "_320.m4a")
            .replace("_96.m4a", "_320.m4a")
            .replace("_48.m4a", "_320.m4a")
        return result
    }

    private fun decryptUrl(encryptedUrl: String): String? {
        return try {
            val key = "38346591"
            val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
            val secretKey = SecretKeySpec(key.toByteArray(), "DES")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)
            val decoded = Base64.decode(encryptedUrl, Base64.DEFAULT)
            val decryptedBytes = cipher.doFinal(decoded)
            String(decryptedBytes)
        } catch (e: Exception) {
            Timber.e(e, "JioSaavnAPI: Failed to decrypt URL")
            null
        }
    }

    suspend fun search(queryTitle: String, queryArtist: String): JioSaavnTrack? = withContext(Dispatchers.IO) {
        val titleClean = cleanString(queryTitle)
        val artistClean = cleanString(queryArtist)
        val fullQuery = "$queryTitle $queryArtist".trim()
        val encodedQuery = try {
            URLEncoder.encode(fullQuery, "UTF-8")
        } catch (e: Exception) {
            fullQuery.replace(" ", "%20")
        }

        var track = fetchFromOfficialAPI(encodedQuery, titleClean, artistClean)
        if (track == null) {
            // Fallback search with title only
            val encodedTitle = try {
                URLEncoder.encode(queryTitle, "UTF-8")
            } catch (e: Exception) {
                queryTitle.replace(" ", "%20")
            }
            track = fetchFromOfficialAPI(encodedTitle, titleClean, artistClean)
        }
        return@withContext track
    }

    private fun levenshteinDistance(lhs: CharSequence, rhs: CharSequence): Int {
        val lhsLength = lhs.length
        val rhsLength = rhs.length

        var cost = Array(lhsLength + 1) { it }
        var newCost = Array(lhsLength + 1) { 0 }

        for (i in 1..rhsLength) {
            newCost[0] = i
            for (j in 1..lhsLength) {
                val match = if (lhs[j - 1] == rhs[i - 1]) 0 else 1
                val costReplace = cost[j - 1] + match
                val costInsert = cost[j] + 1
                val costDelete = newCost[j - 1] + 1
                newCost[j] = minOf(costInsert, costDelete, costReplace)
            }
            val swap = cost
            cost = newCost
            newCost = swap
        }
        return cost[lhsLength]
    }

    private fun similarity(s1: String, s2: String): Double {
        if (s1.isEmpty() && s2.isEmpty()) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        val maxLen = maxOf(s1.length, s2.length)
        val distance = levenshteinDistance(s1, s2)
        return 1.0 - (distance.toDouble() / maxLen)
    }

    private fun fetchFromOfficialAPI(query: String, titleClean: String, artistClean: String): JioSaavnTrack? {
        val url = "https://www.jiosaavn.com/api.php?__call=search.getResults&q=$query&n=10&p=1&_format=json&_marker=0&ctx=web6dot0"
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (!body.isNullOrEmpty()) {
                    if (body.trim().startsWith("{")) {
                        val json = JSONObject(body)
                        val results = json.optJSONArray("results")
                        if (results != null && results.length() > 0) {
                            var firstValidTrack: JioSaavnTrack? = null

                            for (i in 0 until results.length()) {
                                val songObj = results.getJSONObject(i)
                                val sTitleRaw = songObj.optString("song", "")
                                val sArtistRaw = songObj.optString("primary_artists", "")
                                val sTitle = cleanString(sTitleRaw)
                                val sArtist = cleanString(sArtistRaw)
                                
                                val isTitleMatch = sTitle == titleClean || (sTitle.isNotEmpty() && titleClean.isNotEmpty() && (sTitle.startsWith(titleClean) || titleClean.startsWith(sTitle) || similarity(sTitle, titleClean) >= 0.7))
                                val sArtistParts = sArtist.split(Regex("\\s+&\\s+|\\s*,\\s+|\\s+and\\s+|\\s+ft\\.?\\s+|\\s+feat\\.?\\s+|\\s+featuring\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
                                val targetArtistParts = artistClean.split(Regex("\\s+&\\s+|\\s*,\\s+|\\s+and\\s+|\\s+ft\\.?\\s+|\\s+feat\\.?\\s+|\\s+featuring\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
                                
                                val isArtistMatch = sArtist == artistClean || 
                                    sArtist.replace(" ", "") == artistClean.replace(" ", "") ||
                                    similarity(sArtist, artistClean) >= 0.6 ||
                                    (sArtist.isNotEmpty() && artistClean.isNotEmpty() && 
                                     (sArtistParts.any { a1 -> targetArtistParts.any { a2 -> a1 == a2 || a1.replace(" ", "") == a2.replace(" ", "") || similarity(a1, a2) >= 0.6 } } || targetArtistParts.any { a1 -> sArtistParts.any { a2 -> a1 == a2 || a1.replace(" ", "") == a2.replace(" ", "") || similarity(a1, a2) >= 0.6 } }))
                                
                                val encryptedUrl = songObj.optString("encrypted_media_url", "")
                                if (encryptedUrl.isNotEmpty()) {
                                    val decryptedUrl = decryptUrl(encryptedUrl)
                                    if (decryptedUrl != null) {
                                        val finalUrl = upgradeTo320kbps(decryptedUrl)
                                        val track = JioSaavnTrack(
                                            song = sTitleRaw,
                                            artist = sArtistRaw,
                                            url = finalUrl,
                                            quality = "320kbps"
                                        )
                                        
                                        if (firstValidTrack == null) {
                                            firstValidTrack = track
                                        }

                                        if (isTitleMatch && (isArtistMatch || artistClean.isEmpty())) {
                                            Timber.d("JioSaavnAPI: Found strict match $sTitleRaw with URL: $finalUrl")
                                            return track
                                        }
                                    }
                                }
                            }
                            
                            if (firstValidTrack != null) {
                                Timber.d("JioSaavnAPI: No strict match found, returning first valid track: ${firstValidTrack.song}")
                                return firstValidTrack
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "JioSaavnAPI: Failed official endpoint")
        }
        return null
    }
}
