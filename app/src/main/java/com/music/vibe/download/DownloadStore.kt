package com.music.vibe.download

import android.app.usage.StorageStatsManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.music.vibe.data.DebugLog as Log
import com.music.vibe.data.model.Song
import com.music.vibe.data.settings.AppSettings
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Collections
import java.util.Locale
import java.util.UUID

/**
 * Where a downloaded track goes, and how it gets there.
 *
 * Supports three destinations:
 *  1. **App storage (private)**: `getExternalFilesDir(DIRECTORY_MUSIC)` with `.nomedia`.
 *  2. **Default Music folder (`Music/Vibe Music`)**: `MediaStore.Audio` on API 29+ or
 *     direct file path on API 26–28.
 *  3. **Custom SAF folder (`AppSettings.downloadFolderUri`)**: written through a
 *     cache staging file so [MediaTagger] can tag with random access, then streamed
 *     into `.vibe-part-<name>` and atomically renamed to `<name>` via
 *     [DocumentsContract.renameDocument], returning a tree-based document URI built
 *     with [DocumentsContract.buildDocumentUriUsingTree].
 */
object DownloadStore {

    private const val TAG = "VibeMusic"

    /** The subfolder of Music that everything lands in by default. */
    const val FOLDER = "Vibe Music"

    /** Prefix for in-flight or interrupted SAF writes. */
    const val PART_PREFIX = ".vibe-part-"

    /** Prefix for cache staging files prior to tagging and SAF commit. */
    const val CACHE_STAGE_PREFIX = ".download-"

    /** Stale part threshold (5 minutes). */
    const val STALE_PART_AGE_MS = 5 * 60 * 1_000L

    private const val COPY_BUFFER_SIZE = 64 * 1024

    private val relativePath = "${Environment.DIRECTORY_MUSIC ?: "Music"}/$FOLDER"

    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /**
     * 32-entry striped mutex array keyed by normalized stem hash so a regular download
     * and an export job cannot write the same track stem concurrently.
     */
    private val stemLocks = Array(32) { Mutex() }

    fun stemMutexFor(nameOrStem: String): Mutex {
        val key = normalizedStem(nameOrStem, stripCollisionSuffix = true)
        val index = (key.hashCode() and 0x7fffffff) % stemLocks.size
        return stemLocks[index]
    }

    /**
     * Thread-safe set of `.vibe-part-<name>` filenames currently being written by any
     * active download or export worker. Leftover cleanup never deletes an entry in this set.
     */
    val activePartNames: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /**
     * Whether saving needs `WRITE_EXTERNAL_STORAGE` asked for at runtime.
     *
     * Only below API 29 when exporting to the default public Music folder without a
     * custom SAF tree URI.
     */
    fun needsLegacyPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            AppSettings.downloadFolderUri.value.isBlank()

    // ---- Naming & Stem Normalization ----------------------------------------

    /**
     * What the file is called: `Artist - Title.ext`.
     */
    fun fileNameFor(song: Song, extension: String): String {
        val artist = sanitise(song.artist)
        val title = sanitise(song.title)
        val stem = when {
            artist.isEmpty() -> title
            title.isEmpty() -> artist
            else -> "$artist - $title"
        }.ifEmpty { song.videoId }
        return "${stem.take(MAX_STEM_CHARS).trimEnd()}.$extension"
    }

    /**
     * Normalized stem for a filename or `Artist - Title` string.
     *
     * @param stripCollisionSuffix when false (Pass 1), preserves `(1)`, `(2)` so real
     *   titles like `Song (2)` match accurately. When true (Pass 2), strips trailing
     *   ` (1)`, ` (2)` SAF collision suffixes.
     */
    fun normalizedStem(rawName: String, stripCollisionSuffix: Boolean = false): String {
        val withoutExt = stripKnownAudioExtension(rawName.trim())
        val base = if (stripCollisionSuffix) {
            withoutExt.replace(COLLISION_SUFFIX, "")
        } else {
            withoutExt
        }
        return sanitise(base).lowercase(Locale.ROOT).take(MAX_STEM_CHARS).trimEnd()
    }

    fun stripKnownAudioExtension(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        if (dot <= 0) return fileName
        val ext = fileName.substring(dot + 1).lowercase(Locale.ROOT)
        return if (ext in KNOWN_AUDIO_EXTENSIONS) fileName.substring(0, dot) else fileName
    }

    /**
     * Everything a FAT32 volume, the media store or a shell would each object
     * to for its own reasons, plus the whitespace that survives them.
     */
    internal fun sanitise(raw: String): String = raw
        .replace(ILLEGAL, " ")
        .replace(WHITESPACE, " ")
        .trim()
        .trim('.')

    private val ILLEGAL = Regex("""[\\/:*?"<>|\x00-\x1F]""")
    private val WHITESPACE = Regex("""\s+""")
    private val COLLISION_SUFFIX = Regex("""\s+\(\d+\)$""")

