package com.music.echo.utils.potoken

import android.content.Context
import android.content.SharedPreferences
import android.util.LruCache
import com.music.echo.utils.cipher.CipherDeobfuscator
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

class PoTokenGenerator {

    private var webView: PoTokenWebView? = null
    private val mutex = Mutex()

    @Volatile
    private var cachedSessionId: String? = null
    @Volatile
    private var cachedStreamingPot: String? = null
    @Volatile
    private var cachedStreamingPotTime: Long = 0L

    private val playerPotCache = LruCache<String, String>(MAX_IN_MEMORY_PLAYER_TOKENS)

    private fun getPrefs(): SharedPreferences? {
        return try {
            val context = CipherDeobfuscator.appContext
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } catch (e: Exception) {
            null
        }
    }

    private fun getValidStreamingToken(sessionId: String, now: Long): String? {
        // 1. In-memory quick check
        if (cachedSessionId == sessionId && cachedStreamingPot != null) {
            if (now - cachedStreamingPotTime in 0..STREAMING_TOKEN_TTL_MS) {
                return cachedStreamingPot
            }
        }
        // 2. Persistent SharedPreferences check across app/playback sessions
        try {
            val prefs = getPrefs() ?: return null
            val savedSession = prefs.getString(KEY_SESSION_ID, null)
            val savedPot = prefs.getString(KEY_STREAMING_POT, null)
            val time = prefs.getLong(KEY_STREAMING_POT_TIME, 0L)
            if (savedSession == sessionId && savedPot != null && (now - time in 0..STREAMING_TOKEN_TTL_MS)) {
                cachedSessionId = savedSession
                cachedStreamingPot = savedPot
                cachedStreamingPotTime = time
                Timber.tag(TAG).d("Loaded persistent streaming PoToken for session $sessionId (age: ${(now - time) / 1000}s)")
                return savedPot
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to read persistent streaming token")
        }
        return null
    }

    private fun saveStreamingToken(sessionId: String, token: String, now: Long) {
        cachedSessionId = sessionId
        cachedStreamingPot = token
        cachedStreamingPotTime = now
        try {
            val prefs = getPrefs() ?: return
            prefs.edit()
                .putString(KEY_SESSION_ID, sessionId)
                .putString(KEY_STREAMING_POT, token)
                .putLong(KEY_STREAMING_POT_TIME, now)
                .apply()
            Timber.tag(TAG).d("Persisted streaming PoToken for session $sessionId")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to persist streaming token")
        }
    }

    private fun getValidPlayerToken(videoId: String, now: Long): String? {
        // 1. In-memory LRU cache
        synchronized(playerPotCache) {
            playerPotCache.get(videoId)?.let { return it }
        }
        // 2. Persistent SharedPreferences cache
        try {
            val prefs = getPrefs() ?: return null
            val token = prefs.getString("$KEY_PLAYER_PREFIX$videoId", null) ?: return null
            val time = prefs.getLong("$KEY_PLAYER_TIME_PREFIX$videoId", 0L)
            if (now - time in 0..PLAYER_TOKEN_TTL_MS) {
                synchronized(playerPotCache) {
                    playerPotCache.put(videoId, token)
                }
                Timber.tag(TAG).d("Loaded persistent player PoToken for videoId $videoId (age: ${(now - time) / 1000}s)")
                return token
            } else {
                prefs.edit()
                    .remove("$KEY_PLAYER_PREFIX$videoId")
                    .remove("$KEY_PLAYER_TIME_PREFIX$videoId")
                    .apply()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to read persistent player token")
        }
        return null
    }

    private fun savePlayerToken(videoId: String, token: String, now: Long) {
        synchronized(playerPotCache) {
            playerPotCache.put(videoId, token)
        }
        try {
            val prefs = getPrefs() ?: return
            val videoIds = prefs.getStringSet(KEY_CACHED_VIDEO_IDS, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
            
            val editor = prefs.edit()
            editor.putString("$KEY_PLAYER_PREFIX$videoId", token)
            editor.putLong("$KEY_PLAYER_TIME_PREFIX$videoId", now)
            
            videoIds.add(videoId)
            if (videoIds.size > MAX_PERSISTED_PLAYER_TOKENS) {
                val excess = videoIds.size - MAX_PERSISTED_PLAYER_TOKENS
                val toRemove = videoIds.take(excess)
                toRemove.forEach { oldId ->
                    editor.remove("$KEY_PLAYER_PREFIX$oldId")
                    editor.remove("$KEY_PLAYER_TIME_PREFIX$oldId")
                    videoIds.remove(oldId)
                }
            }
            editor.putStringSet(KEY_CACHED_VIDEO_IDS, videoIds)
            editor.apply()
            Timber.tag(TAG).d("Persisted player PoToken for videoId $videoId")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to persist player token")
        }
    }

    suspend fun getWebClientPoToken(videoId: String, sessionId: String): PoTokenResult {
        val now = System.currentTimeMillis()
        val cachedPlayer = getValidPlayerToken(videoId, now)
        val cachedStreaming = getValidStreamingToken(sessionId, now)

        if (cachedPlayer != null && cachedStreaming != null) {
            Timber.tag(TAG).d("Reusing fully cached & persisted PoToken for videoId=$videoId (0 network/JS requests)")
            return PoTokenResult(cachedPlayer, cachedStreaming)
        }

        val wv = mutex.withLock {
            if (webView == null || webView!!.isExpired || webView!!.isDead) {
                Timber.tag(TAG).d("Creating new PoTokenWebView")
                webView?.close()
                webView = PoTokenWebView.getNewPoTokenGenerator(CipherDeobfuscator.appContext)
            }
            webView!!
        }

        val playerPot = cachedPlayer ?: run {
            val token = wv.generatePoToken(videoId)
            savePlayerToken(videoId, token, now)
            token
        }

        val streamingPot = cachedStreaming ?: run {
            val token = wv.generatePoToken(sessionId)
            saveStreamingToken(sessionId, token, now)
            token
        }

        return PoTokenResult(playerPot, streamingPot)
    }

    fun invalidateTokens() {
        cachedSessionId = null
        cachedStreamingPot = null
        cachedStreamingPotTime = 0L
        synchronized(playerPotCache) { playerPotCache.evictAll() }
        try {
            getPrefs()?.edit()?.clear()?.apply()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to clear PoToken prefs")
        }
    }

    fun invalidateVideoToken(videoId: String) {
        synchronized(playerPotCache) { playerPotCache.remove(videoId) }
        try {
            getPrefs()?.edit()
                ?.remove("$KEY_PLAYER_PREFIX$videoId")
                ?.remove("$KEY_PLAYER_TIME_PREFIX$videoId")
                ?.apply()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to remove video token for $videoId")
        }
    }

    suspend fun prewarm() {
        try {
            val now = System.currentTimeMillis()
            val prefs = getPrefs()
            val savedSession = prefs?.getString(KEY_SESSION_ID, null)
            val savedPot = prefs?.getString(KEY_STREAMING_POT, null)
            val time = prefs?.getLong(KEY_STREAMING_POT_TIME, 0L) ?: 0L

            if (savedSession != null && savedPot != null && (now - time in 0..STREAMING_TOKEN_TTL_MS)) {
                cachedSessionId = savedSession
                cachedStreamingPot = savedPot
                cachedStreamingPotTime = time
                Timber.tag(TAG).d("PoToken prewarm: restored valid session PoToken from storage")
            }

            mutex.withLock {
                if (webView == null || webView!!.isExpired || webView!!.isDead) {
                    webView?.close()
                    webView = PoTokenWebView.getNewPoTokenGenerator(CipherDeobfuscator.appContext)
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Prewarm failed")
        }
    }

    companion object {
        private const val TAG = "PoTokenGenerator"
        private const val PREFS_NAME = "echomusic_potoken_cache"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_STREAMING_POT = "streaming_pot"
        private const val KEY_STREAMING_POT_TIME = "streaming_pot_time"
        private const val KEY_PLAYER_PREFIX = "player_pot_"
        private const val KEY_PLAYER_TIME_PREFIX = "player_pot_time_"
        private const val KEY_CACHED_VIDEO_IDS = "cached_video_ids"

        private const val MAX_IN_MEMORY_PLAYER_TOKENS = 100
        private const val MAX_PERSISTED_PLAYER_TOKENS = 200

        // Streaming tokens valid for 12 hours
        private const val STREAMING_TOKEN_TTL_MS = 12 * 60 * 60 * 1000L
        // Track-specific player tokens valid for 6 hours
        private const val PLAYER_TOKEN_TTL_MS = 6 * 60 * 60 * 1000L
    }
}

