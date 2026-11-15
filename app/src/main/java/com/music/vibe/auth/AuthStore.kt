package com.music.vibe.auth

import android.content.Context
import android.content.SharedPreferences
import com.music.vibe.data.innertube.Innertube
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Encrypted-at-rest storage for credentials.
 *
 * Holds YouTube Music multi-account sessions, channel profiles, cookies,
 * Discord bearer token, and WebDAV/SMB passwords.
 */
class AuthStore(context: Context) {

    private val prefs: SharedPreferences =
        EncryptedPrefs.open(context, "vibemusic_auth", "vibemusic_auth_plain")

    var cookie: String?
        get() = prefs.getString(KEY_COOKIE, null)
        set(value) = prefs.edit().putString(KEY_COOKIE, value).apply()

    /**
     * The durable account registry. Credentials remain in this encrypted store;
     * the old single-cookie entry is migrated lazily so an update never logs a
     * listener out.
     */
    var sessions: List<GoogleAccountSession>
        get() {
            val saved = sessionsFromJson(prefs.getString(KEY_SESSIONS, null))
            if (saved.isNotEmpty()) return saved
            val legacy = cookie ?: return emptyList()
            val profile = YouTubeProfile(
                profileId = profileId(channelPageId, channelDataSyncId, channelName ?: "Personal"),
                name = channelName ?: "Personal",
                pageId = channelPageId,
                dataSyncId = channelDataSyncId,
                authUser = channelAuthUser,
                isBrandAccount = channelPageId != null,
            )
            return listOf(GoogleAccountSession(
                accountId = sessionId(legacy, channelDataSyncId), cookie = legacy,
                profiles = listOf(profile), activeProfileId = profile.profileId,
            )).also { replaceSessions(it) }
        }
        set(value) = replaceSessions(value)

    var activeAccountId: String?
        get() = prefs.getString(KEY_ACTIVE_ACCOUNT, null)
        set(value) = prefs.edit().putString(KEY_ACTIVE_ACCOUNT, value).apply()

    var activeProfileId: String?
        get() = prefs.getString(KEY_ACTIVE_PROFILE, null)
        set(value) = prefs.edit().putString(KEY_ACTIVE_PROFILE, value).apply()

    val activeSession: GoogleAccountSession?
        get() = sessions.firstOrNull { it.accountId == activeAccountId }
            ?: sessions.firstOrNull()

    fun replaceSessions(value: List<GoogleAccountSession>) {
        prefs.edit().putString(KEY_SESSIONS, value.toJson()).apply()
    }

    fun upsertSession(session: GoogleAccountSession, activate: Boolean = true) {
        val next = sessions.filterNot { it.accountId == session.accountId } + session
        replaceSessions(next)
        if (activate) select(session.accountId, session.activeProfileId)
    }

    fun select(accountId: String, profileId: String?) {
        activeAccountId = accountId
        activeProfileId = profileId
    }

    fun removeAccount(accountId: String): GoogleAccountSession? {
        val remaining = sessions.filterNot { it.accountId == accountId }
        replaceSessions(remaining)
        val fallback = remaining.firstOrNull()
        select(fallback?.accountId.orEmpty(), fallback?.activeProfileId)
        prefs.edit().putString(KEY_COOKIE, fallback?.cookie).apply()
        return fallback
    }

    val isSignedIn: Boolean
        get() = activeSession?.cookie?.let { hasApiSid(it) } == true

    /** The Discord account's bearer token. See DiscordRPC for why a user token. */
    var discordToken: String?
        get() = prefs.getString(KEY_DISCORD_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_DISCORD_TOKEN, value).apply()

    /** The WebDAV server password. */
    var webdavPassword: String?
        get() = prefs.getString(KEY_WEBDAV_PASSWORD, null)
        set(value) = prefs.edit().putString(KEY_WEBDAV_PASSWORD, value).apply()

