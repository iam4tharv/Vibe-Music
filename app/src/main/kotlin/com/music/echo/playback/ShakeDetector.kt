package com.music.echo.playback
    
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ShakeDetector(private val context: Context, private val onShake: () -> Unit) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    
    private var isProximityCovered = false
    private var lastShakeTime: Long = 0
    private var peakCount = 0
    private var lastPeakTime: Long = 0
    
    private val SHAKE_THRESHOLD_GRAVITY = 2.5f
    private val SHAKE_WINDOW_MS = 500L
    private val SHAKE_COOLDOWN_MS = 2000L
    
    private var isStarted = false

    fun start() {
        if (isStarted) return
        isStarted = true
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        proximitySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() {
        if (!isStarted) return
        isStarted = false
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        
        if (event.sensor.type == Sensor.TYPE_PROXIMITY) {
            // Usually if distance is less than maximum range, it's covered
            isProximityCovered = event.values[0] < event.sensor.maximumRange
            return
        }

        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            if (isProximityCovered) return // Ignore if in pocket
            
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            
            val gX = x / SensorManager.GRAVITY_EARTH
            val gY = y / SensorManager.GRAVITY_EARTH
            val gZ = z / SensorManager.GRAVITY_EARTH
            
            val gForce = sqrt(gX * gX + gY * gY + gZ * gZ)
            
            if (gForce > SHAKE_THRESHOLD_GRAVITY) {
                val now = System.currentTimeMillis()
                
                if (now - lastShakeTime < SHAKE_COOLDOWN_MS) {
                    return // In cooldown
                }
                
                if (now - lastPeakTime > SHAKE_WINDOW_MS) {
                    peakCount = 0 // Reset if too much time passed between peaks
                }
                
                if (now - lastPeakTime > 50L) { // Debounce peaks
                    lastPeakTime = now
                    peakCount++
                    
                    if (peakCount >= 3) {
                        lastShakeTime = now
                        peakCount = 0
                        triggerShake()
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    
    private fun triggerShake() {
        if (vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(50)
            }
        }
        onShake()
    }
}
