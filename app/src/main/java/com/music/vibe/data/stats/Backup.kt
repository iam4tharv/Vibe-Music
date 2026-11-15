package com.music.vibe.data.stats

import android.content.Context
import android.net.Uri
import com.music.vibe.BuildConfig
import com.music.vibe.data.settings.AppSettings
import com.music.vibe.data.settings.SearchHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The whole of what this app knows about you, as one JSON file you own.
 *
 * Two errands, and they are the same errand: moving to a new phone, and being
 * able to see what is being kept. Everything Replay counts lives only on this
 * device, which is the point of it — and the flip side of that is that a factory
 * reset takes it with no way back, so there has to be a way out.
 *
 * ## What is in it
 *
 * Every preference except credentials (see [AppSettings.exportPrefs]), and every
 * month of listening. Not the audio cache, the downloads or the artwork: those
 * are megabytes that re-fetch themselves, and a backup that is mostly cache is
 * one nobody keeps.
 *
 * ## Typed values rather than a JSON object
 *
 * Preferences come off Android as `Map<String, Any?>` and go back the same way,
 * where the *type* decides which `put` is called. Written as plain JSON, `0.5`
 * comes back a Double and lands in a Float preference as a class-cast crash the
 * first time it is read — weeks later, in a settings screen, with nothing
 * pointing at the import. So each value carries its type and is parsed back into
 * exactly the type it left as, and anything unrecognised is skipped rather than
 * guessed at.
 */
