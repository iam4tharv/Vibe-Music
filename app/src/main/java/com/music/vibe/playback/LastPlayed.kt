package com.music.vibe.playback

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.Player
import com.music.vibe.data.db.LegacyDataMigrator
import com.music.vibe.data.db.VibeDatabase
import com.music.vibe.data.model.Song
import com.music.vibe.data.model.SongJsonCodec
import com.music.vibe.data.runCatchingLogged
import kotlinx.serialization.Serializable

/**
 * The queue as it stood when the app last stopped playing, and where in it the
 * playhead was.
 *
 * Backed by [VibeDatabase] (`queue_snapshots` + `queue_items`) using canonical
 * [SongJsonCodec] serialization so every [Song] property is preserved across
 * restarts without rewriting an entire SharedPreferences XML blob on every
 * playhead tick.
 */
object LastPlayed {

    private const val TAG = "LastPlayed"
    private const val SNAPSHOT_KEY = LegacyDataMigrator.SNAPSHOT_LAST_PLAYED

    /** Maximum number of tracks persisted around the playhead for cold-start resume. */
    private const val MAX_PERSISTED_QUEUE_ITEMS = 200
    private const val BACK_WINDOW_ITEMS = 50

    data class Queue(val songs: List<Song>, val index: Int, val positionMs: Long = 0L)

    @Volatile
    private var database: VibeDatabase? = null

    @Volatile
    private var testPrefs: SharedPreferences? = null

    fun init(context: Context) {
        val appContext = context.applicationContext
        val db = VibeDatabase.getInstance(appContext)
        database = db
        testPrefs = null
    }

    internal fun initForTesting(prefs: SharedPreferences, db: VibeDatabase? = null) {
        testPrefs = prefs
        database = db
    }

    internal fun initForTesting(db: VibeDatabase) {
        database = db
        testPrefs = null
    }

    /**
     * Bounded window around [currentIndex] so huge queues do not serialize thousands
     * of tracks on every queue change.
     */
    fun window(count: Int, currentIndex: Int): IntRange {
        if (count <= 0) return IntRange.EMPTY
        if (count <= MAX_PERSISTED_QUEUE_ITEMS) return 0 until count
        val safeCurrent = currentIndex.coerceIn(0, count - 1)
        val start = (safeCurrent - BACK_WINDOW_ITEMS)
            .coerceAtMost(count - MAX_PERSISTED_QUEUE_ITEMS)
            .coerceAtLeast(0)
        val endExclusive = (start + MAX_PERSISTED_QUEUE_ITEMS).coerceAtMost(count)
        return start until endExclusive
    }

    fun saveQueue(songs: List<Song>, index: Int) {
        val currentPos = load()?.positionMs ?: 0L
        saveQueueImmediately(songs = songs, index = index, positionMs = currentPos)
    }

    fun saveQueueImmediately(songs: List<Song>, index: Int, positionMs: Long = 0L) {
        val db = database
        val prefs = testPrefs
        if (db == null && prefs == null) return
        val kept = songs.mapIndexedNotNull { idx, song ->
            if (song.isDeviceFile()) null else (idx to song)
        }
        if (kept.isEmpty()) {
            clearImmediately()
            return
        }
        val safeTarget = index.coerceIn(0, (songs.size - 1).coerceAtLeast(0))
        val savedIndex = kept.indexOfFirst { (originalIndex, _) -> originalIndex >= safeTarget }
            .let { if (it >= 0) it else kept.lastIndex }
        val filteredSongs = kept.map { (_, song) -> song }
        val safePos = positionMs.coerceAtLeast(0L)

        if (db != null) {
            db.queueSnapshotDao.saveSnapshot(
                snapshotKey = SNAPSHOT_KEY,
                songs = filteredSongs,
                currentIndex = savedIndex,
                positionMs = safePos,
                wasPlaying = false,
            )
        } else if (prefs != null) {
            val encoded = runCatchingLogged(TAG, "encodeTestSnapshot") {
                SongJsonCodec.json.encodeToString(
                    TestStoredSnapshot.serializer(),
                    TestStoredSnapshot(songs = filteredSongs, index = savedIndex, positionMs = safePos),
                )
            }.getOrNull() ?: return
            prefs.edit().putString(KEY_QUEUE, encoded).commit()
        }
    }

