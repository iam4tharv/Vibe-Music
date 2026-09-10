package com.music.echo

import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.music.echo.utils.YTPlayerUtils
import com.music.echo.constants.AudioQuality
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import android.net.ConnectivityManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SimpleTest {
    @Test
    fun testRestricted() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        // Some age restricted video ID, e.g. "z_W-D_k3W5Y" or we can test generic one
        val videoId = "z_W-D_k3W5Y" 
        println("TESTING videoId: $videoId")
        
        val result = YTPlayerUtils.resolvePlaybackData(
            videoId = videoId,
            playlistId = null,
            audioQuality = AudioQuality.AUTO,
            connectivityManager = connectivityManager
        )
        println("TEST RESULT: $result")
        if (result.isSuccess) {
            val data = result.getOrNull()
            println("URL: ${data?.streamUrl}")
        } else {
            println("ERR: ${result.exceptionOrNull()?.message}")
        }
    }
}
