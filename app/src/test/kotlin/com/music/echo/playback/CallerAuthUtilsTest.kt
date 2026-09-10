package com.music.echo.playback

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class CallerAuthUtilsTest {

    private lateinit var context: Context
    private lateinit var packageManager: PackageManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val pm = context.packageManager
        val shadowPm = shadowOf(pm)

        // Setup my package info
        val myPackageInfo = PackageInfo()
        myPackageInfo.packageName = context.packageName
        myPackageInfo.signatures = arrayOf(Signature("1234"))
        shadowPm.installPackage(myPackageInfo)

        // Setup untrusted caller
        val untrustedPackageInfo = PackageInfo()
        untrustedPackageInfo.packageName = "com.evil.hacker"
        untrustedPackageInfo.signatures = arrayOf(Signature("5678"))
        shadowPm.installPackage(untrustedPackageInfo)
        
        // Setup trusted caller (same signature)
        val trustedPackageInfo = PackageInfo()
        trustedPackageInfo.packageName = "com.trusted.app"
        trustedPackageInfo.signatures = arrayOf(Signature("1234"))
        shadowPm.installPackage(trustedPackageInfo)
        
        // Setup system caller
        val systemPackageInfo = PackageInfo()
        systemPackageInfo.packageName = "com.android.systemui"
        systemPackageInfo.signatures = arrayOf(Signature("9999"))
        val systemAppInfo = ApplicationInfo()
        systemAppInfo.flags = ApplicationInfo.FLAG_SYSTEM
        systemPackageInfo.applicationInfo = systemAppInfo
        shadowPm.installPackage(systemPackageInfo)
    }

    @Test
    fun testUntrustedCallerRejected() {
        assertFalse(CallerAuthUtils.isTrusted(context, "com.evil.hacker"))
    }

    @Test
    fun testTrustedCallerAllowed() {
        assertTrue(CallerAuthUtils.isTrusted(context, "com.trusted.app"))
    }

    @Test
    fun testSystemCallerAllowed() {
        assertTrue(CallerAuthUtils.isTrusted(context, "com.android.systemui"))
    }
}
