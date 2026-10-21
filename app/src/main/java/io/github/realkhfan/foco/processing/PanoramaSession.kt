package io.github.realkhfan.foco.processing

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.ImageProxy
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan

/**
 * A panorama being taken: while you turn, a frame is kept every ~40 % of the view (the gyroscope says when),
 * then everything is stitched and saved in the background.
 *
 * [offer] runs on the camera analysis thread.
 */
class PanoramaSession(
    private val rotation: Int,
    /** Focal length in pixels of the full-size camera frames. */
    private val focalFull: Float,
    /** (turn in radians, camera elevation in radians, turning speed in rad/s) right now. */
    private val sensors: () -> FloatArray,
    /** Encodes and saves the finished picture; returns its URI as a string. */
    private val save: (width: Int, height: Int, render: (row0: Int, rows: Int, out: IntArray) -> Unit) -> String?,
    private val listener: Listener,
) {
    interface Listener {
        fun onShot(thumb: Bitmap, yaw: Float, fov: Float) {}
        fun onHint(hint: Hint) {}
        /** No more frames: the camera can go back to normal. */
        fun onCaptured(frames: Int) {}
        fun onSaved(uri: String?) {}
    }

    enum class Hint { START, SLOW_DOWN, KEEP_LEVEL }

    private val main = Handler(Looper.getMainLooper())
    private val shots = mutableListOf<PanoShot>()
    private var full: YuvFrame? = null
    private val finished = AtomicBoolean(false)
    private var pitch0 = 0f
    private var fov = 0.9f
    private var lastHint = Hint.START
    private val focal = focalFull / 2f // frames are kept at half size

    val frameCount: Int get() = synchronized(shots) { shots.size }

    fun offer(image: ImageProxy): Boolean {
        if (finished.get()) return false
        val s = sensors()
        val yaw = s[0]
        val elevation = s[1]
        val rate = s[2]
        val count = synchronized(shots) { shots.size }
        if (count == 0) {
            pitch0 = elevation
            grab(image, yaw, 0f)
            return true
        }
        val pitch = elevation - pitch0
        val hint = when {
            rate > 0.9f -> Hint.SLOW_DOWN
            abs(pitch) > 0.12f -> Hint.KEEP_LEVEL
            else -> Hint.START
        }
        if (hint != lastHint) {
            lastHint = hint
            main.post { listener.onHint(hint) }
        }
        if (abs(pitch) > 0.2f) return true
        val (nearest, span) = synchronized(shots) {
            shots.minOf { abs(it.yaw - yaw) } to (maxOf(shots.maxOf { it.yaw }, yaw) - minOf(shots.minOf { it.yaw }, yaw))
        }
        if (nearest >= fov * STEP) {
            grab(image, yaw, pitch)
            if (span + fov >= 2 * PI * 0.95 || frameCount >= MAX_FRAMES) stop()
        }
        return !finished.get()
    }

    private fun grab(image: ImageProxy, yaw: Float, pitch: Float) {
        val buffer = full?.takeIf { FrameCopy.matches(it, image) } ?: YuvFrame(image.width, image.height).also { full = it }
        FrameCopy.copy(image, buffer)
        val small = YuvOps.downscaleRotate(buffer, rotation)
        fov = 2f * atan(small.width / 2f / focal)
        synchronized(shots) { shots += PanoShot(small, yaw, pitch) }
        val (pixels, w, h) = YuvOps.thumbnail(small, 8)
        val thumb = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        val currentFov = fov
        main.post { listener.onShot(thumb, yaw, currentFov) }
    }

    /** Finish (shutter tapped again or a full circle): stitch and save what was taken. */
    fun stop() {
        if (!finished.compareAndSet(false, true)) return
        val taken = synchronized(shots) { shots.toList() }
        full = null
        main.post { listener.onCaptured(taken.size) }
        if (taken.size < 2) {
            main.post { listener.onSaved(null) }
            return
        }
        worker.execute {
            val uri = try {
                val stitcher = PanoramaStitcher(taken, focal)
                val out = stitcher.output(stitcher.layout())
                if (out.width < 64 || out.height < 64) null else save(out.width, out.height, out::render)
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "panorama too big", e)
                null
            } catch (e: Throwable) {
                Log.w(TAG, "panorama failed", e)
                null
            }
            main.post { listener.onSaved(uri) }
        }
    }

    companion object {
        private const val TAG = "FocoPanorama"
        /** A new frame every 40 % of the view: 60 % overlap for lining up. */
        private const val STEP = 0.4f
        private const val MAX_FRAMES = 26
        private val worker = Executors.newSingleThreadExecutor { Thread(it, "foco-panorama") }
    }
}
