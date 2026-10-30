package com.music.vibe.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.music.vibe.data.model.SearchHistoryEntity
import com.music.vibe.data.model.Song
import com.music.vibe.data.model.SongJsonCodec
import com.music.vibe.data.runCatchingLogged
import com.music.vibe.data.spotify.SpotifyImportTrack
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Relational SQLite persistence database for Vibe Music.
 *
 * Replaces fragile full-blob SharedPreferences rewrites for queues, downloads,
 * collections, search history, monthly listening stats, and resumable Spotify
 * imports with indexed SQLite tables running in WAL mode.
 */
class VibeDatabase private constructor(
    context: Context,
    databaseName: String? = DATABASE_NAME,
) : SQLiteOpenHelper(context.applicationContext, databaseName, null, DATABASE_VERSION) {

    val queueSnapshotDao = QueueSnapshotDao(this)
    val searchHistoryDao = SearchHistoryDao(this)
    val downloadsDao = DownloadsDao(this)
    val listeningStatsDao = ListeningStatsDao(this)
    val smartPlaylistsDao = SmartPlaylistsDao(this)
    val spotifyImportCheckpointDao = SpotifyImportCheckpointDao(this)

    @Volatile
    var isV2BackfillDone: Boolean = false
        internal set

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        if (!db.isReadOnly && db.path != null && !db.path.contains(":memory:")) {
            runCatchingLogged(TAG, "enableWriteAheadLogging") {
                db.enableWriteAheadLogging()
            }
        }
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (!db.isReadOnly) {
            runCatching { db.execSQL("PRAGMA foreign_keys=ON") }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS migration_meta (
                migration_key TEXT PRIMARY KEY NOT NULL,
                migrated_at_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS queue_snapshots (
                snapshot_key TEXT PRIMARY KEY NOT NULL,
                current_index INTEGER NOT NULL DEFAULT 0,
                position_ms INTEGER NOT NULL DEFAULT 0,
                was_playing INTEGER NOT NULL DEFAULT 0,
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS queue_items (
                snapshot_key TEXT NOT NULL,
                position INTEGER NOT NULL,
                video_id TEXT NOT NULL,
                song_json TEXT NOT NULL,
                PRIMARY KEY (snapshot_key, position),
                FOREIGN KEY (snapshot_key) REFERENCES queue_snapshots(snapshot_key) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_queue_items_snapshot ON queue_items(snapshot_key)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS search_history (
                id TEXT PRIMARY KEY NOT NULL,
                position INTEGER NOT NULL,
                entity_json TEXT NOT NULL,
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_search_history_pos ON search_history(position ASC)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS downloaded_tracks (
                video_id TEXT PRIMARY KEY NOT NULL,
                uri TEXT NOT NULL,
                metadata_json TEXT,
                date_added_sec INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_downloaded_tracks_uri ON downloaded_tracks(uri)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_downloaded_tracks_date_added ON downloaded_tracks(date_added_sec DESC)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS saved_collections (
                collection_id TEXT PRIMARY KEY NOT NULL,
                collection_json TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS monthly_stats_buckets (
                month_key TEXT PRIMARY KEY NOT NULL,
                bucket_json TEXT NOT NULL,
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS listening_track_stats (
                month_key TEXT NOT NULL,
                video_id TEXT NOT NULL,
                title TEXT NOT NULL,
                artist TEXT NOT NULL,
                album TEXT,
                thumbnail_url TEXT,
                album_id TEXT,
                artist_id TEXT,
                duration_text TEXT,
                played_ms INTEGER NOT NULL,
                play_count INTEGER NOT NULL,
                last_played_ms INTEGER NOT NULL,
                PRIMARY KEY (month_key, video_id),
                FOREIGN KEY (month_key) REFERENCES monthly_stats_buckets(month_key) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_listening_track_stats_ms ON listening_track_stats(month_key, played_ms DESC)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS spotify_import_checkpoint (
                job_id TEXT PRIMARY KEY NOT NULL,
                playlist_url TEXT NOT NULL,
                playlist_title TEXT NOT NULL,
                tracks_json TEXT NOT NULL,
                next_index INTEGER NOT NULL,
                total_count INTEGER NOT NULL,
                created_playlist_id TEXT,
                saved_song_count INTEGER NOT NULL DEFAULT 0,
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS spotify_import_matched_tracks (
                job_id TEXT NOT NULL,
                track_index INTEGER NOT NULL,
                song_json TEXT NOT NULL,
                PRIMARY KEY (job_id, track_index),
                FOREIGN KEY (job_id) REFERENCES spotify_import_checkpoint(job_id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onCreate(db)
        if (oldVersion < 2) {
            addColumnIfMissing(db, "downloaded_tracks", "date_added_sec", "INTEGER NOT NULL DEFAULT 0")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_downloaded_tracks_date_added ON downloaded_tracks(date_added_sec DESC)",
            )
            addColumnIfMissing(db, "listening_track_stats", "thumbnail_url", "TEXT")
            addColumnIfMissing(db, "listening_track_stats", "album_id", "TEXT")
            addColumnIfMissing(db, "listening_track_stats", "artist_id", "TEXT")
            addColumnIfMissing(db, "listening_track_stats", "duration_text", "TEXT")
            addColumnIfMissing(db, "spotify_import_checkpoint", "created_playlist_id", "TEXT")
            addColumnIfMissing(db, "spotify_import_checkpoint", "saved_song_count", "INTEGER NOT NULL DEFAULT 0")
        }
    }

    private fun addColumnIfMissing(
        db: SQLiteDatabase,
        table: String,
        column: String,
        definition: String,
    ) {
        val exists = db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIdx = cursor.getColumnIndex("name")
            var found = false
            while (cursor.moveToNext()) {
                if (nameIdx >= 0 && cursor.getString(nameIdx).equals(column, ignoreCase = true)) {
                    found = true
                    break
                }
            }
            found
        }
        if (!exists) {
            db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
        }
    }

    /**
     * Backfills v2 columns (`downloaded_tracks.date_added_sec` and
     * `listening_track_stats` metadata columns) in chunked transactions so
     * concurrent writers are never blocked for long.
     */
    fun backfillV2MetadataIfNeeded(chunkSize: Int = 200) {
        if (isV2BackfillDone) return
        if (isMigrationDone(BACKFILL_KEY_V2)) {
            isV2BackfillDone = true
            return
        }
        runCatchingLogged(TAG, "backfillV2MetadataIfNeeded") {
            val db = writableDatabase
            val parser = Json { ignoreUnknownKeys = true }

            // 1. Backfill downloaded_tracks.date_added_sec in chunks
            val pendingDownloads = mutableListOf<Pair<String, String>>()
            db.rawQuery(
                "SELECT video_id, metadata_json FROM downloaded_tracks WHERE date_added_sec = 0 AND metadata_json IS NOT NULL",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val vid = cursor.getString(0)
                    val meta = cursor.getString(1)
                    if (!vid.isNullOrBlank() && !meta.isNullOrBlank()) {
                        pendingDownloads.add(vid to meta)
                    }
                }
            }
            for (chunk in pendingDownloads.chunked(chunkSize.coerceAtLeast(1))) {
                db.beginTransaction()
                try {
                    for ((vid, metaJson) in chunk) {
                        val addedSec = extractDateAddedSeconds(parser, metaJson)?.takeIf { it > 0L } ?: 1L
                        val cv = ContentValues().apply {
                            put("date_added_sec", addedSec)
                        }
                        db.update("downloaded_tracks", cv, "video_id = ?", arrayOf(vid))
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }

            // 2. Backfill listening_track_stats from monthly_stats_buckets.bucket_json in chunks
            val buckets = mutableListOf<Pair<String, String>>()
            db.rawQuery(
                "SELECT month_key, bucket_json FROM monthly_stats_buckets",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val monthKey = cursor.getString(0)
                    val bucketJson = cursor.getString(1)
                    if (!monthKey.isNullOrBlank() && !bucketJson.isNullOrBlank()) {
                        buckets.add(monthKey to bucketJson)
                    }
                }
            }

            data class BackfillTrackRow(
                val monthKey: String,
                val videoId: String,
                val title: String,
                val artist: String,
                val album: String?,
                val thumbnailUrl: String?,
                val albumId: String?,
                val artistId: String?,
                val playedMs: Long,
                val playCount: Int,
                val lastPlayedMs: Long,
            )

            val statRowsToUpsert = mutableListOf<BackfillTrackRow>()
            for ((monthKey, bucketJson) in buckets) {
                val root = runCatching { parser.parseToJsonElement(bucketJson).jsonObject }.getOrNull() ?: continue
                val tracksArr = root["tracks"]?.jsonArray ?: continue
                for (el in tracksArr) {
                    val obj = el as? JsonObject ?: continue
                    val videoId = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
                    val title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val artist = obj["artist"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val album = obj["album"]?.jsonPrimitive?.contentOrNull
                    val art = obj["art"]?.jsonPrimitive?.contentOrNull
                    val albumId = obj["albumId"]?.jsonPrimitive?.contentOrNull
                    val artistId = obj["artistId"]?.jsonPrimitive?.contentOrNull
                    val ms = obj["ms"]?.jsonPrimitive?.longOrNull ?: 0L
                    val plays = obj["plays"]?.jsonPrimitive?.intOrNull ?: 0
                    val last = obj["last"]?.jsonPrimitive?.longOrNull ?: 0L
                    statRowsToUpsert.add(
                        BackfillTrackRow(
                            monthKey = monthKey,
                            videoId = videoId,
                            title = title,
                            artist = artist,
                            album = album,
                            thumbnailUrl = art,
                            albumId = albumId,
                            artistId = artistId,
                            playedMs = ms,
                            playCount = plays,
                            lastPlayedMs = last,
                        ),
                    )
                }
            }

            for (chunk in statRowsToUpsert.chunked(chunkSize.coerceAtLeast(1))) {
                db.beginTransaction()
                try {
                    for (row in chunk) {
                        val cv = ContentValues().apply {
                            put("month_key", row.monthKey)
                            put("video_id", row.videoId)
                            put("title", row.title)
                            put("artist", row.artist)
                            if (row.album != null) put("album", row.album) else putNull("album")
                            if (row.thumbnailUrl != null) put("thumbnail_url", row.thumbnailUrl) else putNull("thumbnail_url")
                            if (row.albumId != null) put("album_id", row.albumId) else putNull("album_id")
                            if (row.artistId != null) put("artist_id", row.artistId) else putNull("artist_id")
                            put("played_ms", row.playedMs)
                            put("play_count", row.playCount)
                            put("last_played_ms", row.lastPlayedMs)
                        }
                        db.insertWithOnConflict(
                            "listening_track_stats",
                            null,
                            cv,
                            SQLiteDatabase.CONFLICT_REPLACE,
                        )
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }

            markMigrationDone(BACKFILL_KEY_V2)
            isV2BackfillDone = true
        }
    }

    fun isMigrationDone(key: String): Boolean = runCatchingLogged(TAG, "isMigrationDone($key)") {
        readableDatabase.rawQuery(
            "SELECT 1 FROM migration_meta WHERE migration_key = ? LIMIT 1",
            arrayOf(key),
        ).use { it.moveToFirst() }
    }.getOrDefault(false)

    fun markMigrationDone(key: String) {
        runCatchingLogged(TAG, "markMigrationDone($key)") {
            val cv = ContentValues().apply {
                put("migration_key", key)
                put("migrated_at_ms", System.currentTimeMillis())
            }
            writableDatabase.insertWithOnConflict(
                "migration_meta",
                null,
                cv,
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
    }

    data class StoredQueueSnapshot(
        val songs: List<Song>,
        val index: Int,
        val positionMs: Long,
        val wasPlaying: Boolean,
    )

    data class TrackStatRow(
        val videoId: String,
        val title: String,
        val artist: String,
        val album: String?,
        val thumbnailUrl: String? = null,
        val albumId: String? = null,
        val artistId: String? = null,
        val durationText: String? = null,
        val playedMs: Long,
        val playCount: Int,
        val lastPlayedMs: Long,
    )

    class QueueSnapshotDao(private val helper: VibeDatabase) {
        fun saveSnapshot(
            snapshotKey: String,
            songs: List<Song>,
            currentIndex: Int,
            positionMs: Long,
            wasPlaying: Boolean = false,
        ) {
            if (songs.isEmpty()) {
                deleteSnapshot(snapshotKey)
                return
            }
            runCatchingLogged(TAG, "saveSnapshot($snapshotKey)") {
                val db = helper.writableDatabase
                db.beginTransaction()
                try {
                    val safeIndex = currentIndex.coerceIn(songs.indices)
                    val safePos = positionMs.coerceAtLeast(0L)
                    val header = ContentValues().apply {
                        put("snapshot_key", snapshotKey)
                        put("current_index", safeIndex)
                        put("position_ms", safePos)
                        put("was_playing", if (wasPlaying) 1 else 0)
                        put("updated_at_ms", System.currentTimeMillis())
                    }
                    db.insertWithOnConflict(
                        "queue_snapshots",
                        null,
                        header,
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                    db.delete("queue_items", "snapshot_key = ?", arrayOf(snapshotKey))
                    songs.forEachIndexed { idx, song ->
                        val itemCv = ContentValues().apply {
                            put("snapshot_key", snapshotKey)
                            put("position", idx)
                            put("video_id", song.videoId)
                            put("song_json", SongJsonCodec.encode(song))
                        }
                        db.insertWithOnConflict(
                            "queue_items",
                            null,
                            itemCv,
                            SQLiteDatabase.CONFLICT_REPLACE,
                        )
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }

        fun updatePlayhead(
            snapshotKey: String,
            currentIndex: Int,
            positionMs: Long,
        ): Boolean = runCatchingLogged(TAG, "updatePlayhead($snapshotKey)") {
            val db = helper.writableDatabase
            val cv = ContentValues().apply {
                put("current_index", currentIndex.coerceAtLeast(0))
                put("position_ms", positionMs.coerceAtLeast(0L))
                put("updated_at_ms", System.currentTimeMillis())
            }
            val updated = db.update(
                "queue_snapshots",
                cv,
                "snapshot_key = ?",
                arrayOf(snapshotKey),
            )
            updated > 0
        }.getOrDefault(false)

        fun hasSnapshot(snapshotKey: String): Boolean = runCatchingLogged(TAG, "hasSnapshot($snapshotKey)") {
            helper.readableDatabase.rawQuery(
                "SELECT 1 FROM queue_snapshots WHERE snapshot_key = ? LIMIT 1",
                arrayOf(snapshotKey),
            ).use { it.moveToFirst() }
        }.getOrDefault(false)

        fun loadSnapshot(snapshotKey: String): StoredQueueSnapshot? =
            runCatchingLogged(TAG, "loadSnapshot($snapshotKey)") {
                val db = helper.readableDatabase
                var index = 0
                var positionMs = 0L
                var wasPlaying = false
                val exists = db.rawQuery(
                    "SELECT current_index, position_ms, was_playing FROM queue_snapshots WHERE snapshot_key = ? LIMIT 1",
                    arrayOf(snapshotKey),
                ).use { cursor ->
                    if (!cursor.moveToFirst()) return@use false
                    index = cursor.getInt(0)
                    positionMs = cursor.getLong(1)
                    wasPlaying = cursor.getInt(2) != 0
                    true
                }
                if (!exists) return@runCatchingLogged null

                val songs = mutableListOf<Song>()
                db.rawQuery(
                    "SELECT song_json FROM queue_items WHERE snapshot_key = ? ORDER BY position ASC",
                    arrayOf(snapshotKey),
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val raw = cursor.getString(0)
                        SongJsonCodec.decode(raw)?.let(songs::add)
                    }
                }
                if (songs.isEmpty()) return@runCatchingLogged null
                StoredQueueSnapshot(
                    songs = songs,
                    index = index.coerceIn(songs.indices),
                    positionMs = positionMs.coerceAtLeast(0L),
                    wasPlaying = wasPlaying,
                )
            }.getOrNull()

        fun deleteSnapshot(snapshotKey: String) {
            runCatchingLogged(TAG, "deleteSnapshot($snapshotKey)") {
                val db = helper.writableDatabase
                db.beginTransaction()
                try {
                    db.delete("queue_items", "snapshot_key = ?", arrayOf(snapshotKey))
                    db.delete("queue_snapshots", "snapshot_key = ?", arrayOf(snapshotKey))
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }
    }

    class SearchHistoryDao(private val helper: VibeDatabase) {
        private val json = Json { ignoreUnknownKeys = true }

        fun getRecent(limit: Int = 20): List<SearchHistoryEntity> =
            runCatchingLogged(TAG, "SearchHistoryDao.getRecent") {
                val list = mutableListOf<SearchHistoryEntity>()
                helper.readableDatabase.rawQuery(
                    "SELECT entity_json FROM search_history ORDER BY position ASC LIMIT ?",
                    arrayOf(limit.toString()),
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val raw = cursor.getString(0)
                        runCatching {
                            json.decodeFromString(SearchHistoryEntity.serializer(), raw)
                        }.getOrNull()?.let(list::add)
                    }
                }
                list
            }.getOrDefault(emptyList())

        fun replaceAll(entries: List<SearchHistoryEntity>) {
            runCatchingLogged(TAG, "SearchHistoryDao.replaceAll") {
                val db = helper.writableDatabase
                val now = System.currentTimeMillis()
                db.beginTransaction()
                try {
                    db.delete("search_history", null, null)
                    entries.forEachIndexed { index, entity ->
                        val cv = ContentValues().apply {
                            put("id", entity.id.lowercase())
                            put("position", index)
                            put("entity_json", json.encodeToString(SearchHistoryEntity.serializer(), entity))
                            put("updated_at_ms", now)
                        }
                        db.insertWithOnConflict(
                            "search_history",
                            null,
                            cv,
                            SQLiteDatabase.CONFLICT_REPLACE,
                        )
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }
    }

    class DownloadsDao(private val helper: VibeDatabase) {
        private val json = Json { ignoreUnknownKeys = true }

        data class LoadedDownloadsState(
            val savedUris: Map<String, String>,
            val metadataJsonById: Map<String, String>,
            val collectionsJsonById: Map<String, String>,
        )

        fun loadAll(): LoadedDownloadsState = runCatchingLogged(TAG, "DownloadsDao.loadAll") {
            val db = helper.readableDatabase
            val savedUris = LinkedHashMap<String, String>()
            val metadataJsonById = LinkedHashMap<String, String>()
            db.rawQuery(
                "SELECT video_id, uri, metadata_json FROM downloaded_tracks",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val videoId = cursor.getString(0)
                    val uri = cursor.getString(1)
                    val metaJson = if (cursor.isNull(2)) null else cursor.getString(2)
                    if (!uri.isNullOrBlank()) {
                        savedUris[videoId] = uri
                    }
                    if (!metaJson.isNullOrBlank()) {
                        metadataJsonById[videoId] = metaJson
                    }
                }
            }

            val collectionsJsonById = LinkedHashMap<String, String>()
            db.rawQuery(
                "SELECT collection_id, collection_json FROM saved_collections",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val jsonStr = cursor.getString(1)
                    if (!jsonStr.isNullOrBlank()) {
                        collectionsJsonById[id] = jsonStr
                    }
                }
            }

            LoadedDownloadsState(
                savedUris = savedUris,
                metadataJsonById = metadataJsonById,
                collectionsJsonById = collectionsJsonById,
            )
        }.getOrDefault(LoadedDownloadsState(emptyMap(), emptyMap(), emptyMap()))

        fun saveTracksAndMetadata(
            savedUris: Map<String, String>,
            metadataJsonById: Map<String, String>,
            dateAddedSecById: Map<String, Long> = emptyMap(),
        ) {
            runCatchingLogged(TAG, "DownloadsDao.saveTracksAndMetadata") {
                val db = helper.writableDatabase
                db.beginTransaction()
                try {
                    db.delete("downloaded_tracks", null, null)
                    val allIds = LinkedHashSet<String>().apply {
                        addAll(savedUris.keys)
                        addAll(metadataJsonById.keys)
                    }
                    for (videoId in allIds) {
                        val uri = savedUris[videoId].orEmpty()
                        val metaJson = metadataJsonById[videoId]
                        if (uri.isBlank() && metaJson.isNullOrBlank()) continue
                        val dateAddedSec = dateAddedSecById[videoId]
                            ?: metaJson?.let { extractDateAddedSeconds(json, it) }
                            ?: 0L
                        val cv = ContentValues().apply {
                            put("video_id", videoId)
                            put("uri", uri)
                            if (metaJson != null) put("metadata_json", metaJson) else putNull("metadata_json")
                            put("date_added_sec", dateAddedSec.coerceAtLeast(0L))
                        }
                        db.insertWithOnConflict(
                            "downloaded_tracks",
                            null,
                            cv,
                            SQLiteDatabase.CONFLICT_REPLACE,
                        )
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }

        fun saveCollections(collectionsJsonById: Map<String, String>) {
            runCatchingLogged(TAG, "DownloadsDao.saveCollections") {
                val db = helper.writableDatabase
                db.beginTransaction()
                try {
                    db.delete("saved_collections", null, null)
                    for ((id, jsonStr) in collectionsJsonById) {
                        val cv = ContentValues().apply {
                            put("collection_id", id)
                            put("collection_json", jsonStr)
                        }
                        db.insertWithOnConflict(
                            "saved_collections",
                            null,
                            cv,
                            SQLiteDatabase.CONFLICT_REPLACE,
                        )
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }
    }

    class ListeningStatsDao(private val helper: VibeDatabase) {
        fun saveMonthBucket(
            monthKey: String,
            bucketJson: String,
            tracks: List<TrackStatRow> = emptyList(),
        ) {
            runCatchingLogged(TAG, "ListeningStatsDao.saveMonthBucket($monthKey)") {
                val db = helper.writableDatabase
                db.beginTransaction()
                try {
                    val cv = ContentValues().apply {
                        put("month_key", monthKey)
                        put("bucket_json", bucketJson)
                        put("updated_at_ms", System.currentTimeMillis())
                    }
                    db.insertWithOnConflict(
                        "monthly_stats_buckets",
                        null,
                        cv,
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                    db.delete("listening_track_stats", "month_key = ?", arrayOf(monthKey))
                    for (t in tracks) {
                        val tCv = ContentValues().apply {
                            put("month_key", monthKey)
                            put("video_id", t.videoId)
                            put("title", t.title)
                            put("artist", t.artist)
                            if (t.album != null) put("album", t.album) else putNull("album")
                            if (t.thumbnailUrl != null) put("thumbnail_url", t.thumbnailUrl) else putNull("thumbnail_url")
                            if (t.albumId != null) put("album_id", t.albumId) else putNull("album_id")
                            if (t.artistId != null) put("artist_id", t.artistId) else putNull("artist_id")
                            if (t.durationText != null) put("duration_text", t.durationText) else putNull("duration_text")
                            put("played_ms", t.playedMs)
                            put("play_count", t.playCount)
                            put("last_played_ms", t.lastPlayedMs)
                        }
                        db.insertWithOnConflict(
                            "listening_track_stats",
                            null,
                            tCv,
                            SQLiteDatabase.CONFLICT_REPLACE,
                        )
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }

        fun loadMonthBucketJson(monthKey: String): String? =
            runCatchingLogged(TAG, "ListeningStatsDao.loadMonthBucketJson($monthKey)") {
                helper.readableDatabase.rawQuery(
                    "SELECT bucket_json FROM monthly_stats_buckets WHERE month_key = ? LIMIT 1",
                    arrayOf(monthKey),
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull()

        fun listMonthKeys(): List<String> =
            runCatchingLogged(TAG, "ListeningStatsDao.listMonthKeys") {
                val keys = mutableListOf<String>()
                helper.readableDatabase.rawQuery(
                    "SELECT month_key FROM monthly_stats_buckets ORDER BY month_key ASC",
                    null,
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        keys.add(cursor.getString(0))
                    }
                }
                keys
            }.getOrDefault(emptyList())

        fun deleteMonthBucket(monthKey: String) {
            runCatchingLogged(TAG, "ListeningStatsDao.deleteMonthBucket($monthKey)") {
                val db = helper.writableDatabase
                db.beginTransaction()
                try {
                    db.delete("listening_track_stats", "month_key = ?", arrayOf(monthKey))
                    db.delete("monthly_stats_buckets", "month_key = ?", arrayOf(monthKey))
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }

        fun clearAllBuckets() {
            runCatchingLogged(TAG, "ListeningStatsDao.clearAllBuckets") {
                val db = helper.writableDatabase
                db.beginTransaction()
                try {
                    db.delete("listening_track_stats", null, null)
                    db.delete("monthly_stats_buckets", null, null)
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }
    }

    /**
     * Pure SQL queries for auto-updating Smart Mixes:
     * - Most Played (Last 30 Days)
     * - Recently Added / Downloaded
     * - Forgotten Favorites (played >= 3 times, not played in 60+ days)
     */
    class SmartPlaylistsDao(private val helper: VibeDatabase) {
        private val json = Json { ignoreUnknownKeys = true }

        fun getMostPlayedLast30Days(
            sinceMs: Long,
            limit: Int = 50,
        ): List<Song> = runCatchingLogged(TAG, "SmartPlaylistsDao.getMostPlayedLast30Days") {
            val db = helper.readableDatabase
            val rawSongs = mutableListOf<Song>()
            db.rawQuery(
                """
                SELECT video_id,
                       MAX(title) AS title,
                       MAX(artist) AS artist,
                       MAX(album) AS album,
                       MAX(thumbnail_url) AS thumbnail_url,
                       MAX(album_id) AS album_id,
                       MAX(artist_id) AS artist_id,
                       MAX(duration_text) AS duration_text,
                       SUM(played_ms) AS total_ms,
                       SUM(play_count) AS total_plays
                FROM listening_track_stats
                WHERE last_played_ms >= CAST(? AS INTEGER)
                GROUP BY video_id
                HAVING total_plays > 0
                ORDER BY total_plays DESC, total_ms DESC
                LIMIT CAST(? AS INTEGER)
                """.trimIndent(),
                arrayOf(sinceMs.toString(), limit.toString()),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val videoId = cursor.getString(0) ?: continue
                    val title = cursor.getString(1).orEmpty()
                    val artist = cursor.getString(2).orEmpty()
                    val album = if (cursor.isNull(3)) null else cursor.getString(3)
                    val thumb = if (cursor.isNull(4)) null else cursor.getString(4)
                    val albumId = if (cursor.isNull(5)) null else cursor.getString(5)
                    val artistId = if (cursor.isNull(6)) null else cursor.getString(6)
                    val duration = if (cursor.isNull(7)) null else cursor.getString(7)
                    rawSongs.add(
                        Song(
                            videoId = videoId,
                            title = title,
                            artist = artist,
                            albumName = album,
                            thumbnailUrl = thumb,
                            albumId = albumId,
                            artistId = artistId,
                            durationText = duration,
                        ),
                    )
                }
            }
            enrichMissingMetadata(db, rawSongs)
        }.getOrDefault(emptyList())

        fun getRecentlyDownloaded(limit: Int = 50): List<Song> =
            runCatchingLogged(TAG, "SmartPlaylistsDao.getRecentlyDownloaded") {
                val db = helper.readableDatabase
                val entries = mutableListOf<Triple<Song, String, Long>>()
                val seenUris = HashSet<String>()
                var anyZeroDate = false
                db.rawQuery(
                    """
                    SELECT video_id, uri, metadata_json, date_added_sec
                    FROM downloaded_tracks
                    WHERE uri != '' AND metadata_json IS NOT NULL
                    ORDER BY date_added_sec DESC, rowid DESC
                    LIMIT CAST(? AS INTEGER)
                    """.trimIndent(),
                    arrayOf((limit * 2).toString()),
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val videoId = cursor.getString(0) ?: continue
                        val uri = cursor.getString(1) ?: continue
                        val metaJson = cursor.getString(2) ?: continue
                        val colDateSec = cursor.getLong(3)
                        if (!seenUris.add(uri)) continue
                        val parsed = parseDownloadedSongMetadata(json, videoId, uri, metaJson) ?: continue
                        val resolvedDateSec = if (colDateSec > 0L) {
                            colDateSec
                        } else {
                            anyZeroDate = true
                            parsed.second
                        }
                        entries.add(Triple(parsed.first, uri, resolvedDateSec))
                    }
                }
                val ordered = if (!helper.isV2BackfillDone && anyZeroDate) {
                    entries.sortedByDescending { it.third }
                } else {
                    entries
                }
                ordered.take(limit).map { it.first }
            }.getOrDefault(emptyList())

        fun getForgottenFavorites(
            beforeMs: Long,
            minPlays: Int = 3,
            limit: Int = 50,
        ): List<Song> = runCatchingLogged(TAG, "SmartPlaylistsDao.getForgottenFavorites") {
            val db = helper.readableDatabase
            val rawSongs = mutableListOf<Song>()
            db.rawQuery(
                """
                SELECT video_id,
                       MAX(title) AS title,
                       MAX(artist) AS artist,
                       MAX(album) AS album,
                       MAX(thumbnail_url) AS thumbnail_url,
                       MAX(album_id) AS album_id,
                       MAX(artist_id) AS artist_id,
                       MAX(duration_text) AS duration_text,
                       SUM(played_ms) AS total_ms,
                       SUM(play_count) AS total_plays,
                       MAX(last_played_ms) AS latest_play
                FROM listening_track_stats
                GROUP BY video_id
                HAVING total_plays >= CAST(? AS INTEGER) AND latest_play > 0 AND latest_play <= CAST(? AS INTEGER)
                ORDER BY total_plays DESC, total_ms DESC
                LIMIT CAST(? AS INTEGER)
                """.trimIndent(),
                arrayOf(minPlays.toString(), beforeMs.toString(), limit.toString()),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val videoId = cursor.getString(0) ?: continue
                    val title = cursor.getString(1).orEmpty()
                    val artist = cursor.getString(2).orEmpty()
                    val album = if (cursor.isNull(3)) null else cursor.getString(3)
                    val thumb = if (cursor.isNull(4)) null else cursor.getString(4)
                    val albumId = if (cursor.isNull(5)) null else cursor.getString(5)
                    val artistId = if (cursor.isNull(6)) null else cursor.getString(6)
                    val duration = if (cursor.isNull(7)) null else cursor.getString(7)
                    rawSongs.add(
                        Song(
                            videoId = videoId,
                            title = title,
                            artist = artist,
                            albumName = album,
                            thumbnailUrl = thumb,
                            albumId = albumId,
                            artistId = artistId,
                            durationText = duration,
                        ),
                    )
                }
            }
            enrichMissingMetadata(db, rawSongs)
        }.getOrDefault(emptyList())

        private fun enrichMissingMetadata(db: SQLiteDatabase, songs: List<Song>): List<Song> {
            val missingIds = songs.filter { it.thumbnailUrl == null || it.durationText == null }
                .map { it.videoId }
                .distinct()
            if (missingIds.isEmpty()) return songs

            val fallbackById = HashMap<String, Song>()
            for (chunk in missingIds.chunked(50)) {
                val placeholders = chunk.joinToString(",") { "?" }
                val args = chunk.toTypedArray()

                // Check downloaded_tracks first
                db.rawQuery(
                    "SELECT video_id, uri, metadata_json FROM downloaded_tracks WHERE video_id IN ($placeholders) AND metadata_json IS NOT NULL",
                    args,
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val vid = cursor.getString(0) ?: continue
                        val uri = cursor.getString(1).orEmpty()
                        val meta = cursor.getString(2) ?: continue
                        parseDownloadedSongMetadata(json, vid, uri, meta)?.first?.let {
                            fallbackById[vid] = it
                        }
                    }
                }

                // Check queue_items for any remaining missing IDs
                db.rawQuery(
                    "SELECT video_id, song_json FROM queue_items WHERE video_id IN ($placeholders)",
                    args,
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val vid = cursor.getString(0) ?: continue
                        if (vid in fallbackById) continue
                        val songJson = cursor.getString(1) ?: continue
                        SongJsonCodec.decode(songJson)?.let { fallbackById[vid] = it }
                    }
                }
            }

            if (fallbackById.isEmpty()) return songs
            return songs.map { song ->
                val fb = fallbackById[song.videoId] ?: return@map song
                song.copy(
                    thumbnailUrl = song.thumbnailUrl ?: fb.thumbnailUrl,
                    durationText = song.durationText ?: fb.durationText,
                    albumName = song.albumName ?: fb.albumName,
                    albumId = song.albumId ?: fb.albumId,
                    artistId = song.artistId ?: fb.artistId,
                    localUri = song.localUri ?: fb.localUri,
                )
            }
        }

        private fun parseDownloadedSongMetadata(
            json: Json,
            videoId: String,
            uri: String,
            metaJson: String,
        ): Pair<Song, Long>? = runCatching {
            val obj = json.parseToJsonElement(metaJson).jsonObject
            val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return null
            val artist = obj["artist"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val thumbnailUrl = obj["thumbnailUrl"]?.jsonPrimitive?.contentOrNull
            val durationText = obj["durationText"]?.jsonPrimitive?.contentOrNull
            val albumName = obj["albumName"]?.jsonPrimitive?.contentOrNull
            val downloadFormat = obj["downloadFormat"]?.jsonPrimitive?.contentOrNull
            val dateAddedSec = obj["dateAddedSeconds"]?.jsonPrimitive?.longOrNull ?: 0L
            Song(
                videoId = videoId,
                title = title,
                artist = artist,
                thumbnailUrl = thumbnailUrl,
                durationText = durationText,
                albumName = albumName,
                localUri = uri.takeIf { it.isNotBlank() },
                downloadFormat = downloadFormat,
                localDateAddedSeconds = dateAddedSec.takeIf { it > 0L },
            ) to dateAddedSec
        }.getOrNull()
    }

    /**
     * Incremental checkpoint DAO for resumable background Spotify imports.
     */
    class SpotifyImportCheckpointDao(private val helper: VibeDatabase) {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
        private val trackListSerializer = ListSerializer(SpotifyImportTrack.serializer())

        data class StoredSpotifyCheckpoint(
            val jobId: String,
            val playlistUrl: String,
            val playlistTitle: String,
            val tracks: List<SpotifyImportTrack>,
            val nextIndex: Int,
            val totalCount: Int,
            val createdPlaylistId: String?,
            val savedSongCount: Int,
            val matchedSongs: List<Song>,
            val updatedAtMs: Long,
        )

        fun saveCheckpoint(
            jobId: String,
            playlistUrl: String,
            playlistTitle: String,
            tracks: List<SpotifyImportTrack>,
            nextIndex: Int,
            totalCount: Int,
            createdPlaylistId: String? = null,
            savedSongCount: Int = 0,
            initialMatchedSongs: List<Pair<Int, Song>> = emptyList(),
        ) {
            runCatchingLogged(TAG, "SpotifyImportCheckpointDao.saveCheckpoint($jobId)") {
                val db = helper.writableDatabase
                db.beginTransaction()
                try {
                    val cv = ContentValues().apply {
                        put("job_id", jobId)
                        put("playlist_url", playlistUrl)
                        put("playlist_title", playlistTitle)
                        put("tracks_json", json.encodeToString(trackListSerializer, tracks))
                        put("next_index", nextIndex.coerceAtLeast(0))
                        put("total_count", totalCount.coerceAtLeast(0))
                        if (createdPlaylistId != null) {
                            put("created_playlist_id", createdPlaylistId)
                        } else {
                            putNull("created_playlist_id")
                        }
                        put("saved_song_count", savedSongCount.coerceAtLeast(0))
                        put("updated_at_ms", System.currentTimeMillis())
                    }
                    db.insertWithOnConflict(
                        "spotify_import_checkpoint",
                        null,
                        cv,
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                    for ((trackIdx, song) in initialMatchedSongs) {
                        val mCv = ContentValues().apply {
                            put("job_id", jobId)
                            put("track_index", trackIdx)
                            put("song_json", SongJsonCodec.encode(song))
                        }
                        db.insertWithOnConflict(
                            "spotify_import_matched_tracks",
                            null,
                            mCv,
                            SQLiteDatabase.CONFLICT_REPLACE,
                        )
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }

        fun recordTrackResolved(
            jobId: String,
            trackIndex: Int,
            matchedSong: Song?,
            nextIndex: Int,
        ) {
            runCatchingLogged(TAG, "SpotifyImportCheckpointDao.recordTrackResolved($jobId, $trackIndex)") {
                val db = helper.writableDatabase
                db.beginTransaction()
                try {
                    if (matchedSong != null) {
                        val mCv = ContentValues().apply {
                            put("job_id", jobId)
                            put("track_index", trackIndex)
                            put("song_json", SongJsonCodec.encode(matchedSong))
                        }
                        db.insertWithOnConflict(
                            "spotify_import_matched_tracks",
                            null,
                            mCv,
                            SQLiteDatabase.CONFLICT_REPLACE,
                        )
                    }
                    // Advance next_index monotonically so out-of-order concurrent completions never regress it
                    db.execSQL(
                        """
                        UPDATE spotify_import_checkpoint
                        SET next_index = CASE WHEN next_index < ? THEN ? ELSE next_index END,
                            updated_at_ms = ?
                        WHERE job_id = ?
                        """.trimIndent(),
                        arrayOf<Any>(nextIndex, nextIndex, System.currentTimeMillis(), jobId),
                    )
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }

        fun updatePlaylistSaveProgress(
            jobId: String,
            createdPlaylistId: String,
            savedSongCount: Int,
        ) {
            runCatchingLogged(TAG, "SpotifyImportCheckpointDao.updatePlaylistSaveProgress($jobId)") {
                val db = helper.writableDatabase
                val cv = ContentValues().apply {
                    put("created_playlist_id", createdPlaylistId)
                    put("saved_song_count", savedSongCount.coerceAtLeast(0))
                    put("updated_at_ms", System.currentTimeMillis())
                }
                db.update("spotify_import_checkpoint", cv, "job_id = ?", arrayOf(jobId))
            }
        }

        fun loadActiveCheckpoint(): StoredSpotifyCheckpoint? =
            runCatchingLogged(TAG, "SpotifyImportCheckpointDao.loadActiveCheckpoint") {
                val db = helper.readableDatabase
                var jobId = ""
                var playlistUrl = ""
                var playlistTitle = ""
                var tracksJson = ""
                var nextIndex = 0
                var totalCount = 0
                var createdPlaylistId: String? = null
                var savedSongCount = 0
                var updatedAtMs = 0L

                val found = db.rawQuery(
                    """
                    SELECT job_id, playlist_url, playlist_title, tracks_json,
                           next_index, total_count, created_playlist_id, saved_song_count, updated_at_ms
                    FROM spotify_import_checkpoint
                    ORDER BY updated_at_ms DESC
                    LIMIT 1
                    """.trimIndent(),
                    null,
                ).use { cursor ->
                    if (!cursor.moveToFirst()) return@use false
                    jobId = cursor.getString(0)
                    playlistUrl = cursor.getString(1)
                    playlistTitle = cursor.getString(2)
                    tracksJson = cursor.getString(3)
                    nextIndex = cursor.getInt(4)
                    totalCount = cursor.getInt(5)
                    createdPlaylistId = if (cursor.isNull(6)) null else cursor.getString(6)
                    savedSongCount = cursor.getInt(7)
                    updatedAtMs = cursor.getLong(8)
                    true
                }
                if (!found || jobId.isBlank()) return@runCatchingLogged null

                val tracks = runCatching {
                    json.decodeFromString(trackListSerializer, tracksJson)
                }.getOrDefault(emptyList())

                val matchedSongs = mutableListOf<Song>()
                db.rawQuery(
                    "SELECT song_json FROM spotify_import_matched_tracks WHERE job_id = ? ORDER BY track_index ASC",
                    arrayOf(jobId),
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val raw = cursor.getString(0)
                        SongJsonCodec.decode(raw)?.let(matchedSongs::add)
                    }
                }

                StoredSpotifyCheckpoint(
                    jobId = jobId,
                    playlistUrl = playlistUrl,
                    playlistTitle = playlistTitle,
                    tracks = tracks,
                    nextIndex = nextIndex,
                    totalCount = totalCount,
                    createdPlaylistId = createdPlaylistId,
                    savedSongCount = savedSongCount,
                    matchedSongs = matchedSongs,
                    updatedAtMs = updatedAtMs,
                )
            }.getOrNull()

        /**
         * Explicitly deletes child rows from `spotify_import_matched_tracks`
         * before deleting from `spotify_import_checkpoint` inside a single
         * transaction, preventing orphan rows even if foreign keys were off.
         */
        fun clearCheckpoint(jobId: String) {
            runCatchingLogged(TAG, "SpotifyImportCheckpointDao.clearCheckpoint($jobId)") {
                val db = helper.writableDatabase
                db.beginTransaction()
                try {
                    db.delete("spotify_import_matched_tracks", "job_id = ?", arrayOf(jobId))
                    db.delete("spotify_import_checkpoint", "job_id = ?", arrayOf(jobId))
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }

        fun clearAllCheckpoints() {
            runCatchingLogged(TAG, "SpotifyImportCheckpointDao.clearAllCheckpoints") {
                val db = helper.writableDatabase
                db.beginTransaction()
                try {
                    db.delete("spotify_import_matched_tracks", null, null)
                    db.delete("spotify_import_checkpoint", null, null)
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }
    }

    companion object {
        private const val TAG = "VibeDatabase"
        private const val DATABASE_NAME = "vibe_music.db"
        const val DATABASE_VERSION = 2
        const val BACKFILL_KEY_V2 = "backfill_v2_done"

        @Volatile
        private var instance: VibeDatabase? = null

        fun getInstance(context: Context): VibeDatabase {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: VibeDatabase(context.applicationContext, DATABASE_NAME).also { db ->
                    instance = db
                    LegacyDataMigrator.migrateIfNeeded(context.applicationContext, db)
                }
            }
        }

        fun peekInstance(): VibeDatabase? = instance

        fun createInMemoryForTesting(context: Context): VibeDatabase {
            val db = VibeDatabase(context.applicationContext, databaseName = null)
            instance = db
            return db
        }

        internal fun extractDateAddedSeconds(json: Json, metaJson: String): Long? = runCatching {
            json.parseToJsonElement(metaJson).jsonObject["dateAddedSeconds"]?.jsonPrimitive?.longOrNull
        }.getOrNull()
    }
}
