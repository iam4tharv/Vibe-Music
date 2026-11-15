package com.music.vibe.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Tracks physical app installation and update state using [Context.getNoBackupFilesDir].
 *
 * Files stored in [Context.getNoBackupFilesDir] are never backed up to Google Drive
 * or transferred across devices by Android Auto-Backup. This guarantees that:
 * 1. A fresh install or complete reinstall starts with no state file, reliably
 *    prompting the "Welcome to Vibe Music" dialog.
 * 2. An update from a previous version detects that `lastSeenVersionCode < currentVersionCode`,
 *    reliably prompting the "What's New in v[Version]" dialog.
 * 3. Normal subsequent app launches within the same version code skip the popup.
 */
object AppLaunchTracker {

    private const val STATE_FILE_NAME = "vibe_install_launch_state.json"
    private const val KEY_VERSION_CODE = "version_code"
    private const val KEY_VERSION_NAME = "version_name"

    enum class LaunchPromptType {
        FRESH_INSTALL,
        UPDATE,
    }

    data class LaunchPromptState(
        val type: LaunchPromptType,
        val versionName: String,
        val versionCode: Int,
    )

    private fun getStateFile(context: Context): File {
        val dir = context.noBackupFilesDir ?: context.filesDir
        return File(dir, STATE_FILE_NAME)
    }

    /**
     * Inspects whether a first-install or update release dialog should be displayed.
     * Returns null if the current version has already been presented and acknowledged.
     */
    fun checkLaunchPrompt(
        context: Context,
        currentVersionCode: Int,
        currentVersionName: String,
    ): LaunchPromptState? {
        val file = getStateFile(context)
        if (!file.exists()) {
            return LaunchPromptState(
                type = LaunchPromptType.FRESH_INSTALL,
                versionName = currentVersionName,
                versionCode = currentVersionCode,
            )
        }

        val lastSeenCode = runCatching {
            val content = file.readText()
            val json = JSONObject(content)
            json.optInt(KEY_VERSION_CODE, -1)
        }.getOrDefault(-1)

        return if (lastSeenCode in 0 until currentVersionCode) {
            LaunchPromptState(
                type = LaunchPromptType.UPDATE,
                versionName = currentVersionName,
                versionCode = currentVersionCode,
            )
        } else {
            null
        }
    }

    /**
     * Marks the release dialog for [versionCode] as acknowledged so it will
     * not be presented again on subsequent launches of this version.
     */
    fun acknowledgeLaunchPrompt(
        context: Context,
        versionCode: Int,
        versionName: String,
    ) {
        runCatching {
            val file = getStateFile(context)
            val json = JSONObject().apply {
                put(KEY_VERSION_CODE, versionCode)
                put(KEY_VERSION_NAME, versionName)
            }
            file.writeText(json.toString())
        }
    }

    internal fun resetForTesting(context: Context) {
        runCatching {
            val file = getStateFile(context)
            if (file.exists()) file.delete()
        }
    }
}
