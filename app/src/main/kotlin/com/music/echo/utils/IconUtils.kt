

package com.music.echo.utils

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

object IconUtils {
    fun setIcon(context: Context, isDynamic: Boolean) {
        val pm = context.packageManager
        val dynamic = ComponentName(context, "com.music.echo.MainActivityAlias")
        val static = ComponentName(context, "com.music.echo.MainActivityStatic")

        pm.setComponentEnabledSetting(
            dynamic,
            if (isDynamic) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
        pm.setComponentEnabledSetting(
            static,
            if (!isDynamic) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }
}
