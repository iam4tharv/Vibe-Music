package com.music.vibe

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowRgb565
import coil3.request.bitmapConfig
import coil3.request.crossfade
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import com.music.vibe.auth.AuthStore
import com.music.vibe.data.DebugLog
import com.music.vibe.data.lyrics.LyricsTranslation
import androidx.appcompat.app.AppCompatDelegate
import com.music.vibe.ui.player.AndroidPlayerHost
import com.music.vibe.ui.player.PlayerPlatform
import com.music.vibe.data.canvas.CanvasCache
import com.music.vibe.data.smb.SmbCoverFetcher
import com.music.vibe.data.webdav.WebDavCoilAuth
import com.music.vibe.data.canvas.SpotifyToken
import com.music.vibe.data.spotify.SpotifyLibrary
import com.music.vibe.playback.AudioCache
import com.music.vibe.playback.LastPlayed
import com.music.vibe.playback.OriginalVersion
import com.music.vibe.data.innertube.Innertube
import com.music.vibe.data.innertube.AndroidStreamHooks
import com.music.vibe.data.listentogether.ListenTogether
import com.music.vibe.data.scrobbling.LastFM
import com.music.vibe.data.settings.AppSettings
import com.music.vibe.data.settings.SearchHistory
import com.music.vibe.data.sources.SourceRegistry
import com.music.vibe.data.stats.ArtistFacts
import com.music.vibe.data.stats.ListeningStats
import com.music.vibe.download.Downloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