    /** The SMB share password. */
    var smbPassword: String?
        get() = prefs.getString(KEY_SMB_PASSWORD, null)
        set(value) = prefs.edit().putString(KEY_SMB_PASSWORD, value).apply()

    /**
     * The channel the listener chose to act as, if they chose one.
     *
     * Stored beside the cookie rather than in the plain settings because it is
     * only meaningful with that cookie and must not outlive it: a `dataSyncId`
     * from one login, replayed under another, is answered with 401 on every
     * request. [signOut] and [onNewSession] both clear it for that reason.
     *
     * A null [channelPageId] with a [channelDataSyncId] set is a real state,
     * not an absent one — it is the account's own channel, deliberately chosen
     * over a brand channel the web player would otherwise default to.
     */
    val channelPageId: String? get() = prefs.getString(KEY_CHANNEL_PAGE_ID, null)
    val channelDataSyncId: String? get() = prefs.getString(KEY_CHANNEL_DATASYNC_ID, null)

    /** The chosen channel's display name, for the settings row. */
    val channelName: String? get() = prefs.getString(KEY_CHANNEL_NAME, null)

    /** Which Google account in the jar it belongs to; null means "as the shell says". */
    val channelAuthUser: String? get() = prefs.getString(KEY_CHANNEL_AUTH_USER, null)

    fun selectChannel(
        pageId: String?,
        dataSyncId: String?,
        name: String?,
        authUser: String? = null,
    ) = prefs.edit()
        .putString(KEY_CHANNEL_PAGE_ID, pageId)
        .putString(KEY_CHANNEL_DATASYNC_ID, dataSyncId)
        .putString(KEY_CHANNEL_NAME, name)
        .putString(KEY_CHANNEL_AUTH_USER, authUser)
        .apply()

    /**
     * The chosen channel's name, once something knows it.
     *
     * Separate from [selectChannel] because the two are learned at different
     * times: a channel picked in the in-app browser is identified by ids the
     * moment it is picked, and named only after the next account fetch comes
     * back to say what it is called.
     */
    fun setChannelName(name: String?) =
        prefs.edit().putString(KEY_CHANNEL_NAME, name).apply()

    /** Back to whichever channel YouTube Music serves by default. */
    fun clearChannel() = prefs.edit()
        .remove(KEY_CHANNEL_PAGE_ID)
        .remove(KEY_CHANNEL_DATASYNC_ID)
        .remove(KEY_CHANNEL_NAME)
        .remove(KEY_CHANNEL_AUTH_USER)
        .apply()

    /**
     * A fresh login lands here. The cookie is new, so any channel chosen under
     * the old one names an identity this session cannot act as.
     */
    fun onNewSession(cookie: String) {
        this.cookie = cookie
        clearChannel()
    }

    /**
     * Signs out of YouTube Music only — the Discord login is a separate account.
     */
    fun signOut() {
        prefs.edit().remove(KEY_COOKIE).remove(KEY_SESSIONS)
            .remove(KEY_ACTIVE_ACCOUNT).remove(KEY_ACTIVE_PROFILE).apply()
        clearChannel()
        // The in-app browser keeps its own copy of the Google login, and a
        // sign-out that leaves it in place is not one: the next sign-in is
        // waved straight through as the account just signed out of, with no
        // opportunity to choose another. See [BrowserSession].
        BrowserSession.clearGoogleCookies()
    }

    /**
     * Exports all YouTube Music accounts, channel profiles, active session cookies,
     * and connected service secrets stored in [AuthStore] for backup archives.
     */
    fun exportBackupData(): Map<String, String> {
        val currentSessions = sessions
        val currentActive = activeSession
        if (currentActive != null && activeAccountId == null) {
            select(currentActive.accountId, currentActive.activeProfileId)
        }
        val result = LinkedHashMap<String, String>()
        for (key in BACKUP_KEYS) {
            val value = prefs.getString(key, null)
            if (!value.isNullOrEmpty()) {
                result[key] = value
            }
        }
        if (currentSessions.isNotEmpty() && KEY_SESSIONS !in result) {
            result[KEY_SESSIONS] = currentSessions.toJson()
        }
        return result
    }

