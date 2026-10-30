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
 * Persists the user's personal queue, index, playhead, and playing state before
 * entering a Listen Together party, and restores it when the user leaves.
 *
 * Backed by [VibeDatabase] (`queue_snapshots` + `queue_items`) using canonical
 * [SongJsonCodec] serialization so the personal queue survives process death or
 * recreation while in a party without losing any [Song] metadata.
 */
object PartyPersonalQueueStash {

    private const val TAG = "PartyPersonalQueueStash"
    private const val SNAPSHOT_KEY = LegacyDataMigrator.SNAPSHOT_PARTY_PERSONAL_STASH

    data class Snapshot(
        val songs: List<Song>,
        val index: Int,
        val positionMs: Long,
        val wasPlaying: Boolean,
    )

    @Volatile
    private var database: VibeDatabase? = null

    @Volatile
    private var testPrefs: SharedPreferences? = null

    fun init(context: Context) {
        database = VibeDatabase.getInstance(context.applicationContext)
        testPrefs = null
    }

    fun initForTesting(prefs: SharedPreferences, db: VibeDatabase? = null) {
        testPrefs = prefs
        database = db
    }

    fun initForTesting(db: VibeDatabase) {
        database = db
        testPrefs = null
    }

    /** Stash directly from a live player. */
    fun stashFromPlayer(player: Player): Boolean {
        if (database == null && testPrefs == null) return false
        if (player.mediaItemCount == 0) {
            val fallback = LastPlayed.load()
            if (fallback != null && fallback.songs.isNotEmpty()) {
                stash(
                    songs = fallback.songs,
                    index = fallback.index,
                    positionMs = fallback.positionMs,
                    wasPlaying = false,
                )
                return true
            }
            return false
        }
        val songs = (0 until player.mediaItemCount).mapNotNull { idx ->
            player.getMediaItemAt(idx).toSong().takeUnless { it.isDeviceFile() }
        }
        if (songs.isEmpty()) return false
        val safeIndex = player.currentMediaItemIndex.coerceIn(0, songs.size - 1)
        stash(
            songs = songs,
            index = safeIndex,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            wasPlaying = player.playWhenReady,
        )
        return true
    }

    fun stash(songs: List<Song>, index: Int, positionMs: Long, wasPlaying: Boolean) {
        if (songs.isEmpty()) return
        val safeIndex = index.coerceIn(songs.indices)
        val safePos = positionMs.coerceAtLeast(0L)
        val db = database
        val prefs = testPrefs
        if (db != null) {
            db.queueSnapshotDao.saveSnapshot(
                snapshotKey = SNAPSHOT_KEY,
                songs = songs,
                currentIndex = safeIndex,
                positionMs = safePos,
                wasPlaying = wasPlaying,
            )
        } else if (prefs != null) {
            val encoded = runCatchingLogged(TAG, "encodeTestStash") {
                SongJsonCodec.json.encodeToString(
                    TestStoredQueue.serializer(),
                    TestStoredQueue(
                        songs = songs,
                        index = safeIndex,
                        positionMs = safePos,
                        wasPlaying = wasPlaying,
                    ),
                )
            }.getOrNull() ?: return
            prefs.edit().putString(KEY_STASH, encoded).commit()
        }
    }

    fun hasStash(): Boolean {
        val db = database
        if (db != null) return db.queueSnapshotDao.hasSnapshot(SNAPSHOT_KEY)
        return testPrefs?.contains(KEY_STASH) == true
    }

    fun load(): Snapshot? {
        val db = database
        if (db != null) {
            val snap = db.queueSnapshotDao.loadSnapshot(SNAPSHOT_KEY) ?: return null
            if (snap.songs.isEmpty()) return null
            return Snapshot(
                songs = snap.songs,
                index = snap.index.coerceIn(snap.songs.indices),
                positionMs = snap.positionMs.coerceAtLeast(0L),
                wasPlaying = snap.wasPlaying,
            )
        }
        val prefs = testPrefs ?: return null
        val raw = prefs.getString(KEY_STASH, null) ?: return null
        val stored = runCatchingLogged(TAG, "decodeTestStash") {
            SongJsonCodec.json.decodeFromString(TestStoredQueue.serializer(), raw)
        }.getOrNull() ?: runCatchingLogged(TAG, "decodeLegacyStash") {
            SongJsonCodec.json.decodeFromString(LegacyDataMigrator.LegacyStoredQueue.serializer(), raw)
                .let { legacy ->
                    TestStoredQueue(
                        songs = legacy.tracks.map { it.toSong() },
                        index = legacy.index,
                        positionMs = legacy.positionMs,
                        wasPlaying = legacy.wasPlaying,
                    )
                }
        }.getOrNull() ?: return null
        if (stored.songs.isEmpty()) return null
        return Snapshot(
            songs = stored.songs,
            index = stored.index.coerceIn(stored.songs.indices),
            positionMs = stored.positionMs.coerceAtLeast(0L),
            wasPlaying = stored.wasPlaying,
        )
    }

    fun clear() {
        database?.queueSnapshotDao?.deleteSnapshot(SNAPSHOT_KEY)
        testPrefs?.edit()?.remove(KEY_STASH)?.commit()
    }

    @Serializable
    private data class TestStoredQueue(
        val songs: List<Song>,
        val index: Int = 0,
        val positionMs: Long = 0L,
        val wasPlaying: Boolean = false,
    )

    private fun Song.isDeviceFile(): Boolean =
        videoId.startsWith("content://") || videoId.startsWith("file://")

    private const val KEY_STASH = "stashed_queue"
}
