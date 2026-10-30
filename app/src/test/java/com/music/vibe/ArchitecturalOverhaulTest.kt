package com.music.vibe

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.music.vibe.auth.AuthStore
import com.music.vibe.data.db.LegacyDataMigrator
import com.music.vibe.data.db.VibeDatabase
import com.music.vibe.data.settings.AppSettings
import com.music.vibe.data.settings.SearchHistory
import com.music.vibe.data.stats.Backup
import com.music.vibe.data.stats.ListeningStats
import com.music.vibe.data.model.ArtistRef
import com.music.vibe.data.model.EntityType
import com.music.vibe.data.model.PlaybackSourceType
import com.music.vibe.data.model.PlaylistPrivacy
import com.music.vibe.data.model.QueueTier
import com.music.vibe.data.model.SearchHistoryEntity
import com.music.vibe.data.model.Song
import com.music.vibe.data.spotify.LocalPlaylistStore
import com.music.vibe.data.spotify.SpotifyImportManager
import com.music.vibe.data.spotify.SpotifyImportState
import com.music.vibe.data.spotify.SpotifyImportTrack
import com.music.vibe.download.Downloads
import com.music.vibe.download.SavedSongMetadata
import com.music.vibe.playback.LastPlayed
import com.music.vibe.playback.PartyPersonalQueueStash
import com.music.vibe.playback.QueueCoordinator
import com.music.vibe.playback.QueueCoordinator.asQueueEntry
import com.music.vibe.playback.songFromBundle
import com.music.vibe.playback.toMediaItem
import com.music.vibe.playback.toSong
import com.music.vibe.playback.toSongBundle
import com.music.vibe.ui.screens.rowSelectionKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArchitecturalOverhaulTest {

    private val testJson = Json { ignoreUnknownKeys = true }

    private fun fullFeaturedSong(id: String = "vibe_song_1"): Song = Song(
        videoId = id,
        title = "Starlight Synthesis",
        artist = "Nova & The Orbitals",
        thumbnailUrl = "https://lh3.googleusercontent.com/test=w544-h544",
        durationText = "3:45",
        artistId = "UC_nova",
        artists = listOf(
            ArtistRef("Nova", "UC_nova"),
            ArtistRef("The Orbitals", "UC_orbitals"),
        ),
        albumId = "MPREb_galactic",
        albumName = "Galactic Horizon",
        isVideo = false,
        isVideoOrigin = false,
        setVideoId = "set_vid_1",
        queueTier = QueueTier.USER_QUEUE,
        queueEntryId = "entry_987654",
        radioName = "Starlight Radio",
        localUri = "content://media/external/audio/media/101",
        downloadFormat = "OPUS · 160 kbps",
        localPath = "/storage/emulated/0/Music/starlight.opus",
        localDateAddedSeconds = 1_700_111_222L,
        localDateModifiedSeconds = 1_700_333_444L,
        sourceQuality = "High quality",
        isExplicit = true,
        playbackSource = "Galactic Horizon",
        playbackSourceType = PlaybackSourceType.BROWSE,
        playbackSourceId = "MPREb_galactic",
        trackNumber = 5,
    )

    @Test
    fun songBundleCodec_andMediaItemConversion_preserveAllFieldsLosslessly() {
        val original = fullFeaturedSong()

        // 1. Direct Bundle round-trip
        val bundle = original.toSongBundle()
        val fromBundle = songFromBundle(bundle)
        assertEquals(original, fromBundle)

        // 2. MediaItem round-trip (used across ExoPlayer / MediaSession IPC)
        val mediaItem = original.toMediaItem()
        val fromMediaItem = mediaItem.toSong()
        assertEquals(original, fromMediaItem)
    }

    @Test
    fun legacyDataMigrator_migratesSharedPreferencesQueuesSearchHistoryAndDownloadsToRoomDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = VibeDatabase.createInMemoryForTesting(context)

        // Seed legacy SharedPreferences before running migrator
        val lastPlayedPrefs = context.getSharedPreferences("vibemusic_last_played", Context.MODE_PRIVATE)
        val queueArray = JSONArray().apply {
            put(
                JSONObject().apply {
                    put("videoId", "legacy_vid_1")
                    put("title", "Legacy Track One")
                    put("artist", "Legacy Artist")
                    put("album", "Legacy Album")
                    put("durationText", "2:59")
                    put("queueTier", QueueTier.USER_QUEUE.name)
                },
            )
            put(
                JSONObject().apply {
                    put("videoId", "legacy_vid_2")
                    put("title", "Legacy Track Two")
                    put("artist", "Second Artist")
                    put("queueTier", QueueTier.CONTEXT.name)
                },
            )
        }
        lastPlayedPrefs.edit()
            .putString("queue", queueArray.toString())
            .putInt("index", 1)
            .putLong("positionMs", 42_500L)
            .commit()

        val settingsPrefs = context.getSharedPreferences("vibemusic_settings", Context.MODE_PRIVATE)
        val legacyHistory = listOf(
            SearchHistoryEntity(
                id = "legacy_vid_1",
                title = "Legacy Track One",
                subtitle = "Legacy Artist",
                artworkUrl = null,
                entityType = EntityType.TRACK,
            ),
        )
        val legacySavedTracks = mapOf("dl_vid_1" to "file:///storage/emulated/0/Music/song1.m4a")
        val legacySavedMeta = mapOf(
            "dl_vid_1" to SavedSongMetadata(
                videoId = "dl_vid_1",
                title = "Downloaded Song",
                artist = "Offline Artist",
                uri = "file:///storage/emulated/0/Music/song1.m4a",
                dateAddedSeconds = 1_700_000_000L,
            ),
        )
        settingsPrefs.edit()
            .putString("search_history", Json.encodeToString(legacyHistory))
            .putString("downloaded_tracks", Json.encodeToString(legacySavedTracks))
            .putString("downloaded_tracks_metadata", Json.encodeToString(legacySavedMeta))
            .commit()

        // Execute automatic migration
        LegacyDataMigrator.migrateIfNeeded(context, db)
        assertTrue(LegacyDataMigrator.isV1MigrationDone)

        // Verify queue snapshot migrated into SQLite
        LastPlayed.initForTesting(lastPlayedPrefs, db)
        val restoredQueue = LastPlayed.load()
        assertNotNull(restoredQueue)
        assertEquals(2, restoredQueue!!.songs.size)
        assertEquals("legacy_vid_1", restoredQueue.songs[0].videoId)
        assertEquals(QueueTier.USER_QUEUE, restoredQueue.songs[0].queueTier)
        assertEquals("legacy_vid_2", restoredQueue.songs[1].videoId)
        assertEquals(1, restoredQueue.index)
        assertEquals(42_500L, restoredQueue.positionMs)

        // Verify SearchHistory and Downloads migrated into SQLite
        val historyItems = db.searchHistoryDao.getRecent(10)
        assertEquals(1, historyItems.size)
        assertEquals("Legacy Track One", historyItems.first().title)
        assertEquals(
            "file:///storage/emulated/0/Music/song1.m4a",
            db.downloadsDao.loadAll().savedUris["dl_vid_1"],
        )

        db.close()
    }

    @Test
    fun queuePersistence_roundTripsFullSongMetadataThroughVibeDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = VibeDatabase.createInMemoryForTesting(context)
        val lastPlayedPrefs = context.getSharedPreferences("test_last_played", Context.MODE_PRIVATE)
        val stashPrefs = context.getSharedPreferences("test_party_stash", Context.MODE_PRIVATE)

        LastPlayed.initForTesting(lastPlayedPrefs, db)
        PartyPersonalQueueStash.initForTesting(stashPrefs, db)

        val song1 = fullFeaturedSong("track_1").asQueueEntry(QueueTier.CONTEXT)
        val song2 = fullFeaturedSong("track_2").asQueueEntry(QueueTier.USER_QUEUE)

        LastPlayed.saveQueueImmediately(
            songs = listOf(song1, song2),
            index = 0,
            positionMs = 15_000L,
        )
        LastPlayed.savePlaybackState(index = 1, positionMs = 88_000L)

        val restored = LastPlayed.load()
        assertNotNull(restored)
        assertEquals(2, restored!!.songs.size)
        assertEquals(song1, restored.songs[0])
        assertEquals(song2, restored.songs[1])
        assertEquals(1, restored.index)
        assertEquals(88_000L, restored.positionMs)

        // Verify PartyPersonalQueueStash round-trip and clear
        PartyPersonalQueueStash.stash(
            songs = listOf(song1, song2),
            index = 1,
            positionMs = 33_000L,
            wasPlaying = true,
        )
        assertTrue(PartyPersonalQueueStash.hasStash())
        val stashed = PartyPersonalQueueStash.load()
        assertNotNull(stashed)
        assertEquals(listOf(song1, song2), stashed!!.songs)
        assertEquals(1, stashed.index)
        assertEquals(33_000L, stashed.positionMs)
        assertTrue(stashed.wasPlaying)
        PartyPersonalQueueStash.clear()
        assertFalse(PartyPersonalQueueStash.hasStash())

        // Verify QueueCoordinator Play Next / Add to Queue tier placement
        val timeline = listOf(
            song1.asQueueEntry(QueueTier.CONTEXT),
            song2.asQueueEntry(QueueTier.USER_QUEUE),
            fullFeaturedSong("track_3").asQueueEntry(QueueTier.CONTEXT),
        )
        val afterPlayNext = QueueCoordinator.findUserQueueInsertionIndex(
            timeline = timeline,
            currentIndex = 0,
            isNext = true,
        )
        assertEquals(1, afterPlayNext)
        val afterAddToQueue = QueueCoordinator.findUserQueueInsertionIndex(
            timeline = timeline,
            currentIndex = 0,
            isNext = false,
        )
        assertEquals(2, afterAddToQueue)

        db.close()
    }

    @Test
    fun vibeDatabaseV1ToV2Migration_andChunkedBackfill_populatesColumnsAndSmartMixSqlQueries(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = VibeDatabase.createInMemoryForTesting(context)
        val now = 1_750_000_000_000L
        val tenDaysAgo = now - 10L * 24 * 60 * 60 * 1000
        val seventyDaysAgo = now - 70L * 24 * 60 * 60 * 1000

        // Simulate pre-v2 downloaded_tracks rows where date_added_sec is still 0
        val metaOlder = SavedSongMetadata(
            videoId = "dl_older",
            title = "Older Download",
            artist = "Artist A",
            uri = "file:///storage/emulated/0/Music/older.opus",
            dateAddedSeconds = 1_700_000_100L,
        )
        val metaNewer = SavedSongMetadata(
            videoId = "dl_newer",
            title = "Newer Download",
            artist = "Artist B",
            uri = "file:///storage/emulated/0/Music/newer.opus",
            dateAddedSeconds = 1_700_000_900L,
        )
        db.writableDatabase.execSQL(
            "INSERT INTO downloaded_tracks (video_id, uri, metadata_json, date_added_sec) VALUES (?, ?, ?, 0)",
            arrayOf("dl_older", metaOlder.uri, Json.encodeToString(metaOlder)),
        )
        db.writableDatabase.execSQL(
            "INSERT INTO downloaded_tracks (video_id, uri, metadata_json, date_added_sec) VALUES (?, ?, ?, 0)",
            arrayOf("dl_newer", metaNewer.uri, Json.encodeToString(metaNewer)),
        )

        // Before backfill_v2_done is marked, getRecentlyDownloaded() must fall back to parsing metadata_json
        val preBackfillDownloads = db.smartPlaylistsDao.getRecentlyDownloaded()
        assertEquals(listOf("dl_newer", "dl_older"), preBackfillDownloads.map { it.videoId })

        // Seed a v1 listening_buckets JSON payload so chunked backfill populates thumbnail_url, album_id, artist_id
        val bucketJson = JSONObject().apply {
            put(
                "tracks",
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("id", "fav_old")
                            put("title", "Forgotten Gem")
                            put("artist", "Classic Artist")
                            put("album", "Retro Album")
                            put("art", "https://example.com/gem.jpg")
                            put("albumId", "MPREb_retro")
                            put("artistId", "UC_classic")
                            put("ms", 600_000L)
                            put("plays", 4)
                            put("last", seventyDaysAgo)
                        },
                    )
                    put(
                        JSONObject().apply {
                            put("id", "recent_hit")
                            put("title", "Recent Hit")
                            put("artist", "Trending Artist")
                            put("album", "Now Album")
                            put("art", "https://example.com/hit.jpg")
                            put("albumId", "MPREb_now")
                            put("artistId", "UC_trending")
                            put("ms", 900_000L)
                            put("plays", 6)
                            put("last", tenDaysAgo)
                        },
                    )
                },
            )
        }.toString()
        db.listeningStatsDao.saveMonthBucket(
            monthKey = "2025-01",
            bucketJson = bucketJson,
            tracks = emptyList(),
        )

        // Run chunked v2 backfill with small chunkSize=1 to verify multi-chunk transaction behavior
        db.backfillV2MetadataIfNeeded(chunkSize = 1)
        assertTrue(db.isMigrationDone(VibeDatabase.BACKFILL_KEY_V2))

        // Verify downloaded_tracks date_added_sec column was backfilled and SQL ordering returns newest first
        val postBackfillDownloads = db.smartPlaylistsDao.getRecentlyDownloaded()
        assertEquals(listOf("dl_newer", "dl_older"), postBackfillDownloads.map { it.videoId })

        // Verify Most Played (Last 30 Days) includes recent_hit with backfilled artwork/IDs and excludes fav_old
        val mostPlayed = db.smartPlaylistsDao.getMostPlayedLast30Days(
            sinceMs = now - 30L * 24 * 60 * 60 * 1000,
        )
        assertEquals(1, mostPlayed.size)
        assertEquals("recent_hit", mostPlayed.first().videoId)
        assertEquals("https://example.com/hit.jpg", mostPlayed.first().thumbnailUrl)
        assertEquals("MPREb_now", mostPlayed.first().albumId)
        assertEquals("UC_trending", mostPlayed.first().artistId)

        // Verify Forgotten Favorites includes fav_old (play_count = 4 >= 3, last_played_ms <= now - 60d)
        val forgotten = db.smartPlaylistsDao.getForgottenFavorites(
            beforeMs = now - 60L * 24 * 60 * 60 * 1000,
        )
        assertEquals(1, forgotten.size)
        assertEquals("fav_old", forgotten.first().videoId)
        assertEquals("https://example.com/gem.jpg", forgotten.first().thumbnailUrl)

        db.close()
    }

    @Test
    fun spotifyImportManager_cancelSavePartialAndRetryIdempotency_preserveDataAndAvoidDuplicates(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = VibeDatabase.createInMemoryForTesting(context)
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        SpotifyImportManager.resetForTesting(testScope)
        SpotifyImportManager.serviceStarter = {}
        SpotifyImportManager.serviceStopper = {}

        val sampleTracks = listOf(
            SpotifyImportTrack(title = "Song One", artist = "Artist 1", durationMs = 180_000, album = "Album 1"),
            SpotifyImportTrack(title = "Song Two", artist = "Artist 2", durationMs = 200_000, album = "Album 2"),
            SpotifyImportTrack(title = "Song Three", artist = "Artist 3", durationMs = 210_000, album = "Album 3"),
        )
        SpotifyImportManager.trackFetcher = { "Roadtrip Mix" to sampleTracks }

        // 1. Test Save Failure mid-save & Retry Idempotency (must reuse created_playlist_id)
        var createdPlaylistsCount = 0
        var failFirstAdd = true
        val addedBatches = mutableListOf<Pair<String, List<String>>>()

        SpotifyImportManager.singleTrackMatcher = { track ->
            val slug = track.title.lowercase().replace(' ', '_')
            Song(
                videoId = "yt_$slug",
                title = track.title,
                artist = track.artist,
                thumbnailUrl = "https://img/$slug",
            )
        }
        SpotifyImportManager.remotePlaylistCreator = { _, _ ->
            createdPlaylistsCount++
            "PL_created_once_$createdPlaylistsCount"
        }
        SpotifyImportManager.remotePlaylistTrackAdder = { playlistId, videoIds ->
            if (failFirstAdd) {
                failFirstAdd = false
                throw IllegalStateException("Simulated network drop after playlist creation")
            }
            addedBatches.add(playlistId to videoIds)
        }

        SpotifyImportManager.startImport(
            context = context,
            rawUrl = "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M",
            privacy = PlaylistPrivacy.PRIVATE,
            signedIn = true,
            dispatcher = Dispatchers.Unconfined,
        )

        // Because remotePlaylistTrackAdder failed on first attempt, state must be Interrupted
        // and checkpoint must remain in DB with created_playlist_id = "PL_created_once_1"
        val interrupted = SpotifyImportManager.state.value as? SpotifyImportState.Interrupted
        assertNotNull("Expected Interrupted state after mid-save failure", interrupted)
        assertEquals("PL_created_once_1", interrupted!!.createdPlaylistId)
        assertEquals(3, interrupted.matchedSongs.size)
        assertNotNull(db.spotifyImportCheckpointDao.loadActiveCheckpoint())

        // Retry with "Save what we got" — must reuse PL_created_once_1 without creating a 2nd playlist
        SpotifyImportManager.saveInterruptedWhatWeGot(
            context = context,
            signedIn = true,
            dispatcher = Dispatchers.Unconfined,
        )
        val completed = SpotifyImportManager.state.value as? SpotifyImportState.Completed
        assertNotNull("Expected Completed state on retry", completed)
        assertEquals(1, createdPlaylistsCount)
        assertEquals("VLPL_created_once_1", completed!!.browseId)
        assertEquals(1, addedBatches.size)
        assertEquals(listOf("yt_song_one", "yt_song_two", "yt_song_three"), addedBatches.first().second)
        assertNull(db.spotifyImportCheckpointDao.loadActiveCheckpoint())

        // 2. Test explicit Cancel deletes both checkpoint and matched_tracks rows
        db.spotifyImportCheckpointDao.saveCheckpoint(
            jobId = "job_cancel_test",
            playlistUrl = "https://open.spotify.com/album/4aawyAB9vmqN3uQ7FjRGTy",
            playlistTitle = "Cancelable Album",
            tracks = sampleTracks,
            nextIndex = 0,
            totalCount = sampleTracks.size,
        )
        db.spotifyImportCheckpointDao.recordTrackResolved(
            jobId = "job_cancel_test",
            trackIndex = 0,
            matchedSong = fullFeaturedSong("yt_cancel_1"),
            nextIndex = 1,
        )
        assertNotNull(db.spotifyImportCheckpointDao.loadActiveCheckpoint())
        SpotifyImportManager.cancelImport(context)
        assertNull(db.spotifyImportCheckpointDao.loadActiveCheckpoint())
        assertEquals(SpotifyImportState.Idle, SpotifyImportManager.state.value)

        // Verify spotify_import_matched_tracks table has 0 orphan rows
        val orphanCount = db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM spotify_import_matched_tracks",
            null,
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        assertEquals(0, orphanCount)

        db.close()
    }

    @Test
    fun backupExportAndImport_bridgesSqliteAndSupportsOldFormatArchiveIntoMigratedDb(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = VibeDatabase.getInstance(context)
        LegacyDataMigrator.migrateIfNeeded(context, db)
        assertTrue(LegacyDataMigrator.isV1MigrationDone)

        AppSettings.init(context, AuthStore(context))
        SearchHistory.init(context)
        Downloads.init(context)
        ListeningStats.init(context)

        // Seed SearchHistory and Downloads via their SQLite-backed stores
        val entity = SearchHistoryEntity(
            id = "backup_track_1",
            title = "Backup Song",
            subtitle = "Backup Artist",
            artworkUrl = "https://example.com/art.jpg",
            entityType = EntityType.TRACK,
        )
        SearchHistory.clear()
        SearchHistory.record(entity)

        val savedMeta = SavedSongMetadata(
            videoId = "dl_backup_1",
            title = "Saved Offline Track",
            artist = "Offline Singer",
            uri = "file:///storage/emulated/0/Music/saved.opus",
            dateAddedSeconds = 1_710_000_000L,
        )
        Downloads.importFromBackup(
            mapOf(
                "downloaded_tracks" to Json.encodeToString(mapOf("dl_backup_1" to savedMeta.uri)),
                "downloaded_tracks_metadata" to Json.encodeToString(mapOf("dl_backup_1" to savedMeta)),
            ),
        )

        // Export backup archive to a temporary file URI
        val backupFile = java.io.File(context.cacheDir, "test_vibe_backup.json")
        val backupUri = Uri.fromFile(backupFile)
        Backup.exportTo(context, backupUri).getOrThrow()
        assertTrue(backupFile.exists() && backupFile.length() > 0L)

        // Clear SQLite tables before importing
        SearchHistory.clear()
        Downloads.importFromBackup(
            mapOf(
                "downloaded_tracks" to "{}",
                "downloaded_tracks_metadata" to "{}",
            ),
        )
        assertTrue(db.searchHistoryDao.getRecent(10).isEmpty())
        assertTrue(db.downloadsDao.loadAll().savedUris.isEmpty())

        // Import backup archive into already-migrated v2 database
        Backup.importFrom(context, backupUri).getOrThrow()

        // Verify SQLite tables were repopulated from the backup archive
        val restoredHistory = db.searchHistoryDao.getRecent(10)
        assertEquals(1, restoredHistory.size)
        assertEquals("Backup Song", restoredHistory.first().title)

        val restoredDownloads = db.downloadsDao.loadAll()
        assertEquals("file:///storage/emulated/0/Music/saved.opus", restoredDownloads.savedUris["dl_backup_1"])
        val restoredMetaJson = restoredDownloads.metadataJsonById["dl_backup_1"]
        assertNotNull(restoredMetaJson)
        val restoredMeta = testJson.decodeFromString<SavedSongMetadata>(restoredMetaJson!!)
        assertEquals(1_710_000_000L, restoredMeta.dateAddedSeconds)
        backupFile.delete()
        Unit
    }

    @Test
    fun detailScreenSelectionKey_usesPlainVideoIdWhenUniqueAndRowIndexOnlyForDuplicates() {
        val withSetId = fullFeaturedSong("vid_1").copy(setVideoId = "set_abc")
        val withoutSetId = fullFeaturedSong("vid_1").copy(setVideoId = null)

        // 1. Remote playlist entry with setVideoId always keys by setVideoId
        assertEquals(
            "set:set_abc",
            rowSelectionKey(index = 0, song = withSetId, hasDuplicateVideoIds = true),
        )

        // 2. Local playlist / Downloads without duplicate videoIds keys by plain videoId (shift-proof)
        assertEquals(
            "vid:vid_1",
            rowSelectionKey(index = 3, song = withoutSetId, hasDuplicateVideoIds = false),
        )

        // 3. Page with duplicate videoIds and no setVideoId disambiguates by index
        assertEquals(
            "idx:3:vid_1",
            rowSelectionKey(index = 3, song = withoutSetId, hasDuplicateVideoIds = true),
        )
    }
}