    private val KNOWN_AUDIO_EXTENSIONS = setOf(
        "mp3", "m4a", "mp4", "flac", "wav", "ogg", "opus", "aac", "webm", "3gp", "alac", "dsf", "dff",
    )

    /** Long enough for anything real, short of the 255-byte filename ceiling. */
    private const val MAX_STEM_CHARS = 120

    /** What a file of some codec is called and what the store is told it is. */
    class Storable(val extension: String, val mimeType: String)

    fun storable(codec: String?): Storable? = when (codec?.lowercase(Locale.ROOT)?.trim()) {
        "flac", "x-flac" -> Storable("flac", "audio/flac")
        "wav", "x-wav", "wave" -> Storable("wav", "audio/x-wav")
        "alac", "m4a", "mp4", "eac3-joc", "ec3-joc", "dolby-atmos" -> Storable("m4a", "audio/mp4")
        else -> null
    }

    /**
     * Maps a filename or extension to its real audio MIME type so SAF providers
     * never append `.bin` or misclassify the file.
     */
    fun realAudioMimeType(fileNameOrExt: String, fallbackMime: String? = null): String {
        val ext = fileNameOrExt.substringAfterLast('.', fileNameOrExt).lowercase(Locale.ROOT)
        return when (ext) {
            "m4a", "mp4", "alac" -> "audio/mp4"
            "flac" -> "audio/flac"
            "webm" -> "audio/webm"
            "opus", "ogg" -> "audio/ogg"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/x-wav"
            "aac" -> "audio/aac"
            else -> fallbackMime?.takeIf { it.startsWith("audio/") } ?: "audio/mp4"
        }
    }

    // ---- SAF Tree & URI Helpers ---------------------------------------------

    /**
     * Converts a plain or tree document URI into a canonical tree-based document URI
     * (`content://<authority>/tree/<treeDocId>/document/<docId>`), ensuring the parent
     * tree ID is embedded in every URI stored in `Downloads.saved`.
     */
    fun toTreeDocumentUri(treeUri: Uri, documentUri: Uri): Uri {
        val docId = DocumentsContract.getDocumentId(documentUri)
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
    }

