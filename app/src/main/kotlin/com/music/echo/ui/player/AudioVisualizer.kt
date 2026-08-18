package com.music.echo.ui.player

import android.media.audiofx.Visualizer
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.media3.exoplayer.ExoPlayer
import com.music.echo.playback.PlayerConnection

@Composable
fun AudioVisualizer(
    playerConnection: PlayerConnection?,
    modifier: Modifier = Modifier,
    barColor: Color = Color.White.copy(alpha = 0.5f)
) {
    var waveform by remember { mutableStateOf(ByteArray(0)) }
    
    DisposableEffect(playerConnection) {
        val player = playerConnection?.player as? ExoPlayer
        var visualizer: Visualizer? = null
        
        val initVisualizer = {
            val sessionId = player?.audioSessionId
            if (sessionId != null && sessionId != 0) {
                try {
                    visualizer = Visualizer(sessionId).apply {
                        captureSize = Visualizer.getCaptureSizeRange()[1]
                        setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                            override fun onWaveFormDataCapture(v: Visualizer?, waveformData: ByteArray?, samplingRate: Int) {
                                waveformData?.let {
                                    waveform = it.copyOf()
                                }
                            }
                            override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                                // Not used
                            }
                        }, Visualizer.getMaxCaptureRate() / 2, true, false)
                        enabled = true
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        
        initVisualizer()
        
        onDispose {
            try {
                visualizer?.enabled = false
                visualizer?.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    Canvas(modifier = modifier) {
        if (waveform.isEmpty()) return@Canvas
        
        val barWidth = 8f
        val space = 8f
        val barsCount = (size.width / (barWidth + space)).toInt()
        if (barsCount <= 0) return@Canvas
        
        val step = waveform.size / barsCount
        if (step <= 0) return@Canvas
        
        val centerY = size.height / 2
        
        for (i in 0 until barsCount) {
            val idx = i * step
            if (idx >= waveform.size) break
            
            // waveform values are generally around 128 (which is the center line)
            // It varies from 0 to 255. 128 is silence.
            val byteValue = waveform[idx].toInt() and 0xFF
            val amplitude = Math.abs(byteValue - 128) / 128f
            val height = (amplitude * size.height).coerceAtLeast(10f)
            
            val x = i * (barWidth + space) + barWidth / 2
            
            drawLine(
                color = barColor,
                start = Offset(x, centerY - height / 2),
                end = Offset(x, centerY + height / 2),
                strokeWidth = barWidth,
                cap = StrokeCap.Round
            )
        }
    }
}
