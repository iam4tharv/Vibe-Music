package com.music.echo.playback

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import timber.log.Timber

object CallerAuthUtils {

    @Suppress("DEPRECATION")
    @SuppressLint("PackageManagerGetSignatures")
    fun isTrusted(context: Context, callerPackageName: String): Boolean {
        try {
            val pm = context.packageManager
            
            // Check if caller is a system app (e.g. Android Auto, Bluetooth, System UI)
            val appInfo = pm.getApplicationInfo(callerPackageName, 0)
            val isSystemApp = (appInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
            if (isSystemApp) {
                return true
            }

            val callerPackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(callerPackageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                pm.getPackageInfo(callerPackageName, PackageManager.GET_SIGNATURES)
            }

            val myPackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            }

            val mySigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                myPackageInfo.signingInfo?.apkContentsSigners
            } else {
                myPackageInfo.signatures
            }

            val callerSigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                callerPackageInfo.signingInfo?.apkContentsSigners
            } else {
                callerPackageInfo.signatures
            }

            if (mySigs != null && callerSigs != null) {
                for (mySig in mySigs) {
                    for (callerSig in callerSigs) {
                        if (mySig == callerSig) {
                            return true
                        }
                    }
                }
            }

            Timber.w("Caller $callerPackageName is not trusted. It is not a system app and its signature does not match ours.")
            return false

        } catch (e: PackageManager.NameNotFoundException) {
            Timber.e(e, "Could not find package info for caller: $callerPackageName")
            return false
        }
    }
}