class VibeMusicApplication : Application(), SingletonImageLoader.Factory {

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = ANNOUNCEMENTS_CHANNEL_ID
            val channel = NotificationChannel(
                channelId,
                getString(R.string.announcements_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = getString(R.string.announcements_channel_description)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
        try {
            android.system.Os.setenv("MESA_DEBUG", "silent", true)
            android.system.Os.setenv("MESA_LOG_FILE", "/dev/null", true)
            android.system.Os.setenv("MESA_LOG_LEVEL", "none", true)
        } catch (_: Throwable) {}
        ensureWebViewCacheDirs(this)
        // The player is drawn by the shared UI module; this is what it reads
        // underneath — settings, the Canvas decoder, outputs, the party.
        AndroidStreamHooks.installEarly()
        PlayerPlatform.install(AndroidPlayerHost(this))
        com.music.vibe.ui.AppUi.install(com.music.vibe.ui.AndroidAppUiHost)
        // The shared data layer (lyrics, the YouTube Music client) logs to
        // logcat on debug builds only, as the app's own DebugLog always has.
        if (BuildConfig.DEBUG) {
            DebugLog.sink = DebugLog.Sink { level, tag, message, error ->
                when (level) {
                    'D' -> android.util.Log.d(tag, message, error)
                    'I' -> android.util.Log.i(tag, message, error)
                    'W' -> android.util.Log.w(tag, message, error)
                    else -> android.util.Log.e(tag, message, error)
                }
            }
        }
        // The Spotify library and import live in the shared module; the tokens are the WebView's.
        SpotifyLibrary.auth = SpotifyLibrary.Auth {
            if (AppSettings.spotifySpdcToken.value.isBlank()) {
                null
            } else {
                SpotifyToken.accessToken()?.let { SpotifyLibrary.Tokens(it, SpotifyToken.clientToken()) }
            }
        }
        // The per-app language picker, which YouTube Music's `hl` follows.
        // Full tag (zh-Hant / zh-Hans preserved); Innertube maps to hl/gl.
        Innertube.appLanguage = { AppCompatDelegate.getApplicationLocales().get(0)?.toLanguageTag() }
        migrateScriptOnlyAppLocale()
        LyricsTranslation.cacheDir = cacheDir
        container = com.music.vibe.di.AppContainer(this).also {
            com.music.vibe.di.AppContainer.install(it)
        }
        // PlaybackService shares this process, so seeding the cookie here means
        // stream resolution is authenticated from the first play onwards.
        authStore = container.authStore
        // Migration-safe: an old single cookie becomes the first encrypted
        // session, while newer installs restore the profile the listener chose.
        val restoredSession = authStore.activeSession
        if (restoredSession != null && authStore.activeAccountId == null) {
            authStore.select(restoredSession.accountId, restoredSession.activeProfileId)
        }
        authStore.cookie = restoredSession?.cookie
        Innertube.cookie = restoredSession?.cookie
        if (restoredSession != null) {
            restoredSession.profiles.firstOrNull { it.profileId == restoredSession.activeProfileId }
                ?.let { Innertube.selectChannel(it.pageId, it.dataSyncId, it.authUser) }
            container.applicationScope.launch { Innertube.ensureSessionScope() }
        }
        container.initializeCoreStores()
        if (AppSettings.consumeVersionUpdate(BuildConfig.VERSION_CODE)) {
            AudioCache.clear()
            SingletonImageLoader.get(this).let { loader ->
                loader.memoryCache?.clear()
                loader.diskCache?.clear()
            }
        }
        // Initialize LastFM with saved settings if available
        initLastfm()
        // The public "apps open right now" count; see Presence.
        com.music.vibe.data.presence.AndroidPresence.install(this)
    }

    lateinit var container: com.music.vibe.di.AppContainer
        private set

    /**
     * Artwork loading, which was previously left entirely on Coil's defaults.
     *
     * The defaults aren't unreasonable, but the disk cache is sized at 2% of
     * free space — which on a full phone is the 10MB floor, a few screens of
     * covers, and covers are exactly the thing worth still having tomorrow.
     * Naming a directory alongside it keeps that cache somewhere identifiable
     * rather than in the process's temp dir.
     */
    private val imageOkHttpClient by lazy {
        OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 32
            })
            .connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { imageOkHttpClient }))
                add(WebDavCoilAuth())
                add(SmbCoverFetcher.Factory())
            }
            .coroutineContext(Dispatchers.IO)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .allowRgb565(true)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(200L * 1024 * 1024)
                    .build()
            }
            .crossfade(false)
            .build()

    private fun initLastfm() {
        val sessionKey = AppSettings.lastfmSessionKey.value
        if (sessionKey.isBlank()) return
        val endpoint = AppSettings.lastfmEndpoint.value.ifBlank { LastFM.DEFAULT_API_ENDPOINT }
        val apiKey = AppSettings.lastfmApiKey.value.trim()
        val secret = AppSettings.lastfmSecret.value.trim()
        if (apiKey.isBlank() || secret.isBlank()) return
        LastFM.configure(
            endpoint = endpoint,
            apiKey = apiKey,
            secret = secret,
            sessionKey = sessionKey,
        )
    }

    companion object {
        const val ANNOUNCEMENTS_CHANNEL_ID = "announcements"

        lateinit var authStore: AuthStore
            private set

        /**
         * One-time move from region-less script tags to regional ones.
         *
         * Early builds of the Simplified/Traditional picker stored `zh-Hant` /
         * `zh-Hans`. Android Views resolve those (script qualifiers), but the
         * shared Compose resources only match language + region — so Settings
         * read Traditional while the player read Simplified. Regional tags
         * match on both systems. Bare `zh` is left alone: it historically
         * meant Simplified.
         */
        private fun migrateScriptOnlyAppLocale() {
            val current = AppCompatDelegate.getApplicationLocales().get(0)?.toLanguageTag()
            val replacement = when (current) {
                "zh-Hant" -> "zh-TW"
                "zh-Hans" -> "zh-CN"
                else -> return
            }
            AppCompatDelegate.setApplicationLocales(
                androidx.core.os.LocaleListCompat.forLanguageTags(replacement),
            )
        }

        init {
            try {
                android.system.Os.setenv("MESA_DEBUG", "silent", true)
                android.system.Os.setenv("MESA_LOG_FILE", "/dev/null", true)
                android.system.Os.setenv("MESA_LOG_LEVEL", "none", true)
            } catch (_: Throwable) {}
        }

        private fun ensureWebViewCacheDirs(context: android.content.Context) {
            try {
                val cache = context.cacheDir
                val webViewDir = File(cache, "WebView/Default/HTTP Cache/Code Cache")
                File(webViewDir, "js").mkdirs()
                File(webViewDir, "wasm").mkdirs()
            } catch (_: Throwable) {}
        }
    }
}