    fun savePlaybackState(index: Int, positionMs: Long) {
        updatePlayhead(currentIndex = index.coerceAtLeast(0), positionMs = positionMs.coerceAtLeast(0L))
    }

    fun save(player: Player) {
        if (player.mediaItemCount == 0) return
        val songs = (0 until player.mediaItemCount).map { idx ->
            player.getMediaItemAt(idx).toSong()
        }
        saveQueueImmediately(
            songs = songs,
            index = player.currentMediaItemIndex,
            positionMs = player.currentPosition,
        )
    }

    /**
     * Fast path for periodic playhead updates when the queue items themselves
     * have not changed. Updates only the snapshot header row in SQLite.
     */
    fun updatePlayhead(currentIndex: Int, positionMs: Long) {
        val db = database
        if (db != null) {
            db.queueSnapshotDao.updatePlayhead(SNAPSHOT_KEY, currentIndex, positionMs)
            return
        }
        val prefs = testPrefs ?: return
        val existing = load() ?: return
        val updated = TestStoredSnapshot(
            songs = existing.songs,
            index = currentIndex.coerceIn(existing.songs.indices),
            positionMs = positionMs.coerceAtLeast(0L),
        )
        val encoded = runCatchingLogged(TAG, "encodeTestPlayhead") {
            SongJsonCodec.json.encodeToString(TestStoredSnapshot.serializer(), updated)
        }.getOrNull() ?: return
        prefs.edit().putString(KEY_QUEUE, encoded).apply()
    }

    fun clear() {
        clearImmediately()
    }

    fun clearImmediately() {
        database?.queueSnapshotDao?.deleteSnapshot(SNAPSHOT_KEY)
        testPrefs?.edit()?.remove(KEY_QUEUE)?.commit()
    }

    fun load(): Queue? {
        val db = database
        if (db != null) {
            val snap = db.queueSnapshotDao.loadSnapshot(SNAPSHOT_KEY) ?: return null
            if (snap.songs.isEmpty()) return null
            return Queue(
                songs = snap.songs,
                index = snap.index.coerceIn(snap.songs.indices),
                positionMs = snap.positionMs.coerceAtLeast(0L),
            )
        }
        val prefs = testPrefs ?: return null
        val raw = prefs.getString(KEY_QUEUE, null) ?: return null
        val stored = runCatchingLogged(TAG, "decodeTestSnapshot") {
            SongJsonCodec.json.decodeFromString(TestStoredSnapshot.serializer(), raw)
        }.getOrNull() ?: runCatchingLogged(TAG, "decodeLegacySnapshot") {
            SongJsonCodec.json.decodeFromString(LegacyDataMigrator.LegacyStoredQueue.serializer(), raw)
                .let { legacy ->
                    val tracks = legacy.tracks.ifEmpty { legacy.songs }
                    TestStoredSnapshot(
                        songs = tracks.map { it.toSong() },
                        index = legacy.index,
                        positionMs = legacy.positionMs,
                    )
                }
        }.getOrNull() ?: return null
        if (stored.songs.isEmpty()) return null
        return Queue(
            songs = stored.songs,
            index = stored.index.coerceIn(stored.songs.indices),
            positionMs = stored.positionMs.coerceAtLeast(0L),
        )
    }

    @Serializable
    private data class TestStoredSnapshot(
        val songs: List<Song>,
        val index: Int = 0,
        val positionMs: Long = 0L,
    )

    private fun Song.isDeviceFile(): Boolean =
        videoId.startsWith("content://") || videoId.startsWith("file://")

    private const val KEY_QUEUE = "queue"
}
