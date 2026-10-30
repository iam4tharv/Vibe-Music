package com.music.vibe.data.db

import android.content.Context
import android.content.SharedPreferences
import com.music.vibe.data.model.PlaybackSourceType
import com.music.vibe.data.model.QueueTier
import com.music.vibe.data.model.SearchHistoryEntity
import com.music.vibe.data.model.Song
import com.music.vibe.data.runCatchingLogged
import com.music.vibe.data.stats.StoredBucket
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.time.YearMonth

/**
 * Automatic, idempotent one-time migrator from legacy `SharedPreferences` JSON
 * blobs and `filesDir/listening/` bucket files into [VibeDatabase].
 */
object LegacyDataMigrator {

    private const val TAG = "LegacyDataMigrator"
    const val MIGRATION_KEY_V1 = "legacy_prefs_to_sqlite_v1"

    /**
     * In-memory cached migration gate so `Downloads`, `SearchHistory`, and
     * `ListeningStats` can skip legacy `SharedPreferences` / `.json` dual-writes
     * without hitting SQLite on every write.
     */
    @Volatile
    var isV1MigrationDone: Boolean = false
        internal set

    private val json = Json { ignoreUnknownKeys = true }

    fun migrateIfNeeded(context: Context, db: VibeDatabase) {
        if (db.isMigrationDone(MIGRATION_KEY_V1)) {
            isV1MigrationDone = true
            return
        }

        var allSucceeded = true

        runCatchingLogged(TAG, "migrateLastPlayed") {
            val prefs = context.getSharedPreferences("vibemusic_last_played", Context.MODE_PRIVATE)
            migrateLegacyQueuePrefs(
                prefs = prefs,
                prefKey = "queue",
                snapshotKey = SNAPSHOT_LAST_PLAYED,
                db = db,
            )
        }.onFailure { allSucceeded = false }

        runCatchingLogged(TAG, "migratePartyPersonalQueueStash") {
            val prefs = context.getSharedPreferences("vibemusic_party_queue_stash", Context.MODE_PRIVATE)
            migrateLegacyQueuePrefs(
                prefs = prefs,
                prefKey = "stashed_queue",
                snapshotKey = SNAPSHOT_PARTY_PERSONAL_STASH,
                db = db,
            )
        }.onFailure { allSucceeded = false }

        val settingsPrefs = context.getSharedPreferences("vibemusic_settings", Context.MODE_PRIVATE)
        runCatchingLogged(TAG, "migrateSearchHistory") {
            migrateSearchHistoryPrefs(settingsPrefs, db)
            val rawHistory = settingsPrefs.getString("search_history", null)
            if (!rawHistory.isNullOrBlank() && rawHistory.trim() != "[]") {
                val decoded = runCatching {
                    json.decodeFromString(ListSerializer(SearchHistoryEntity.serializer()), rawHistory)
                }.getOrDefault(emptyList())
                if (decoded.isNotEmpty() && db.searchHistoryDao.getRecent(1).isEmpty()) {
                    allSucceeded = false
                }
            }
        }.onFailure { allSucceeded = false }

        runCatchingLogged(TAG, "migrateDownloads") {
            migrateDownloadsPrefs(settingsPrefs, db)
            val rawSaved = settingsPrefs.getString("downloaded_tracks", null)
            if (!rawSaved.isNullOrBlank() && rawSaved.trim() != "{}") {
                val decoded = runCatching {
                    json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), rawSaved)
                }.getOrDefault(emptyMap())
                if (decoded.isNotEmpty() && db.downloadsDao.loadAll().savedUris.isEmpty()) {
                    allSucceeded = false
                }
            }
        }.onFailure { allSucceeded = false }

        runCatchingLogged(TAG, "migrateListeningBuckets") {
            val dir = File(context.filesDir, "listening")
            migrateListeningBucketsDir(dir, db)
            val bucketFiles = dir.listFiles()?.filter {
                it.name.endsWith(".json") && runCatching { YearMonth.parse(it.name.removeSuffix(".json")) }.isSuccess
            }.orEmpty()
            if (bucketFiles.isNotEmpty() && db.listeningStatsDao.listMonthKeys().isEmpty()) {
                allSucceeded = false
            }
        }.onFailure { allSucceeded = false }

        if (allSucceeded) {
            db.markMigrationDone(MIGRATION_KEY_V1)
            isV1MigrationDone = true
        }
    }

    fun migrateLegacyQueuePrefs(
        prefs: SharedPreferences,
        prefKey: String,
        snapshotKey: String,
        db: VibeDatabase,
    ) {
        if (db.queueSnapshotDao.hasSnapshot(snapshotKey)) return
        val raw = prefs.getString(prefKey, null)?.trim() ?: return
        if (raw.isEmpty()) return

        if (raw.startsWith("[")) {
            val tracks = runCatchingLogged(TAG, "decodeLegacyQueueArray($snapshotKey)") {
                json.decodeFromString(ListSerializer(LegacyStoredTrack.serializer()), raw)
            }.getOrNull() ?: return
            if (tracks.isEmpty()) return
            val songs = tracks.map { it.toSong() }
            val index = prefs.getInt("index", 0).coerceIn(songs.indices)
            val positionMs = prefs.getLong("positionMs", 0L).coerceAtLeast(0L)
            db.queueSnapshotDao.saveSnapshot(
                snapshotKey = snapshotKey,
                songs = songs,
                currentIndex = index,
                positionMs = positionMs,
                wasPlaying = false,
            )
            return
        }

        val stored = runCatchingLogged(TAG, "decodeLegacyQueue($snapshotKey)") {
            json.decodeFromString(LegacyStoredQueue.serializer(), raw)
        }.getOrNull() ?: return
        val allTracks = stored.tracks.ifEmpty { stored.songs }
        if (allTracks.isEmpty()) return
        val songs = allTracks.map { it.toSong() }
        val fallbackIndex = prefs.getInt("index", stored.index)
        val fallbackPosition = prefs.getLong("positionMs", stored.positionMs)
        db.queueSnapshotDao.saveSnapshot(
            snapshotKey = snapshotKey,
            songs = songs,
            currentIndex = fallbackIndex.coerceIn(songs.indices),
            positionMs = fallbackPosition.coerceAtLeast(0L),
            wasPlaying = stored.wasPlaying,
        )
    }

    fun migrateSearchHistoryPrefs(
        prefs: SharedPreferences,
        db: VibeDatabase,
    ) {
        val raw = prefs.getString("search_history", null) ?: return
        val list = runCatchingLogged(TAG, "decodeLegacySearchHistory") {
            json.decodeFromString(ListSerializer(SearchHistoryEntity.serializer()), raw)
        }.getOrNull() ?: return
        if (list.isNotEmpty() && db.searchHistoryDao.getRecent(1).isEmpty()) {
            db.searchHistoryDao.replaceAll(list)
        }
    }

    fun migrateDownloadsPrefs(
        prefs: SharedPreferences,
        db: VibeDatabase,
    ) {
        val existing = db.downloadsDao.loadAll()
        val rawSaved = prefs.getString("downloaded_tracks", null)
        val rawMeta = prefs.getString("downloaded_tracks_metadata", null)
        val rawCollections = prefs.getString("downloaded_collections", null)

        if (existing.savedUris.isEmpty() && existing.metadataJsonById.isEmpty() &&
            (!rawSaved.isNullOrBlank() || !rawMeta.isNullOrBlank())
        ) {
            val savedMap = if (!rawSaved.isNullOrBlank()) {
                runCatchingLogged(TAG, "decodeLegacyDownloadedTracks") {
                    json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), rawSaved)
                }.getOrDefault(emptyMap())
            } else {
                emptyMap()
            }

            val metaJsonMap = if (!rawMeta.isNullOrBlank()) {
                runCatchingLogged(TAG, "decodeLegacyDownloadedMetadata") {
                    val objMap = json.decodeFromString(
                        MapSerializer(String.serializer(), JsonObject.serializer()),
                        rawMeta,
                    )
                    objMap.mapValues { (_, v) -> v.toString() }
                }.getOrDefault(emptyMap())
            } else {
                emptyMap()
            }

            if (savedMap.isNotEmpty() || metaJsonMap.isNotEmpty()) {
                db.downloadsDao.saveTracksAndMetadata(savedMap, metaJsonMap)
            }
        }

        if (existing.collectionsJsonById.isEmpty() && !rawCollections.isNullOrBlank()) {
            val collectionsJsonMap = runCatchingLogged(TAG, "decodeLegacyDownloadedCollections") {
                val objMap = json.decodeFromString(
                    MapSerializer(String.serializer(), JsonObject.serializer()),
                    rawCollections,
                )
                objMap.mapValues { (_, v) -> v.toString() }
            }.getOrDefault(emptyMap())
            if (collectionsJsonMap.isNotEmpty()) {
                db.downloadsDao.saveCollections(collectionsJsonMap)
            }
        }
    }

    fun migrateListeningBucketsDir(
        directory: File,
        db: VibeDatabase,
    ) {
        if (!directory.exists() || !directory.isDirectory) return
        val existingMonths = db.listeningStatsDao.listMonthKeys().toSet()
        val files = directory.listFiles() ?: return
        for (file in files) {
            if (!file.name.endsWith(".json")) continue
            val key = file.name.removeSuffix(".json")
            val parsedMonth = runCatching { YearMonth.parse(key) }.getOrNull() ?: continue
            val monthKey = parsedMonth.toString()
            if (monthKey in existingMonths) continue
            runCatchingLogged(TAG, "migrateBucketFile($monthKey)") {
                val raw = file.readText()
                if (raw.isNotBlank()) {
                    val parsedBucket = runCatching {
                        json.decodeFromString(StoredBucket.serializer(), raw)
                    }.getOrNull()
                    val trackRows = parsedBucket?.tracks.orEmpty().map { t ->
                        VibeDatabase.TrackStatRow(
                            videoId = t.id,
                            title = t.title,
                            artist = t.artist,
                            album = t.album,
                            thumbnailUrl = t.art,
                            albumId = t.albumId,
                            artistId = t.artistId,
                            durationText = t.durationText,
                            playedMs = t.ms,
                            playCount = t.plays,
                            lastPlayedMs = t.last,
                        )
                    }
                    db.listeningStatsDao.saveMonthBucket(monthKey, raw, trackRows)
                }
            }
        }
    }

    @Serializable
    internal data class LegacyStoredQueue(
        val tracks: List<LegacyStoredTrack> = emptyList(),
        val songs: List<LegacyStoredTrack> = emptyList(),
        val index: Int = 0,
        val positionMs: Long = 0L,
        val wasPlaying: Boolean = false,
    )

    @Serializable
    internal data class LegacyStoredTrack(
        val id: String = "",
        val videoId: String = "",
        val title: String = "",
        val artist: String = "",
        val artwork: String? = null,
        val thumbnailUrl: String? = null,
        val auto: Boolean = false,
        val tier: String? = null,
        val queueTier: String? = null,
        val entryId: String? = null,
        val queueEntryId: Long? = null,
        val local: String? = null,
        val localUri: String? = null,
        val duration: String? = null,
        val durationText: String? = null,
        val album: String? = null,
        val explicit: Boolean? = null,
        val isExplicit: Boolean = false,
        val video: Boolean = false,
        val isVideo: Boolean = false,
        val sourceType: String? = null,
    ) {
        fun toSong(): Song {
            val resolvedTierName = queueTier ?: tier
            val resolvedTier = resolvedTierName?.let { runCatching { QueueTier.valueOf(it) }.getOrNull() }
                ?: if (auto) QueueTier.AUTOPLAY else QueueTier.CONTEXT
            val resolvedVideoId = videoId.ifEmpty { id }
            val resolvedEntryId = entryId ?: queueEntryId?.toString()
            return Song(
                videoId = resolvedVideoId,
                title = title,
                artist = artist,
                thumbnailUrl = thumbnailUrl ?: artwork,
                durationText = durationText ?: duration,
                albumName = album,
                isExplicit = explicit ?: isExplicit,
                isVideo = isVideo || video,
                isVideoOrigin = isVideo || video,
                queueTier = resolvedTier,
                queueEntryId = resolvedEntryId,
                playbackSourceType = sourceType?.let {
                    runCatching { PlaybackSourceType.valueOf(it) }.getOrNull()
                },
                localUri = localUri ?: local,
            )
        }
    }

    const val SNAPSHOT_LAST_PLAYED = "last_played"
    const val SNAPSHOT_PARTY_PERSONAL_STASH = "party_personal_stash"
}
