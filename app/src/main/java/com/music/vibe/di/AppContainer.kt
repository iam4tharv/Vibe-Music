package com.music.vibe.di

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.music.vibe.auth.AuthStore
import com.music.vibe.data.LocalMediaRepository
import com.music.vibe.data.YtMusicRepository
import com.music.vibe.data.canvas.CanvasCache
import com.music.vibe.data.canvas.SpotifyToken
import com.music.vibe.data.db.VibeDatabase
import com.music.vibe.data.innertube.AndroidStreamHooks
import com.music.vibe.data.listentogether.ListenTogether
import com.music.vibe.data.settings.AppSettings
import com.music.vibe.data.settings.SearchHistory
import com.music.vibe.data.sources.SourceRegistry
import com.music.vibe.data.stats.ArtistFacts
import com.music.vibe.data.stats.ListeningStats
import com.music.vibe.download.Downloads
import com.music.vibe.playback.AudioCache
import com.music.vibe.playback.LastPlayed
import com.music.vibe.playback.OriginalVersion
import com.music.vibe.playback.PartyPersonalQueueStash
import com.music.vibe.playback.audio.LosslessOutput
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.concurrent.thread

/**
 * Injectable coroutine dispatchers so repositories, ViewModels, and services
 * can be tested deterministically without hardcoded `Dispatchers.IO`.
 */
data class DispatcherProvider(
    val io: CoroutineDispatcher = Dispatchers.IO,
    val default: CoroutineDispatcher = Dispatchers.Default,
    val main: CoroutineDispatcher = Dispatchers.Main,
)

/**
 * Lightweight dependency injection container managing application-scoped
 * database, coroutine scope, dispatchers, and core repositories/stores.
 *
 * Replaces scattered unmanaged `CoroutineScope(SupervisorJob() + Dispatchers.IO)`
 * allocations and untyped `.init(context)` sequences with a single structured
 * graph owned by [com.music.vibe.VibeMusicApplication].
 */
class AppContainer(
    context: Context,
    val dispatchers: DispatcherProvider = DispatcherProvider(),
    databaseOverride: VibeDatabase? = null,
) {
    val appContext: Context = context.applicationContext

    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.io)

    val database: VibeDatabase = databaseOverride ?: VibeDatabase.getInstance(appContext)

    val authStore: AuthStore by lazy { AuthStore(appContext) }

    val youTubeRepository: YtMusicRepository = YtMusicRepository

    val localMediaRepository: LocalMediaRepository = LocalMediaRepository

    /**
     * Initializes all application stores in deterministic dependency order,
     * ensuring [VibeDatabase] and its automatic legacy migration complete
     * before any store reads state.
     */
    @OptIn(UnstableApi::class)
    fun initializeCoreStores() {
        val backgroundInit = thread(name = "startup-init") {
            SourceRegistry.init(appContext)
            AndroidStreamHooks.initInnerTubeX(appContext)
            CanvasCache.init(appContext)
        }
        AppSettings.init(appContext, authStore)
        LosslessOutput.init(appContext)
        ListenTogether.init(appContext)
        SearchHistory.init(appContext)
        LastPlayed.init(appContext)
        PartyPersonalQueueStash.init(appContext)
        OriginalVersion.init(appContext)
        Downloads.init(appContext)
        ListeningStats.init(appContext)
        ArtistFacts.init(appContext)
        AudioCache.init(appContext)
        SpotifyToken.init(appContext)
        backgroundInit.join()
    }

    fun close() {
        applicationScope.cancel()
    }

    companion object {
        @Volatile
        private var current: AppContainer? = null

        fun get(context: Context): AppContainer {
            return current ?: synchronized(this) {
                current ?: AppContainer(context.applicationContext).also { current = it }
            }
        }

        internal fun install(container: AppContainer) {
            current = container
        }

        internal fun setInstance(container: AppContainer) {
            current = container
        }
    }
}