    /**
     * Restores YouTube Music accounts, channel profiles, session cookies, and
     * service secrets from a backup archive and immediately activates the
     * restored session on [Innertube] and notifies active UI observers.
     */
    fun importBackupData(values: Map<String, String>) {
        prefs.edit().apply {
            for (key in BACKUP_KEYS) {
                remove(key)
            }
            for ((key, value) in values) {
                if (key in BACKUP_KEYS && value.isNotEmpty()) {
                    putString(key, value)
                }
            }
        }.apply()

        // Ensure legacy single-cookie backups are promoted into structured sessions
        val restoredSessions = sessions
        val active = activeSession
        if (active != null) {
            val resolvedProfile = active.profiles.firstOrNull { it.profileId == activeProfileId }
                ?: active.profiles.firstOrNull { it.profileId == active.activeProfileId }
                ?: active.profiles.firstOrNull()
            select(active.accountId, resolvedProfile?.profileId ?: active.activeProfileId)
            cookie = active.cookie
            Innertube.cookie = active.cookie
            if (resolvedProfile != null) {
                selectChannel(
                    pageId = resolvedProfile.pageId,
                    dataSyncId = resolvedProfile.dataSyncId,
                    name = resolvedProfile.name,
                    authUser = resolvedProfile.authUser,
                )
                Innertube.selectChannel(
                    resolvedProfile.pageId,
                    resolvedProfile.dataSyncId,
                    resolvedProfile.authUser,
                )
            } else {
                Innertube.selectChannel(channelPageId, channelDataSyncId, channelAuthUser)
            }
        } else if (restoredSessions.isEmpty()) {
            Innertube.cookie = null
            Innertube.selectChannel(null, null, null)
        }
        _restoredEvents.tryEmit(Unit)
    }

    companion object {
        private val _restoredEvents = MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        /** Emits whenever [importBackupData] restores auth credentials so the UI updates immediately. */
        val restoredEvents: SharedFlow<Unit> = _restoredEvents.asSharedFlow()

        /**
         * Whether a cookie header carries a secret Innertube requests can be
         * signed with.
         */
        fun hasApiSid(cookieHeader: String): Boolean =
            cookieHeader.split(';').any { entry ->
                val name = entry.substringBefore('=').trim()
                val value = entry.substringAfter('=', "").trim()
                name in API_SID_NAMES && value.isNotEmpty()
            }

        private val API_SID_NAMES =
            setOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID")

        private const val KEY_COOKIE = "cookie"
        private const val KEY_SESSIONS = "google_account_sessions_v2"
        private const val KEY_ACTIVE_ACCOUNT = "active_google_account_id_v2"
        private const val KEY_ACTIVE_PROFILE = "active_youtube_profile_id_v2"
        private const val KEY_CHANNEL_PAGE_ID = "channel_page_id"
        private const val KEY_CHANNEL_DATASYNC_ID = "channel_datasync_id"
        private const val KEY_CHANNEL_NAME = "channel_name"
        private const val KEY_CHANNEL_AUTH_USER = "channel_auth_user"
        private const val KEY_DISCORD_TOKEN = "discord_token"
        private const val KEY_WEBDAV_PASSWORD = "webdav_password"
        private const val KEY_SMB_PASSWORD = "smb_password"

        private val BACKUP_KEYS = setOf(
            KEY_COOKIE,
            KEY_SESSIONS,
            KEY_ACTIVE_ACCOUNT,
            KEY_ACTIVE_PROFILE,
            KEY_CHANNEL_PAGE_ID,
            KEY_CHANNEL_DATASYNC_ID,
            KEY_CHANNEL_NAME,
            KEY_CHANNEL_AUTH_USER,
            KEY_DISCORD_TOKEN,
            KEY_WEBDAV_PASSWORD,
            KEY_SMB_PASSWORD,
        )
    }
}