    fun toTreeDocumentUri(treeUri: Uri, documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    fun isSafTreeUri(uri: Uri): Boolean =
        uri.scheme == "content" && uri.path?.contains("/tree/") == true

    fun extractTreeDocumentId(uri: Uri): String? = runCatching {
        if (!isSafTreeUri(uri)) null else DocumentsContract.getTreeDocumentId(uri)
    }.getOrNull()

    fun extractDocumentId(uri: Uri): String? = runCatching {
        DocumentsContract.getDocumentId(uri)
    }.getOrNull()

    fun buildTreeUriFromDocument(docUri: Uri): Uri? = runCatching {
        val authority = docUri.authority ?: return@runCatching null
        val treeId = extractTreeDocumentId(docUri) ?: return@runCatching null
        DocumentsContract.buildTreeDocumentUri(authority, treeId)
    }.getOrNull()

    /**
     * Returns true only for local storage providers (`com.android.externalstorage.documents`),
     * rejecting cloud providers like Google Drive, Dropbox, OneDrive, or Nextcloud.
     */
    fun isLocalSafProvider(treeUri: Uri): Boolean =
        treeUri.authority == EXTERNAL_STORAGE_AUTHORITY

    /**
     * Returns the currently active custom SAF tree URI if configured and mounted, or null.
     */
    fun activeCustomTreeUri(): Uri? {
        if (!AppSettings.exportDownloads.value) return null
        if (AppSettings.downloadFolderUnavailable.value) return null
        val raw = AppSettings.downloadFolderUri.value
        if (raw.isBlank()) return null
        return runCatching { Uri.parse(raw) }.getOrNull()
    }

    /**
     * Checks whether [treeUriString] points to the exact same folder as the default
     * `Music/Vibe Music` directory (`primary:Music/Vibe Music`).
     */
    fun isCustomFolderSameAsDefaultMusic(treeUriString: String): Boolean {
        if (treeUriString.isBlank()) return false
        val expected = "primary:${relativePath.lowercase(Locale.ROOT)}"
        val fromFramework = runCatching {
            val uri = Uri.parse(treeUriString) ?: return@runCatching null
            val auth = uri.authority ?: return@runCatching null
            if (auth != EXTERNAL_STORAGE_AUTHORITY) return false
            val treeDocId = DocumentsContract.getTreeDocumentId(uri) ?: return@runCatching null
            treeDocId.replace('\\', '/').trimEnd('/').lowercase(Locale.ROOT) == expected
        }.getOrNull()
        if (fromFramework != null) return fromFramework

        // Pure-JVM fallback for unit tests where android.net.Uri is not mocked
        return runCatching {
            val jUri = java.net.URI(treeUriString)
            if (jUri.authority != EXTERNAL_STORAGE_AUTHORITY) return@runCatching false
            val rawPath = jUri.rawPath ?: return@runCatching false
            val encodedTreeId = rawPath.substringAfter("/tree/", "").substringBefore("/")
            if (encodedTreeId.isEmpty()) return@runCatching false
            val decoded = java.net.URLDecoder.decode(encodedTreeId, "UTF-8")
                .replace('\\', '/')
                .trimEnd('/')
                .lowercase(Locale.ROOT)
            decoded == expected
        }.getOrDefault(false)
    }

    /**
     * Checks whether two SAF tree/document IDs on the same authority overlap
     * (either identical or one is a parent directory of the other).
     */
    fun treeOverlaps(idA: String, idB: String): Boolean {
        val normA = idA.replace('\\', '/').trimEnd('/')
        val normB = idB.replace('\\', '/').trimEnd('/')
        return normA == normB ||
            normA.startsWith("$normB/") ||
            normB.startsWith("$normA/")
    }

    // ---- Single-Query SAF Index & ContentObserver ---------------------------

    data class SafDocEntry(
        val documentId: String,
        val uri: Uri,
        val displayName: String,
        val normalizedStem: String,
        val strippedStem: String,
        val size: Long,
        val lastModified: Long,
        val flags: Int,
    )

    internal class SafTreeIndex(
        val treeUriString: String,
        val treeDocId: String,
        val byUriString: Map<String, SafDocEntry>,
        val byDocId: Map<String, SafDocEntry>,
        val byDisplayNameLower: Map<String, SafDocEntry>,
        val byExactStem: Map<String, List<SafDocEntry>>,
        val byStrippedStem: Map<String, List<SafDocEntry>>,
        val partEntries: List<SafDocEntry>,
    )

    private val indexLock = Any()

    @Volatile
    private var cachedSafIndex: SafTreeIndex? = null

    @Volatile
    private var safIndexDirty: Boolean = true

    private var observedChildrenUri: Uri? = null
    private var safContentObserver: ContentObserver? = null

    fun markSafIndexDirty() {
        safIndexDirty = true
    }

    private fun ensureObserverRegistered(context: Context, childrenUri: Uri) {
        synchronized(indexLock) {
            if (observedChildrenUri == childrenUri && safContentObserver != null) return
            val resolver = context.applicationContext.contentResolver
            safContentObserver?.let { old ->
                runCatching { resolver.unregisterContentObserver(old) }
            }
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    safIndexDirty = true
                }

                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    safIndexDirty = true
                }
            }
            runCatching {
                resolver.registerContentObserver(childrenUri, true, observer)
                safContentObserver = observer
                observedChildrenUri = childrenUri
            }.onFailure {
                safContentObserver = null
                observedChildrenUri = null
            }
        }
    }

    internal fun getSafIndex(context: Context, treeUri: Uri, forceRefresh: Boolean = false): SafTreeIndex? {
        val treeUriStr = treeUri.toString()
        val current = cachedSafIndex
        if (!forceRefresh && !safIndexDirty && current != null && current.treeUriString == treeUriStr) {
            return current
        }
        return synchronized(indexLock) {
            val recheck = cachedSafIndex
            if (!forceRefresh && !safIndexDirty && recheck != null && recheck.treeUriString == treeUriStr) {
                return@synchronized recheck
            }
            querySafTreeIndex(context, treeUri)?.also { built ->
                cachedSafIndex = built
                safIndexDirty = false
            }
        }
    }

    private fun querySafTreeIndex(context: Context, treeUri: Uri): SafTreeIndex? = runCatching {
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
        ensureObserverRegistered(context, childrenUri)

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )

        val entries = mutableListOf<SafDocEntry>()
        val parts = mutableListOf<SafDocEntry>()

        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val flagsCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
            val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)

            while (cursor.moveToNext()) {
                if (idCol < 0 || nameCol < 0) continue
                val docId = cursor.getString(idCol) ?: continue
                val displayName = cursor.getString(nameCol) ?: continue
                val mime = if (mimeCol >= 0 && !cursor.isNull(mimeCol)) cursor.getString(mimeCol) else null
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue

                val size = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else 0L
                val lastMod = if (modCol >= 0 && !cursor.isNull(modCol)) cursor.getLong(modCol) else 0L
                val flags = if (flagsCol >= 0 && !cursor.isNull(flagsCol)) cursor.getInt(flagsCol) else 0
                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

                val entry = SafDocEntry(
                    documentId = docId,
                    uri = docUri,
                    displayName = displayName,
                    normalizedStem = normalizedStem(displayName, stripCollisionSuffix = false),
                    strippedStem = normalizedStem(displayName, stripCollisionSuffix = true),
                    size = size,
                    lastModified = lastMod,
                    flags = flags,
                )
                if (displayName.startsWith(PART_PREFIX)) {
                    parts.add(entry)
                } else if (!displayName.startsWith(".")) {
                    entries.add(entry)
                }
            }
        } ?: return@runCatching null

        SafTreeIndex(
            treeUriString = treeUri.toString(),
            treeDocId = treeDocId,
            byUriString = entries.associateBy { it.uri.toString() },
            byDocId = entries.associateBy { it.documentId },
            byDisplayNameLower = entries.associateBy { it.displayName.lowercase(Locale.ROOT) },
            byExactStem = entries.groupBy { it.normalizedStem },
            byStrippedStem = entries.groupBy { it.strippedStem },
            partEntries = parts,
        )
    }.onFailure {
        Log.w(TAG, "SAF tree index query failed for $treeUri: ${it.message}")
    }.getOrNull()

    /**
     * Returns all non-part files in the currently active custom SAF folder using a single
     * cached `ContentResolver.query`.
     */
    fun getActiveSafEntries(context: Context, forceRefresh: Boolean = false): List<SafDocEntry> {
        val treeUri = activeCustomTreeUri() ?: return emptyList()
        return getSafIndex(context, treeUri, forceRefresh)?.byUriString?.values?.toList().orEmpty()
    }

    /**
     * Looks up a file in the given SAF tree by exact display name (case-insensitive).
     */
    fun findSafEntryByDisplayName(
        context: Context,
        treeUri: Uri,
        displayName: String,
        forceRefresh: Boolean = false,
    ): SafDocEntry? {
        val index = getSafIndex(context, treeUri, forceRefresh) ?: return null
        return index.byDisplayNameLower[displayName.lowercase(Locale.ROOT)]
    }

    /**
     * Two-pass stem lookup in the active SAF folder:
     * Pass 1 checks exact normalized stem (preserving `(1)`, `(2)`).
     * Pass 2 checks collision-stripped stem (`Song (1)` -> `song`).
     */
    fun findInActiveSafByStem(context: Context, rawStemOrName: String): List<SafDocEntry> {
        val treeUri = activeCustomTreeUri() ?: return emptyList()
        val index = getSafIndex(context, treeUri) ?: return emptyList()
        val exactKey = normalizedStem(rawStemOrName, stripCollisionSuffix = false)
        val exactHits = index.byExactStem[exactKey].orEmpty()
        if (exactHits.isNotEmpty()) return exactHits
        val strippedKey = normalizedStem(rawStemOrName, stripCollisionSuffix = true)
        return index.byStrippedStem[strippedKey].orEmpty()
    }

    // ---- Lookup -------------------------------------------------------------

    /**
     * Ensures a .nomedia file exists in [folder] to prevent MediaScanner and third-party
     * apps (galleries, video players) from cluttering users' media libraries.
     */
    fun ensureNoMedia(folder: File) {
        runCatching {
            if (!folder.exists()) folder.mkdirs()
            val nomedia = File(folder, ".nomedia")
            if (!nomedia.exists()) nomedia.createNewFile()
        }
    }

    internal fun privateDirectory(context: Context): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: File(context.filesDir, "downloads")
        ensureNoMedia(dir)
        return dir
    }

    private fun privateFile(context: Context, name: String): File =
        File(privateDirectory(context), name)

    /**
     * The uri of a file already saved under this name, or null.
     */
    fun existing(context: Context, name: String): Uri? {
        if (!AppSettings.exportDownloads.value) {
            val primary = privateFile(context, name)
            if (primary.exists()) return Uri.fromFile(primary)
            val legacyInternal = File(File(context.filesDir, "downloads"), name)
            if (legacyInternal.exists()) return Uri.fromFile(legacyInternal)
            val exactStem = normalizedStem(name, stripCollisionSuffix = false)
            val match = privateDirectory(context).listFiles()?.firstOrNull { file ->
                file.isFile && !file.name.startsWith(".") &&
                    normalizedStem(file.name, stripCollisionSuffix = false) == exactStem
            }
            return match?.let(Uri::fromFile)
        }
        val customTree = activeCustomTreeUri()
        if (customTree != null) {
            val index = getSafIndex(context, customTree)
            if (index != null) {
                index.byDisplayNameLower[name.lowercase(Locale.ROOT)]?.let { return it.uri }
                val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                val stem = normalizedStem(name, stripCollisionSuffix = false)
                index.byExactStem[stem]?.firstOrNull { entry ->
                    entry.displayName.substringAfterLast('.', "").lowercase(Locale.ROOT) == ext
                }?.let { return it.uri }
                return null
            }
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mediaStoreEntry(context, name)
        } else {
            legacyFile(name).takeIf { it.exists() }?.let(Uri::fromFile)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun mediaStoreEntry(context: Context, name: String): Uri? = runCatching {
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf(name, "%$FOLDER%"),
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.buildUpon()
                .appendPath(cursor.getLong(0).toString())
                .build()
        }
    }.onFailure { Log.w(TAG, "media store lookup failed for $name: ${it.message}") }.getOrNull()

    /**
     * Two-pass stem lookup in default `Music/Vibe Music` or private folder for untracked
     * files when no custom SAF folder is active.
     */
    fun findExistingByStem(context: Context, rawStemOrName: String): List<Pair<Uri, String>> {
        val customTree = activeCustomTreeUri()
        if (customTree != null) {
            return findInActiveSafByStem(context, rawStemOrName).map { it.uri to it.displayName }
        }
        val exactKey = normalizedStem(rawStemOrName, stripCollisionSuffix = false)
        val strippedKey = normalizedStem(rawStemOrName, stripCollisionSuffix = true)

        if (!AppSettings.exportDownloads.value) {
            val files = privateDirectory(context).listFiles()?.filter {
                it.isFile && !it.name.startsWith(".")
            }.orEmpty()
            val pass1 = files.filter { normalizedStem(it.name, false) == exactKey }
            val chosen = pass1.ifEmpty { files.filter { normalizedStem(it.name, true) == strippedKey } }
            return chosen.map { Uri.fromFile(it) to it.name }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val allInFolder = mutableListOf<Pair<Uri, String>>()
            runCatching {
                context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                    arrayOf("%$FOLDER%"),
                    null,
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val disp = cursor.getString(nameCol) ?: continue
                        val u = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                        allInFolder.add(u to disp)
                    }
                }
            }
            val pass1 = allInFolder.filter { normalizedStem(it.second, false) == exactKey }
            return pass1.ifEmpty { allInFolder.filter { normalizedStem(it.second, true) == strippedKey } }
        } else {
            val folder = legacyFile("dummy").parentFile ?: return emptyList()
            val files = folder.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }.orEmpty()
            val pass1 = files.filter { normalizedStem(it.name, false) == exactKey }
            val chosen = pass1.ifEmpty { files.filter { normalizedStem(it.name, true) == strippedKey } }
            return chosen.map { Uri.fromFile(it) to it.name }
        }
    }

    /**
     * Whether [uri] still names a file that is there.
     *
     * Tree-aware:
     *  - For URIs belonging to the currently active custom SAF tree, checks the in-memory
     *    SAF index in O(1) without a binder round trip per track.
     *  - For URIs belonging to any OTHER SAF tree (e.g., Folder A after switching to Folder B),
     *    performs a single lightweight `ContentResolver.query` on `uri` so tracks in Folder A
     *    are never falsely pruned.
     *  - For `file://` and `MediaStore` URIs, checks `File.exists()` or `openFileDescriptor`.
     */
    fun exists(context: Context, uri: Uri): Boolean = runCatching {
        if (uri.scheme == "file") return uri.path?.let { File(it).exists() } == true
        if (isSafTreeUri(uri)) {
            val activeTree = activeCustomTreeUri()
            val uriTreeId = extractTreeDocumentId(uri)
            val activeTreeId = activeTree?.let(::extractTreeDocumentId)
            if (activeTree != null && uri.authority == activeTree.authority &&
                uriTreeId != null && uriTreeId == activeTreeId
            ) {
                val index = getSafIndex(context, activeTree)
                if (index != null) {
                    val docId = extractDocumentId(uri)
                    return if (docId != null) {
                        index.byDocId.containsKey(docId)
                    } else {
                        index.byUriString.containsKey(uri.toString())
                    }
                }
            }
            // Different SAF tree (or index unavailable): lightweight single-URI query
            return context.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null,
            )?.use { it.moveToFirst() } == true
        }
        context.contentResolver.openFileDescriptor(uri, "r")?.use { true } == true
    }.getOrDefault(false)

    fun delete(context: Context, uri: Uri): Boolean = runCatching {
        if (uri.scheme == "file") {
            uri.path?.let { path ->
                val file = File(path)
                if (file.name == "playlist.m3u8" && file.parentFile?.parentFile?.name == "offline-hls") {
                    file.parentFile?.deleteRecursively() == true
                } else {
                    file.delete()
                }
            } == true
        } else if (DocumentsContract.isDocumentUri(context, uri)) {
            val deleted = DocumentsContract.deleteDocument(context.contentResolver, uri)
            if (deleted) markSafIndexDirty()
            deleted
        } else {
            context.contentResolver.delete(uri, null, null) > 0
        }
    }.onFailure { Log.w(TAG, "could not delete $uri: ${it.message}") }.getOrDefault(false)

    // ---- Unified `.vibe-part-` SAF Writer -----------------------------------

    /**
     * Writes a file into [treeUri] using a temporary `.vibe-part-<finalFileName>` document
     * followed by [DocumentsContract.renameDocument] to `<finalFileName>`, then rebuilds
     * the tree-based document URI from the returned renamed URI via
     * [DocumentsContract.buildDocumentUriUsingTree].
     *
     * Protects in-flight part names via [activePartNames] so concurrent leftover cleanup
     * never deletes an active download or export.
     */
    fun writeSafDocumentWithPart(
        context: Context,
        treeUri: Uri,
        finalFileName: String,
        mimeType: String,
        expectedSize: Long = -1L,
        writeContent: (OutputStream) -> Long,
    ): Uri {
        val resolver = context.contentResolver
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
        val effectiveMime = realAudioMimeType(finalFileName, mimeType)
        val partName = "$PART_PREFIX$finalFileName"

        activePartNames.add(partName)
        var partDocUri: Uri? = null
        try {
            // If an inactive leftover with the exact partName exists, delete it first
            findSafEntryByDisplayNameForPart(context, treeUri, partName)?.let { stale ->
                runCatching { DocumentsContract.deleteDocument(resolver, stale.uri) }
            }

            val createdPart = DocumentsContract.createDocument(
                resolver,
                parentDocUri,
                effectiveMime,
                partName,
            ) ?: error("Could not create temporary file in download folder")

            partDocUri = toTreeDocumentUri(treeUri, createdPart)

            val writtenBytes = resolver.openOutputStream(partDocUri, "wt")?.use { out ->
                val count = writeContent(out)
                out.flush()
                count
            } ?: error("Could not open temporary file for writing")

            if (expectedSize > 0L && writtenBytes != expectedSize) {
                error("Incomplete write ($writtenBytes of $expectedSize bytes)")
            }

            // Try atomic renameDocument -> rebuild tree URI from returned renamedUri
            val renamed = try {
                DocumentsContract.renameDocument(resolver, partDocUri, finalFileName)
            } catch (_: UnsupportedOperationException) {
                null
            } catch (_: Exception) {
                null
            }

            if (renamed != null) {
                partDocUri = null
                val finalTreeUri = toTreeDocumentUri(treeUri, renamed)
                markSafIndexDirty()
                return finalTreeUri
            }

            // Fallback for providers that do not support renameDocument:
            // delete the .vibe-part- document and write directly to finalFileName.
            runCatching { DocumentsContract.deleteDocument(resolver, partDocUri) }
            partDocUri = null

            val directCreated = DocumentsContract.createDocument(
                resolver,
                parentDocUri,
                effectiveMime,
                finalFileName,
            ) ?: error("Could not create $finalFileName in download folder")
            val directTreeUri = toTreeDocumentUri(treeUri, directCreated)
            try {
                val directWritten = resolver.openOutputStream(directTreeUri, "wt")?.use { out ->
                    val count = writeContent(out)
                    out.flush()
                    count
                } ?: error("Could not open $finalFileName for writing")
                if (expectedSize > 0L && directWritten != expectedSize) {
                    error("Incomplete write ($directWritten of $expectedSize bytes)")
                }
                markSafIndexDirty()
                return directTreeUri
            } catch (t: Throwable) {
                runCatching { DocumentsContract.deleteDocument(resolver, directTreeUri) }
                throw t
            }
        } catch (t: Throwable) {
            partDocUri?.let { uri ->
                runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            }
            markSafIndexDirty()
            throw t
        } finally {
            activePartNames.remove(partName)
        }
    }

    private fun findSafEntryByDisplayNameForPart(
        context: Context,
        treeUri: Uri,
        partName: String,
    ): SafDocEntry? {
        val index = getSafIndex(context, treeUri) ?: return null
        return index.partEntries.firstOrNull {
            it.displayName.equals(partName, ignoreCase = true)
        }
    }

    /**
     * Copies [sourceFile] to [sink] using a 64 KB buffer and returns total bytes written.
     */
    internal fun copyFileToStream(sourceFile: File, sink: OutputStream): Long {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        var total = 0L
        FileInputStream(sourceFile).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                sink.write(buffer, 0, read)
                total += read
            }
        }
        return total
    }

    // ---- Writing (DownloadStore.Pending) ------------------------------------

    /**
     * A destination that exists but is not yet a file anyone else can see.
     *
     * Every path out of here is either [commit] or [abort].
     */
    class Pending internal constructor(
        private val context: Context,
        val uri: Uri,
        val name: String,
        /** Set on the private/legacy/custom-SAF-staging path: the local `.part` file being written. */
        private val part: File?,
        /** Set on the private/legacy path: what [part] is renamed to. */
        private val target: File?,
        /** Set when staging for a custom SAF folder. */
        private val safTreeUri: Uri? = null,
        private val initialMimeType: String? = null,
    ) {
        /** The bytes being built, so [MediaTagger] always tags a random-access local file or MediaStore row. */
        val tagUri: Uri get() = part?.let(Uri::fromFile) ?: uri

        fun openStream(): OutputStream =
            part?.let { FileOutputStream(it) }
                ?: context.contentResolver.openOutputStream(uri)
                ?: error("Could not open $name for writing")

        /** @return the uri the finished file can be reached at. */
        fun commit(mimeType: String? = null): Uri {
            val effectiveMime = realAudioMimeType(name, mimeType ?: initialMimeType)

            // Custom SAF folder path: stage in cacheDir, tag locally, then stream via .vibe-part- + renameDocument
            val treeUri = safTreeUri
            val stagedPart = part
            if (treeUri != null && stagedPart != null) {
                try {
                    val expectedLen = stagedPart.length()
                    return runCatching {
                        // Avoid overwriting an existing final-named file of a different size
                        val existingEntry = findSafEntryByDisplayName(
                            context = context,
                            treeUri = treeUri,
                            displayName = name,
                            forceRefresh = true,
                        )
                        if (existingEntry != null && existingEntry.size == expectedLen && expectedLen > 0L) {
                            return@runCatching existingEntry.uri
                        }
                        writeSafDocumentWithPart(
                            context = context,
                            treeUri = treeUri,
                            finalFileName = name,
                            mimeType = effectiveMime,
                            expectedSize = expectedLen,
                        ) { out ->
                            copyFileToStream(stagedPart, out)
                        }
                    }.getOrElse { cause ->
                        Log.w(TAG, "Custom SAF commit failed for $name (${cause.message}); falling back to default Music folder")
                        // Never clear downloadFolderUri at download time; schedule background re-validation
                        Downloads.scheduleFolderValidation(context)
                        commitFallbackToDefaultMusic(stagedPart, name, effectiveMime)
                    }
                } finally {
                    stagedPart.delete()
                }
            }

            if (part != null && target != null) {
                if (!part.renameTo(target)) error("Could not finish writing $name")
                if (AppSettings.exportDownloads.value) {
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(target.absolutePath),
                        arrayOf(effectiveMime),
                        null,
                    )
                }
                return Uri.fromFile(target)
            }

            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            if (AppSettings.exportDownloads.value && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching {
                    context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val dataPath = cursor.getString(0)
                            if (!dataPath.isNullOrBlank()) {
                                MediaScannerConnection.scanFile(
                                    context,
                                    arrayOf(dataPath),
                                    arrayOf(effectiveMime),
                                    null,
                                )
                            }
                        }
                    }
                }
            }
            return uri
        }

        private fun commitFallbackToDefaultMusic(stagedFile: File, fileName: String, mimeType: String): Uri {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val fallbackUri = context.contentResolver.insert(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    values,
                ) ?: error("Could not create $fileName in Music")
                try {
                    context.contentResolver.openOutputStream(fallbackUri)?.use { out ->
                        copyFileToStream(stagedFile, out)
                    } ?: error("Could not open $fileName in Music")
                    context.contentResolver.update(
                        fallbackUri,
                        ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                        null,
                        null,
                    )
                    return fallbackUri
                } catch (t: Throwable) {
                    runCatching { context.contentResolver.delete(fallbackUri, null, null) }
                    throw t
                }
            } else {
                val targetFile = legacyFile(fileName)
                val folder = targetFile.parentFile ?: error("No Music folder on this device")
                if (!folder.exists() && !folder.mkdirs()) error("Could not create ${folder.path}")
                stagedFile.copyTo(targetFile, overwrite = true)
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(targetFile.absolutePath),
                    arrayOf(mimeType),
                    null,
                )
                return Uri.fromFile(targetFile)
            }
        }

        fun abort() {
            part?.delete()
            if (part == null && safTreeUri == null) {
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
        }
    }

    /**
     * Reserve [name] and return somewhere to write it.
     */
    fun begin(context: Context, name: String, mimeType: String): Pending {
        if (!AppSettings.exportDownloads.value) {
            val target = privateFile(context, name)
            val folder = target.parentFile ?: error("No download folder")
            if (!folder.exists() && !folder.mkdirs()) error("Could not create ${folder.path}")
            val part = File(folder, ".$name.part")
            part.delete()
            return Pending(context, Uri.fromFile(target), name, part = part, target = target)
        }

        val customTree = activeCustomTreeUri()
        if (customTree != null) {
            // Check whether custom folder is currently accessible; if not, fall back for this
            // single download without clearing the user's setting.
            val canUseCustom = runCatching {
                val treeDocId = DocumentsContract.getTreeDocumentId(customTree)
                val docUri = DocumentsContract.buildDocumentUriUsingTree(customTree, treeDocId)
                context.contentResolver.query(
                    docUri,
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                    null,
                    null,
                    null,
                )?.use { it.moveToFirst() } == true
            }.getOrDefault(false)

            if (canUseCustom) {
                val safeSuffix = name.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
                val stageFile = File(context.cacheDir, "$CACHE_STAGE_PREFIX${UUID.randomUUID()}-$safeSuffix.part")
                return Pending(
                    context = context,
                    uri = customTree,
                    name = name,
                    part = stageFile,
                    target = null,
                    safTreeUri = customTree,
                    initialMimeType = mimeType,
                )
            } else {
                Log.w(TAG, "Custom download folder $customTree temporarily unreachable at begin(); falling back for $name")
                Downloads.scheduleFolderValidation(context)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = runCatching {
                context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            }.getOrElse { cause ->
                Log.w(TAG, "the media store refused $mimeType for $name: ${cause.message}")
                error("Android won't store ${name.substringAfterLast('.', mimeType)} files in Music")
            } ?: error("Could not create $name in Music")
            return Pending(context, uri, name, part = null, target = null)
        }

        val target = legacyFile(name)
        val folder = target.parentFile ?: error("No Music folder on this device")
        if (!folder.exists() && !folder.mkdirs()) error("Could not create ${folder.path}")
        val part = File(folder, "$name.part")
        part.delete()
        return Pending(context, Uri.fromFile(target), name, part = part, target = target)
    }

    @Suppress("DEPRECATION")
    internal fun legacyFile(name: String) = File(
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), FOLDER),
        name,
    )

    // ---- Leftover Cleanup & Volume Free-Space (API 26–35) -------------------

    /**
     * Cleans stale `.download-*.part` files in `cacheDir` and stale `.vibe-part-*`
     * documents in [targetTreeUri] (or the active custom SAF folder) that are NOT in
     * [activePartNames] and whose `lastModified` is older than [STALE_PART_AGE_MS].
     */
    fun cleanupStaleTempFiles(context: Context, targetTreeUri: Uri? = activeCustomTreeUri()) {
        val now = System.currentTimeMillis()

        // 1. Clean stale cache staging files
        runCatching {
            context.cacheDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.startsWith(CACHE_STAGE_PREFIX) && file.name.endsWith(".part")) {
                    val age = now - file.lastModified()
                    if (age > STALE_PART_AGE_MS) {
                        file.delete()
                    }
                }
            }
        }

        // 2. Clean stale .vibe-part-* files in the SAF folder
        val treeUri = targetTreeUri ?: return
        runCatching {
            val index = getSafIndex(context, treeUri, forceRefresh = true) ?: return@runCatching
            var deletedAny = false
            for (part in index.partEntries) {
                if (part.displayName in activePartNames) continue
                val age = if (part.lastModified > 0L) now - part.lastModified else STALE_PART_AGE_MS + 1L
                if (age > STALE_PART_AGE_MS) {
                    if (runCatching {
                            DocumentsContract.deleteDocument(context.contentResolver, part.uri)
                        }.getOrDefault(false)
                    ) {
                        deletedAny = true
                    }
                }
            }
            if (deletedAny) markSafIndexDirty()
        }
    }

    /**
     * Identifies the storage volume ID (`"primary"` or secondary `<UUID>`) for a file,
     * MediaStore, or SAF URI so Move operations can determine whether source and destination
     * share a volume.
     */
    fun volumeIdOf(uri: Uri): String {
        if (uri.scheme == "file") {
            val path = uri.path.orEmpty()
            val match = Regex("""^/storage/([0-9A-Fa-f-]{4,})/""").find(path)
            return match?.groupValues?.get(1)?.lowercase(Locale.ROOT) ?: "primary"
        }
        if (isSafTreeUri(uri)) {
            val treeId = extractTreeDocumentId(uri).orEmpty()
            if (treeId.startsWith("raw:")) return "primary"
            val vol = treeId.substringBefore(':', "primary")
            return if (vol.equals("primary", ignoreCase = true)) "primary" else vol.lowercase(Locale.ROOT)
        }
        return "primary"
    }

    /**
     * Queries available free bytes on the volume backing [targetTreeUri] (or primary external
     * storage when null) across API 26–35 without calling API-30-only methods on older devices.
     */
    fun queryAvailableBytes(context: Context, targetTreeUri: Uri?): Long? = runCatching {
        val volId = targetTreeUri?.let(::volumeIdOf) ?: "primary"
        if (volId == "primary") {
            val stats = context.getSystemService(StorageStatsManager::class.java)
            if (stats != null) {
                val free = runCatching { stats.getFreeBytes(StorageManager.UUID_DEFAULT) }.getOrNull()
                if (free != null && free > 0L) return@runCatching free
            }
            return@runCatching StatFs(Environment.getExternalStorageDirectory().path).availableBytes
        }

        // Secondary volume (e.g. SD card <UUID>)
        val storageManager = context.getSystemService(StorageManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && storageManager != null) {
            val matchedVolume = storageManager.storageVolumes.firstOrNull {
                it.uuid?.equals(volId, ignoreCase = true) == true
            }
            val dir = matchedVolume?.directory
            if (dir != null && dir.exists()) {
                return@runCatching StatFs(dir.path).availableBytes
            }
        }

        // API 26–29 fallback (and fallback if StorageVolume.directory was null):
        // find matching mount root from getExternalFilesDirs(null)
        val prefix = "/storage/$volId".lowercase(Locale.ROOT)
        val matchedAppDir = context.getExternalFilesDirs(null)
            .filterNotNull()
            .firstOrNull { it.absolutePath.lowercase(Locale.ROOT).startsWith(prefix) }
        if (matchedAppDir != null && matchedAppDir.exists()) {
            return@runCatching StatFs(matchedAppDir.path).availableBytes
        }
        val directMount = File("/storage/$volId")
        if (directMount.exists()) {
            return@runCatching StatFs(directMount.path).availableBytes
        }
        null
    }.getOrNull()
}