object Backup {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    /** A suggested filename, dated so successive exports don't collide. */
    fun suggestedName(): String =
        "vibemusic-backup-${DateTimeFormatter.ofPattern("yyyy-MM-dd").format(
            Instant.now().atZone(ZoneId.systemDefault()),
        )}.json"

    /**
     * Writes a backup to [target], a document the user picked.
     *
     * Through the content resolver rather than a [java.io.File] because the
     * destination is wherever they chose — Drive, a USB stick, a folder this app
     * has no path to and no permission for. The picker grants access to that one
     * document and nothing else, which is the correct amount.
     */
    suspend fun exportTo(context: Context, target: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val buckets = ListeningStats.exportAll()
            val mergedPrefs = LinkedHashMap<String, Any?>().apply {
                putAll(AppSettings.exportPrefs())
                put("search_history", SearchHistory.exportJson())
                com.music.vibe.data.spotify.LocalPlaylistStore.exportJson()?.let {
                    put("local_playlists_json", it)
                }
                putAll(com.music.vibe.download.Downloads.exportForBackup())
            }
            val authData = AppSettings.exportAuthBackup().ifEmpty {
                com.music.vibe.di.AppContainer.get(context).authStore.exportBackupData()
            }
            val file = BackupFile(
                versionName = BuildConfig.VERSION_NAME,
                exportedAt = Instant.now().toString(),
                settings = mergedPrefs.mapNotNull { (key, value) ->
                    PrefValue.of(value)?.let { key to it }
                }.toMap(),
                listening = buckets,
                auth = authData,
            )
            val text = json.encodeToString(BackupFile.serializer(), file)
            context.contentResolver.openOutputStream(target, "wt")
                ?.use { it.write(text.toByteArray()) }
                ?: error("Couldn't open that file for writing")
            buckets.size
        }
    }

    /**
     * Reads [source] and replaces this device's settings, accounts, and listening with it.
     *
     * Validated before anything is written: a file that isn't one of ours, or is
     * from a schema this build can't read, is refused whole. Accepts both
     * current `vibemusic` backups and legacy `bitchord` backups.
     */
    suspend fun importFrom(context: Context, source: Uri): Result<Summary> = withContext(Dispatchers.IO) {
        runCatching {
            val text = context.contentResolver.openInputStream(source)
                ?.use { it.readBytes().decodeToString() }
                ?: error("Couldn't open that file")
            val file = runCatching { json.decodeFromString(BackupFile.serializer(), text) }
                .getOrElse { error("That doesn't look like a Vibe Music backup") }
            require(file.app in ACCEPTED_APP_TAGS) { "That backup is from another app" }
            require(file.version in 1..SCHEMA_VERSION) {
                "That backup was written by a newer version of Vibe Music"
            }

            ListeningStats.importAll(file.listening)
            val decodedSettings = file.settings
                .filterKeys { it != "download_folder_uri" }
                .mapValues { (key, prefValue) ->
                    val raw = prefValue.decoded()
                    if (key == "downloaded_tracks" && raw is String) {
                        filterSafTreeTrackUris(raw)
                    } else {
                        raw
                    }
                }
            AppSettings.importPrefs(
                decodedSettings.filterKeys { it != "search_history" && it != "local_playlists_json" },
            )
            if (decodedSettings.containsKey("search_history")) {
                SearchHistory.importJson(decodedSettings["search_history"] as? String)
            } else {
                SearchHistory.reload()
            }
            if (decodedSettings.containsKey("local_playlists_json")) {
                com.music.vibe.data.spotify.LocalPlaylistStore.importJson(
                    decodedSettings["local_playlists_json"] as? String,
                )
            }
            com.music.vibe.download.Downloads.importFromBackup(decodedSettings)
            if (file.auth.isNotEmpty() || file.version >= 2) {
                AppSettings.importAuthBackup(file.auth, context)
            }
            val playlists = com.music.vibe.data.spotify.LocalPlaylistStore.playlists.value
            val playlistTracksCount = playlists.sumOf { it.songs.size }
            val savedDownloadsCount = com.music.vibe.download.Downloads.saved.value.size
            val restoredAccounts = buildList {
                val ytSignedIn = runCatching {
                    com.music.vibe.di.AppContainer.get(context).authStore.isSignedIn
                }.getOrDefault(false) || !com.music.vibe.data.innertube.Innertube.cookie.isNullOrBlank()
                if (ytSignedIn) add("YouTube Music")
                if (AppSettings.discordToken.value.isNotBlank()) add("Discord")
                if (AppSettings.lastfmSessionKey.value.isNotBlank()) add("Last.fm")
                if (AppSettings.listenBrainzToken.value.isNotBlank()) add("ListenBrainz")
                if (AppSettings.spotifySpdcToken.value.isNotBlank()) add("Spotify")
            }
            Summary(
                months = file.listening.size,
                settings = file.settings.size,
                from = file.versionName,
                at = file.exportedAt,
                playlistsCount = playlists.size,
                tracksCount = playlistTracksCount + savedDownloadsCount,
                restoredAccounts = restoredAccounts,
            )
        }
    }

    /**
     * Filters out device-specific SAF `/tree/` URIs (`content://.../tree/...`)
     * from a serialized `downloaded_tracks` map while leaving `MediaStore`
     * (`content://media/...`) and `file://` URIs untouched.
     */
    internal fun filterSafTreeTrackUris(rawJson: String): String = runCatching {
        val mapSerializer = MapSerializer(
            String.serializer(),
            String.serializer(),
        )
        val parsed = json.decodeFromString(mapSerializer, rawJson)
        val filtered = parsed.filterValues { uri ->
            !(uri.startsWith("content://") && uri.contains("/tree/"))
        }
        json.encodeToString(mapSerializer, filtered)
    }.getOrDefault(rawJson)

    /** What an import turned out to contain, for the summary card shown afterwards. */
    data class Summary(
        val months: Int,
        val settings: Int,
        val from: String,
        val at: String,
        val playlistsCount: Int = 0,
        val tracksCount: Int = 0,
        val restoredAccounts: List<String> = emptyList(),
    )

    const val APP_TAG = "vibemusic"
    const val LEGACY_APP_TAG = "bitchord"
    internal val ACCEPTED_APP_TAGS = setOf(APP_TAG, LEGACY_APP_TAG)

    /**
     * Bump when the shape below stops being readable by an older build. A file
     * from a *newer* schema is refused rather than partially read; one from an
     * older schema is read as-is, since every field has a default.
     */
    const val SCHEMA_VERSION = 2

    @Serializable
    data class BackupFile(
        val app: String = APP_TAG,
        val version: Int = SCHEMA_VERSION,
        /** Which build wrote it — for the reader, not for any logic here. */
        val versionName: String = "",
        val exportedAt: String = "",
        val settings: Map<String, PrefValue> = emptyMap(),
        val listening: List<StoredBucket> = emptyList(),
        val auth: Map<String, String> = emptyMap(),
    )

    /** One preference, with the type it has to be restored as. */
    @Serializable
    data class PrefValue(
        val type: String,
        val value: String? = null,
        val values: List<String> = emptyList(),
    ) {
        fun decoded(): Any? = when (type) {
            BOOLEAN -> value?.toBooleanStrictOrNull()
            INT -> value?.toIntOrNull()
            LONG -> value?.toLongOrNull()
            FLOAT -> value?.toFloatOrNull()
            STRING -> value
            STRING_SET -> values.toSet()
            else -> null
        }

        companion object {
            /**
             * Numbers go out as strings on purpose. JSON has one number type and
             * every parser picks its own Kotlin type back off it, which is
             * exactly the ambiguity this whole class exists to remove.
             */
            fun of(value: Any?): PrefValue? = when (value) {
                is Boolean -> PrefValue(BOOLEAN, value.toString())
                is Int -> PrefValue(INT, value.toString())
                is Long -> PrefValue(LONG, value.toString())
                is Float -> PrefValue(FLOAT, value.toString())
                is String -> PrefValue(STRING, value)
                is Set<*> -> PrefValue(STRING_SET, values = value.filterIsInstance<String>())
                else -> null
            }

            private const val BOOLEAN = "bool"
            private const val INT = "int"
            private const val LONG = "long"
            private const val FLOAT = "float"
            private const val STRING = "string"
            private const val STRING_SET = "stringSet"
        }
    }
}
