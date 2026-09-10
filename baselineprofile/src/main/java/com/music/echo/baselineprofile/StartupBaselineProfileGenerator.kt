package com.music.echo.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupBaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() {
        baselineProfileRule.collect(
            packageName = "com.music.echo",
            profileBlock = {
                pressHome()
                startActivityAndWait()
                // You can add additional UI automator interactions here to
                // exercise other parts of the app (like opening the player)
            }
        )
    }
}
