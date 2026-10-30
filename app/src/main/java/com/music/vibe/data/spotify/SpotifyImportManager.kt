package com.music.vibe.data.spotify

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.music.vibe.auth.AuthStore
import com.music.vibe.data.YtMusicRepository
import com.music.vibe.data.db.VibeDatabase
import com.music.vibe.data.model.PlaylistPrivacy
import com.music.vibe.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * State of the app-scoped Spotify playlist/album import job.
 */
sealed interface SpotifyImportState {
    data object Idle : SpotifyImportState

    data class FetchingMetadata(
        val url: String,
    ) : SpotifyImportState

    data class Matching(
        val jobId: String,
        val url: String,
        val title: String,
        val completed: Int,
        val total: Int,
        val matchedCount: Int,
    ) : SpotifyImportState

    data class Saving(
        val jobId: String,
        val title: String,
        val matchedCount: Int,
        val totalCount: Int,
        val isPartial: Boolean,
    ) : SpotifyImportState

    data class Interrupted(
        val jobId: String,
        val url: String,
        val title: String,
        val matchedSongs: List<Song>,
        val completed: Int,
        val total: Int,
        val createdPlaylistId: String? = null,
        val savedSongCount: Int = 0,
        val lastError: String? = null,
    ) : SpotifyImportState

    data class Completed(
        val title: String,
        val matchedCount: Int,
        val totalCount: Int,
        val isPartial: Boolean,
        val unmatchedTracks: List<SpotifyImportTrack>,
        val browseId: String?,
        val savedLocally: Boolean,
    ) : SpotifyImportState {
        val statusSummary: String
            get() = if (isPartial) {
                "Saved $matchedCount of $totalCount songs to $title"
            } else {
                "Saved $matchedCount of $totalCount songs to $title"
            }
    }

    data class Failed(
        val url: String,
        val message: String,
    ) : SpotifyImportState
}

/**
 * Event emitted when a Spotify import finishes saving a playlist so the UI/ViewModel
 * can refresh shelves and show a snackbar/notice.
 */
data class SpotifyPlaylistCreatedEvent(
    val browseId: String?,
    val title: String,
    val matchedCount: Int,
    val totalCount: Int,
    val isPartial: Boolean,
    val savedLocally: Boolean,
    val songs: List<Song>,
)

/**
 * App-scoped manager for background Spotify playlist & album imports.
 *
 * Coordinates with [SpotifyImportService] (foreground `dataSync` service) and
 * persists matched tracks incrementally to [VibeDatabase.spotifyImportCheckpointDao]
 * so process death or network failures mid-import can be resumed or saved partially
 * without losing matched tracks or creating duplicate playlists.
 */
object SpotifyImportManager {

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null
    private val checkpointMutex = Mutex()

    private val savePartialRequested = AtomicBoolean(false)
    private val serviceTimedOut = AtomicBoolean(false)

    private val _state = MutableStateFlow<SpotifyImportState>(SpotifyImportState.Idle)
    val state: StateFlow<SpotifyImportState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<SpotifyPlaylistCreatedEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<SpotifyPlaylistCreatedEvent> = _events.asSharedFlow()

    @Volatile
    private var currentPrivacy: PlaylistPrivacy = PlaylistPrivacy.PRIVATE

    @Volatile
    private var currentSignedIn: Boolean = false

    // Injectable hooks for deterministic unit / Robolectric testing
    internal var trackFetcher: suspend (SpotifyImportTarget) -> Pair<String, List<SpotifyImportTrack>> =
        { target -> SpotifyImporter.fetchTracks(target) }

    internal var singleTrackMatcher: (suspend (SpotifyImportTrack) -> Song?)? = null

    internal var remotePlaylistCreator: suspend (title: String, privacy: PlaylistPrivacy) -> String =
        { title, privacy ->
            YtMusicRepository.createPlaylist(title, privacy, emptyList()).getOrThrow()
        }

    internal var remotePlaylistTrackAdder: suspend (playlistId: String, videoIds: List<String>) -> Unit =
        { playlistId, videoIds ->
            YtMusicRepository.addToPlaylist(playlistId, videoIds, dedupe = false).getOrThrow()
        }

    internal var serviceStarter: (Context) -> Unit = { context ->
        SpotifyImportService.start(context)
    }

