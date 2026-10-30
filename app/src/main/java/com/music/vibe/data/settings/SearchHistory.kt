package com.music.vibe.data.settings

import android.content.Context
import android.content.SharedPreferences
import com.music.vibe.data.db.VibeDatabase
import com.music.vibe.data.model.SearchHistoryEntity
import com.music.vibe.data.runCatchingLogged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * What's been searched for lately, kept on this device only.
 *
 * Persisted in [VibeDatabase] (`search_history` table) and mirrored to
 * `vibemusic_settings` so [com.music.vibe.data.stats.Backup] export/import
 * continues to include search history seamlessly.
 */
object SearchHistory {

    private const val TAG = "SearchHistory"
    private const val MAX_ENTRIES = 20

    private lateinit var prefs: SharedPreferences
    @Volatile
    private var database: VibeDatabase? = null

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(SearchHistoryEntity.serializer())

    private val _recent = MutableStateFlow<List<SearchHistoryEntity>>(emptyList())

    /** Most recent first. */
    val recent: StateFlow<List<SearchHistoryEntity>> = _recent.asStateFlow()

    fun init(context: Context) {
        val appContext = context.applicationContext
        prefs = appContext.getSharedPreferences("vibemusic_settings", Context.MODE_PRIVATE)
        database = VibeDatabase.getInstance(appContext)
        val fromDb = database?.searchHistoryDao?.getRecent(MAX_ENTRIES).orEmpty()
        if (fromDb.isNotEmpty()) {
            _recent.value = fromDb
        } else {
            reload()
        }
    }

    /**
     * Re-reads the list off disk (e.g. after [com.music.vibe.data.stats.Backup.importFrom]
     * replaces `vibemusic_settings`) and syncs it into [VibeDatabase].
     */
    fun reload() {
        if (!this::prefs.isInitialized) return
        val fromPrefs = runCatchingLogged(TAG, "reloadSearchHistory") {
            json.decodeFromString(serializer, prefs.getString(KEY_HISTORY, null) ?: "[]")
        }.getOrDefault(emptyList()).take(MAX_ENTRIES)
        _recent.value = fromPrefs
        database?.searchHistoryDao?.replaceAll(fromPrefs)
    }

    /** Serializes current search history for [com.music.vibe.data.stats.Backup] export. */
    fun exportJson(): String = json.encodeToString(serializer, _recent.value)

    /** Restores search history from a [com.music.vibe.data.stats.Backup] payload into memory and [VibeDatabase]. */
    fun importJson(rawJson: String?) {
        if (rawJson.isNullOrBlank()) {
            save(emptyList())
            return
        }
        val parsed = runCatchingLogged(TAG, "importJsonSearchHistory") {
            json.decodeFromString(serializer, rawJson)
        }.getOrDefault(emptyList()).take(MAX_ENTRIES)
        save(parsed)
    }

    /**
     * Records [entity], or moves it back to the top if it's already there.
     * Matching is by id so re-tapping an existing entity updates its timestamp.
     */
    fun record(entity: SearchHistoryEntity) {
        val deduped = _recent.value.filterNot { it.id.equals(entity.id, ignoreCase = true) }
        save((listOf(entity) + deduped).take(MAX_ENTRIES))
    }

    fun remove(id: String) {
        save(_recent.value.filterNot { it.id.equals(id, ignoreCase = true) })
    }

    fun clear() = save(emptyList())

    private fun save(value: List<SearchHistoryEntity>) {
        _recent.value = value
        val db = database
        db?.searchHistoryDao?.replaceAll(value)
        val skipLegacyWrite = db != null && com.music.vibe.data.db.LegacyDataMigrator.isV1MigrationDone
        if (!skipLegacyWrite && this::prefs.isInitialized) {
            prefs.edit().putString(KEY_HISTORY, json.encodeToString(serializer, value)).apply()
        }
    }

    private const val KEY_HISTORY = "search_history"
}
