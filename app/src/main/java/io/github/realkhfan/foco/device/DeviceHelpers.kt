package io.github.realkhfan.foco.device

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaActionSound
import android.os.Build
import android.os.Looper
import android.view.OrientationEventListener
import android.view.Surface
import androidx.core.content.ContextCompat
import io.github.realkhfan.foco.ui.Tilt
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Tracks how the phone is held. The UI stays in portrait (like Pixel Camera) and only the icons turn;
 * photos get the right rotation through [onRotation]. Also reports tilt (level line / top-down crosshair)
 * and how much the phone moves (gyroscope) for Night's "hold still".
 */
class OrientationWatcher(
    context: Context,
    private val onRotation: (degrees: Int, surfaceRotation: Int) -> Unit,
    private val onTilt: (Tilt) -> Unit,
    private val onMotion: (rate: Float, x: Float, y: Float) -> Unit = { _, _, _ -> },
) : SensorEventListener {
    private var current = 0
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val gravity: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var rate = 0f
    private var mx = 0f
    private var my = 0f
    private var up = floatArrayOf(0f, 1f, 0f)
    private var lastGyroNs = 0L

    /** Turn around the vertical since [resetPan] in radians (to the right = positive), for panoramas. */
    @Volatile var panYaw = 0f
        private set

    /** How far the camera points up (+) or down (−) from level, in radians. */
    @Volatile var elevation = 0f
        private set

    fun resetPan() {
        panYaw = 0f
    }

    /** Smoothed rotation speed in rad/s. */
    val motionRate: Float get() = rate

    private val listener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            val next = snap(orientation, current)
            if (next != current) {
                current = next
                onRotation(next, surfaceRotationFor(next))
            }
        }
    }

    fun start() {
        if (listener.canDetectOrientation()) listener.enable()
        gravity?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        gyro?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        listener.disable()
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            val (gx, gy, gz) = Triple(event.values[0], event.values[1], event.values[2])
            if (lastGyroNs != 0L) {
                val dt = ((event.timestamp - lastGyroNs) / 1e9f).coerceIn(0f, 0.1f)
                // Turning right (clockwise seen from above) is a negative rotation around "up".
                panYaw -= (gx * up[0] + gy * up[1] + gz * up[2]) * dt
            }
            lastGyroNs = event.timestamp
            val magnitude = sqrt(gx * gx + gy * gy + gz * gz)
            rate += (magnitude - rate) * 0.15f
            mx += (gy - mx) * 0.3f
            my += (gx - my) * 0.3f
            onMotion(rate, mx, my)
            return
        }
        val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
        val g = sqrt(x * x + y * y + z * z)
        if (g > 1f) {
            up = floatArrayOf(x / g, y / g, z / g)
            // The camera looks along −Z: its height above the horizon.
            elevation = kotlin.math.asin((-z / g).coerceIn(-1f, 1f))
        }
        onTilt(tiltFor(x, y, z, current))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        /** Snap to 0/90/180/270 with a little hysteresis so it doesn't flicker at 45°. */
        fun snap(orientation: Int, current: Int): Int {
            val diff = abs(((orientation - current + 540) % 360) - 180)
            if (diff < 60) return current
            return ((orientation + 45) / 90 * 90) % 360
        }

        /**
         * Gravity → tilt. Upright: angle of the horizon (0 = level). Lying flat (pointing down or up):
         * how far off flat it is on each axis, for the crosshair.
         */
        fun tiltFor(x: Float, y: Float, z: Float, rotation: Int): Tilt {
            val g = sqrt(x * x + y * y + z * z)
            if (g < 1f) return Tilt()
            if (abs(z) / g > 0.8f) {
                val fx = Math.toDegrees(atan2(x.toDouble(), abs(z).toDouble())).toFloat()
                val fy = Math.toDegrees(atan2(y.toDouble(), abs(z).toDouble())).toFloat()
                return Tilt(flatX = fx, flatY = fy)
            }
            val roll = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()
            var tilt = roll - when (rotation) { 90 -> -90f; 180 -> 180f; 270 -> 90f; else -> 0f }
            while (tilt > 180f) tilt -= 360f
            while (tilt < -180f) tilt += 360f
            return Tilt(levelDegrees = tilt)
        }

        fun surfaceRotationFor(degrees: Int): Int = when (degrees) {
            90 -> Surface.ROTATION_270
            180 -> Surface.ROTATION_180
            270 -> Surface.ROTATION_90
            else -> Surface.ROTATION_0
        }
    }
}

/** Optional GPS tag for photos. Uses the phone's own location providers (no Google Play Services). */
class LocationTracker(private val context: Context) : LocationListener {
    private val manager = context.getSystemService(LocationManager::class.java)
    @Volatile var last: Location? = null
        private set
    private var running = false

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (running || manager == null || !hasPermission()) return
        running = true
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }.filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        providers.forEach { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()?.let { if (isBetter(it)) last = it }
            runCatching { manager.requestLocationUpdates(provider, 5_000L, 10f, this, Looper.getMainLooper()) }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { manager?.removeUpdates(this) }
    }

    private fun isBetter(location: Location): Boolean {
        val current = last ?: return true
        return location.time > current.time || location.accuracy < current.accuracy
    }

    override fun onLocationChanged(location: Location) {
        if (isBetter(location)) last = location
    }
}

/** Shutter and recording sounds (the system's standard camera sounds). */
class CameraSounds {
    private val sound = MediaActionSound().apply {
        load(MediaActionSound.SHUTTER_CLICK)
        load(MediaActionSound.START_VIDEO_RECORDING)
        load(MediaActionSound.STOP_VIDEO_RECORDING)
    }

    fun shutter() = sound.play(MediaActionSound.SHUTTER_CLICK)
    fun startVideo() = sound.play(MediaActionSound.START_VIDEO_RECORDING)
    fun stopVideo() = sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
    fun release() = sound.release()
}