    internal var serviceStopper: (Context) -> Unit = { context ->
        SpotifyImportService.stop(context)
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Checks for an incomplete checkpoint from a previous killed process and exposes
     * [SpotifyImportState.Interrupted] without auto-starting a foreground service.
     */
    fun init(context: Context) {
        val db = VibeDatabase.getInstance(context.applicationContext)
        if (activeJob?.isActive == true) return
        val checkpoint = db.spotifyImportCheckpointDao.loadActiveCheckpoint() ?: return
        savePartialRequested.set(false)
        serviceTimedOut.set(false)
        _state.value = SpotifyImportState.Interrupted(
            jobId = checkpoint.jobId,
            url = checkpoint.playlistUrl,
            title = checkpoint.playlistTitle,
            matchedSongs = checkpoint.matchedSongs,
            completed = checkpoint.nextIndex.coerceIn(0, checkpoint.totalCount),
            total = checkpoint.totalCount,
            createdPlaylistId = checkpoint.createdPlaylistId,
            savedSongCount = checkpoint.savedSongCount,
            lastError = null,
        )
    }

    /**
     * Starts a new Spotify import from a user UI tap.
     */
    fun startImport(
        context: Context,
        rawUrl: String,
        privacy: PlaylistPrivacy = PlaylistPrivacy.PRIVATE,
        signedIn: Boolean = AuthStore(context.applicationContext).isSignedIn,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): Boolean {
        val target = SpotifyImporter.extractTarget(rawUrl)
        if (target == null) {
            _state.value = SpotifyImportState.Failed(rawUrl, "Invalid Spotify playlist or album link")
            return false
        }
        val appContext = context.applicationContext
        activeJob?.cancel()
        savePartialRequested.set(false)
        serviceTimedOut.set(false)
        currentPrivacy = privacy
        currentSignedIn = signedIn

        val jobId = UUID.randomUUID().toString()
        _state.value = SpotifyImportState.FetchingMetadata(rawUrl)
        runCatching { serviceStarter(appContext) }

        activeJob = scope.launch(dispatcher) {
            runImportJob(
                context = appContext,
                jobId = jobId,
                rawUrl = rawUrl,
                target = target,
                resumedCheckpoint = null,
            )
        }
        return true
    }

    /**
     * Resumes an [SpotifyImportState.Interrupted] import from a direct user UI tap.
     */
    fun resumeImport(
        context: Context,
        signedIn: Boolean = AuthStore(context.applicationContext).isSignedIn,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) {
        val appContext = context.applicationContext
        val db = VibeDatabase.getInstance(appContext)
        val checkpoint = db.spotifyImportCheckpointDao.loadActiveCheckpoint() ?: return
        val target = SpotifyImporter.extractTarget(checkpoint.playlistUrl)
            ?: SpotifyImportTarget(SpotifyImportType.PLAYLIST, "0000000000000000000000")

        activeJob?.cancel()
        savePartialRequested.set(false)
        serviceTimedOut.set(false)
        currentSignedIn = signedIn

        _state.value = SpotifyImportState.Matching(
            jobId = checkpoint.jobId,
            url = checkpoint.playlistUrl,
            title = checkpoint.playlistTitle,
            completed = checkpoint.nextIndex.coerceIn(0, checkpoint.totalCount),
            total = checkpoint.totalCount,
            matchedCount = checkpoint.matchedSongs.size,
        )
        runCatching { serviceStarter(appContext) }

        activeJob = scope.launch(dispatcher) {
            runImportJob(
                context = appContext,
                jobId = checkpoint.jobId,
                rawUrl = checkpoint.playlistUrl,
                target = target,
                resumedCheckpoint = checkpoint,
            )
        }
    }

    /**
     * Requests saving the songs matched so far while an import is running.
     */
    fun requestSavePartial(context: Context) {
        if (_state.value is SpotifyImportState.Interrupted) {
            saveInterruptedWhatWeGot(context)
            return
        }
        savePartialRequested.set(true)
        activeJob?.cancel(CancellationException("SAVE_PARTIAL"))
    }

    /**
     * Saves the already-matched tracks from an [SpotifyImportState.Interrupted] checkpoint.
     */
    fun saveInterruptedWhatWeGot(
        context: Context,
        signedIn: Boolean = AuthStore(context.applicationContext).isSignedIn,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) {
        val appContext = context.applicationContext
        val db = VibeDatabase.getInstance(appContext)
        val checkpoint = db.spotifyImportCheckpointDao.loadActiveCheckpoint() ?: return
        activeJob?.cancel()
        savePartialRequested.set(false)
        serviceTimedOut.set(false)
        currentSignedIn = signedIn

        activeJob = scope.launch(dispatcher) {
            saveMatchedPlaylistIdempotently(
                context = appContext,
                db = db,
                jobId = checkpoint.jobId,
                url = checkpoint.playlistUrl,
                title = checkpoint.playlistTitle,
                tracks = checkpoint.tracks,
                matchedByIndex = checkpoint.matchedSongs.mapIndexed { idx, s -> idx to s }.toMap(),
                completedCount = checkpoint.nextIndex,
                totalCount = checkpoint.totalCount,
                isPartial = checkpoint.nextIndex < checkpoint.totalCount,
                existingPlaylistId = checkpoint.createdPlaylistId,
                existingSavedCount = checkpoint.savedSongCount,
            )
        }
    }

    /**
     * Cancels the active import immediately, discards all progress, explicitly deletes
     * rows from `spotify_import_matched_tracks` and `spotify_import_checkpoint`, and
     * creates NO playlist.
     */
    fun cancelImport(context: Context) {
        val appContext = context.applicationContext
        savePartialRequested.set(false)
        serviceTimedOut.set(false)
        activeJob?.cancel(CancellationException("USER_CANCELLED"))
        activeJob = null
        val db = VibeDatabase.getInstance(appContext)
        db.spotifyImportCheckpointDao.clearAllCheckpoints()
        _state.value = SpotifyImportState.Idle
        runCatching { serviceStopper(appContext) }
    }

    /**
     * Discards an interrupted checkpoint without creating a playlist.
     */
    fun discardInterrupted(context: Context) {
        cancelImport(context)
    }

    /**
     * Dismisses terminal states (`Completed` or `Failed`) back to `Idle`.
     */
    fun dismissTerminalState() {
        val current = _state.value
        if (current is SpotifyImportState.Completed || current is SpotifyImportState.Failed) {
            _state.value = SpotifyImportState.Idle
        }
    }

    fun dismissCompleted() = dismissTerminalState()

    /**
     * Called by [SpotifyImportService.onTimeout] on Android 15+ when the system's
     * `dataSync` foreground service time limit is reached. Preserves the SQLite
     * checkpoint and transitions to [SpotifyImportState.Interrupted].
     */
    fun onServiceTimeout(context: Context) {
        savePartialRequested.set(false)
        serviceTimedOut.set(true)
        activeJob?.cancel(CancellationException("SERVICE_TIMEOUT"))
    }

    private suspend fun runImportJob(
        context: Context,
        jobId: String,
        rawUrl: String,
        target: SpotifyImportTarget,
        resumedCheckpoint: VibeDatabase.SpotifyImportCheckpointDao.StoredSpotifyCheckpoint?,
    ) {
        val db = VibeDatabase.getInstance(context)
        val dao = db.spotifyImportCheckpointDao
        var title = resumedCheckpoint?.playlistTitle.orEmpty()
        var tracks = resumedCheckpoint?.tracks.orEmpty()
        val startIndex = resumedCheckpoint?.nextIndex ?: 0
        val matchedByIndex = ConcurrentHashMap<Int, Song>()
        resumedCheckpoint?.matchedSongs?.forEachIndexed { idx, song ->
            matchedByIndex[idx] = song
        }

        try {
            if (resumedCheckpoint == null || tracks.isEmpty()) {
                val fetched = trackFetcher(target)
                title = fetched.first
                tracks = fetched.second
                dao.clearAllCheckpoints()
                dao.saveCheckpoint(
                    jobId = jobId,
                    playlistUrl = rawUrl,
                    playlistTitle = title,
                    tracks = tracks,
                    nextIndex = 0,
                    totalCount = tracks.size,
                )
            }

            val total = tracks.size
            _state.value = SpotifyImportState.Matching(
                jobId = jobId,
                url = rawUrl,
                title = title,
                completed = startIndex.coerceIn(0, total),
                total = total,
                matchedCount = matchedByIndex.size,
            )

            val customMatcher = singleTrackMatcher
            if (customMatcher != null) {
                for (index in startIndex until total) {
                    val track = tracks[index]
                    val song = customMatcher(track)
                    if (song != null) {
                        matchedByIndex[index] = song
                    }
                    val done = index + 1
                    checkpointMutex.withLock {
                        dao.recordTrackResolved(jobId, index, song, done)
                    }
                    _state.value = SpotifyImportState.Matching(
                        jobId = jobId,
                        url = rawUrl,
                        title = title,
                        completed = done,
                        total = total,
                        matchedCount = matchedByIndex.size,
                    )
                }
            } else {
                SpotifyImporter.resolveToSongs(
                    tracks = tracks,
                    startIndex = startIndex,
                    onProgress = { done, totalCount ->
                        _state.value = SpotifyImportState.Matching(
                            jobId = jobId,
                            url = rawUrl,
                            title = title,
                            completed = done,
                            total = totalCount,
                            matchedCount = matchedByIndex.size,
                        )
                    },
                    onTrackResolved = { index, song ->
                        if (song != null) {
                            matchedByIndex[index] = song
                        }
                        checkpointMutex.withLock {
                            dao.recordTrackResolved(jobId, index, song, index + 1)
                        }
                    },
                )
            }

            val latestCheckpoint = dao.loadActiveCheckpoint()
            saveMatchedPlaylistIdempotently(
                context = context,
                db = db,
                jobId = jobId,
                url = rawUrl,
                title = title,
                tracks = tracks,
                matchedByIndex = matchedByIndex,
                completedCount = total,
                totalCount = total,
                isPartial = false,
                existingPlaylistId = latestCheckpoint?.createdPlaylistId,
                existingSavedCount = latestCheckpoint?.savedSongCount ?: 0,
            )
        } catch (e: CancellationException) {
            when {
                savePartialRequested.getAndSet(false) -> {
                    val latestCheckpoint = dao.loadActiveCheckpoint()
                    val completedNow = latestCheckpoint?.nextIndex ?: matchedByIndex.size
                    saveMatchedPlaylistIdempotently(
                        context = context,
                        db = db,
                        jobId = jobId,
                        url = rawUrl,
                        title = title.ifBlank { latestCheckpoint?.playlistTitle ?: "Spotify Import" },
                        tracks = tracks.ifEmpty { latestCheckpoint?.tracks.orEmpty() },
                        matchedByIndex = matchedByIndex,
                        completedCount = completedNow,
                        totalCount = tracks.size.takeIf { it > 0 } ?: (latestCheckpoint?.totalCount ?: 0),
                        isPartial = true,
                        existingPlaylistId = latestCheckpoint?.createdPlaylistId,
                        existingSavedCount = latestCheckpoint?.savedSongCount ?: 0,
                    )
                }

                serviceTimedOut.getAndSet(false) -> {
                    val latestCheckpoint = dao.loadActiveCheckpoint()
                    val orderedSongs = matchedByIndex.entries.sortedBy { it.key }.map { it.value }
                        .ifEmpty { latestCheckpoint?.matchedSongs.orEmpty() }
                    _state.value = SpotifyImportState.Interrupted(
                        jobId = jobId,
                        url = rawUrl,
                        title = title.ifBlank { latestCheckpoint?.playlistTitle ?: "Spotify Import" },
                        matchedSongs = orderedSongs,
                        completed = latestCheckpoint?.nextIndex ?: orderedSongs.size,
                        total = tracks.size.takeIf { it > 0 } ?: (latestCheckpoint?.totalCount ?: orderedSongs.size),
                        createdPlaylistId = latestCheckpoint?.createdPlaylistId,
                        savedSongCount = latestCheckpoint?.savedSongCount ?: 0,
                        lastError = "Import paused due to system background time limit",
                    )
                }

                else -> {
                    // Explicit user Cancel already cleared checkpoint and set state to Idle
                    throw e
                }
            }
        } catch (e: Exception) {
            savePartialRequested.set(false)
            val latestCheckpoint = dao.loadActiveCheckpoint()
            val orderedSongs = matchedByIndex.entries.sortedBy { it.key }.map { it.value }
                .ifEmpty { latestCheckpoint?.matchedSongs.orEmpty() }
            if (orderedSongs.isNotEmpty() && latestCheckpoint != null) {
                _state.value = SpotifyImportState.Interrupted(
                    jobId = latestCheckpoint.jobId,
                    url = rawUrl,
                    title = latestCheckpoint.playlistTitle,
                    matchedSongs = orderedSongs,
                    completed = latestCheckpoint.nextIndex,
                    total = latestCheckpoint.totalCount,
                    createdPlaylistId = latestCheckpoint.createdPlaylistId,
                    savedSongCount = latestCheckpoint.savedSongCount,
                    lastError = e.message ?: "Import interrupted",
                )
            } else {
                dao.clearCheckpoint(jobId)
                _state.value = SpotifyImportState.Failed(rawUrl, e.message ?: "Spotify import failed")
            }
            runCatching { serviceStopper(context) }
        }
    }

    private suspend fun saveMatchedPlaylistIdempotently(
        context: Context,
        db: VibeDatabase,
        jobId: String,
        url: String,
        title: String,
        tracks: List<SpotifyImportTrack>,
        matchedByIndex: Map<Int, Song>,
        completedCount: Int,
        totalCount: Int,
        isPartial: Boolean,
        existingPlaylistId: String?,
        existingSavedCount: Int,
    ) {
        val dao = db.spotifyImportCheckpointDao
        val orderedMatched = if (matchedByIndex.isNotEmpty()) {
            matchedByIndex.entries.sortedBy { it.key }.map { it.value }
        } else {
            dao.loadActiveCheckpoint()?.matchedSongs.orEmpty()
        }

        if (orderedMatched.isEmpty()) {
            savePartialRequested.set(false)
            dao.clearCheckpoint(jobId)
            _state.value = SpotifyImportState.Failed(url, "No matching songs found on YouTube Music")
            runCatching { serviceStopper(context) }
            return
        }

        _state.value = SpotifyImportState.Saving(
            jobId = jobId,
            title = title,
            matchedCount = orderedMatched.size,
            totalCount = totalCount,
            isPartial = isPartial,
        )

        var playlistId: String? = existingPlaylistId
        var savedCount = existingSavedCount.coerceIn(0, orderedMatched.size)
        var browseId: String? = null
        var savedLocally = false

        try {
            if (currentSignedIn) {
                val targetPlaylistId = playlistId ?: remotePlaylistCreator(title, currentPrivacy).also { newId ->
                    playlistId = newId
                    dao.updatePlaylistSaveProgress(jobId, newId, savedCount)
                }
                val remaining = orderedMatched.drop(savedCount)
                for (chunk in remaining.chunked(50)) {
                    remotePlaylistTrackAdder(targetPlaylistId, chunk.map { it.videoId })
                    savedCount += chunk.size
                    dao.updatePlaylistSaveProgress(jobId, targetPlaylistId, savedCount)
                }
                browseId = "VL${targetPlaylistId.removePrefix("VL")}"
                savedLocally = false
            } else {
                val local = LocalPlaylistStore.savePlaylist(title, orderedMatched)
                browseId = local.browseId
                savedLocally = true
                savedCount = orderedMatched.size
            }

            // Success! Clear checkpoint from both tables and reset flags.
            savePartialRequested.set(false)
            dao.clearCheckpoint(jobId)

            val unmatched = tracks.mapIndexedNotNull { index, track ->
                if (index < completedCount && index !in matchedByIndex) track else null
            }

            val completedState = SpotifyImportState.Completed(
                title = title,
                matchedCount = orderedMatched.size,
                totalCount = totalCount,
                isPartial = isPartial,
                unmatchedTracks = unmatched,
                browseId = browseId,
                savedLocally = savedLocally,
            )
            _state.value = completedState
            _events.tryEmit(
                SpotifyPlaylistCreatedEvent(
                    browseId = browseId,
                    title = title,
                    matchedCount = orderedMatched.size,
                    totalCount = totalCount,
                    isPartial = isPartial,
                    savedLocally = savedLocally,
                    songs = orderedMatched,
                ),
            )
        } catch (e: Exception) {
            // Failure path: keep checkpoint, created_playlist_id, and saved_song_count intact so user can retry!
            savePartialRequested.set(false)
            _state.value = SpotifyImportState.Interrupted(
                jobId = jobId,
                url = url,
                title = title,
                matchedSongs = orderedMatched,
                completed = completedCount,
                total = totalCount,
                createdPlaylistId = playlistId,
                savedSongCount = savedCount,
                lastError = e.message ?: "Could not save playlist",
            )
        } finally {
            runCatching { serviceStopper(context) }
        }
    }

    internal fun resetForTesting(testScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)) {
        activeJob?.cancel()
        activeJob = null
        scope = testScope
        savePartialRequested.set(false)
        serviceTimedOut.set(false)
        _state.value = SpotifyImportState.Idle
        singleTrackMatcher = null
    }
}
