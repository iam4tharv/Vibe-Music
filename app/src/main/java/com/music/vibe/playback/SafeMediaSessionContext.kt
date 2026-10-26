package com.music.vibe.playback

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager

/**
 * A ContextWrapper used exclusively for MediaLibrarySession initialization.
 *
 * Media3 checks `Util.isAutomotive(context)` when deciding whether to register
 * an AndroidAutoConnectionStateObserver. If the device is not automotive and the
 * manufacturer matches certain OEMs (Google, Motorola, Vivo, etc.), Media3 queries
 * `content://androidx.car.app.connection` via ContentResolver.
 *
 * When Android Auto (Gearhead) is not installed on the system (e.g. in the AI Studio
 * emulator environment), that query causes the Android OS ActivityThread to log:
 * "E/ActivityThread: Failed to find provider info for androidx.car.app.connection".
 *
 * Declaring a dummy provider for `androidx.car.app.connection` in the manifest conflicts
 * with actual Android Auto installations (`INSTALL_FAILED_CONFLICTING_PROVIDER`).
 *
 * Wrapping the PackageManager to report [PackageManager.FEATURE_AUTOMOTIVE] as true
 * cleanly bypasses the workaround in Media3 without touching the manifest or any real
 * system settings, completely preventing the failed provider lookup log.
 */
class SafeMediaSessionContext(base: Context) : ContextWrapper(base) {
    private val safePackageManager: PackageManager by lazy {
        SafeAutomotivePackageManager(base.packageManager)
    }

    override fun getPackageManager(): PackageManager = safePackageManager

    override fun getApplicationContext(): Context = this
}
