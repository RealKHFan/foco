package io.github.realkhfan.foco.processing

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.ImageProxy
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * One processed photo: takes frames from the camera's analysis stream, lines each up with the first,
 * merges them, then renders and saves the result in the background.
 *
 * Threads: [offer] runs on the camera analysis thread and only copies; aligning/merging runs on [mergeThread];
 * rendering/encoding on [encodeThread] so the next photo can already start.
 */
class BurstSession(
    val plan: ShotPlan,
    private val rotation: Int,
    private val mirror: Boolean,
    private val crop: (width: Int, height: Int) -> IntArray?,
    private val tone: ToneSettings,
    private val ultraHdr: Boolean,
    /** Called on [mergeThread] when the frames are in; renders, encodes and saves. Returns the saved URI as a string. */
    private val finish: (renderer: Renderer, ultraHdr: Boolean, meta: FrameStats) -> String?,
    private val listener: Listener,
) {
    interface Listener {
        fun onFirstFrame(preview: Bitmap) {}
        fun onProgress(done: Int, total: Int) {}
        /** All frames taken: the camera can go back to normal. */
        fun onCaptured() {}
        fun onSaved(uri: String?) {}
    }

    /** Exposure of the frames that were used (for Exif). */
    data class FrameStats(val exposureNs: Long, val iso: Int, val frames: Int)

    private enum class Phase { NORMAL, HIGHLIGHT, DONE }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var phase = Phase.NORMAL
    private val accepted = AtomicInteger(0)
    private val inFlight = AtomicInteger(0)
    private val pool = ArrayDeque<YuvFrame>()
    @Volatile private var reference: YuvFrame? = null
    private val finished = java.util.concurrent.atomic.AtomicBoolean(false)
    private var refPyramid: LumaPyramid? = null
    private var accumulator: MergeAccumulator? = null
    private var highlight: HighlightFrame? = null
    private var exposureNs = 0L
    private var iso = 0
    @Volatile private var stopRequested = false
    @Volatile private var firstTimestamp = 0L

    /** Exposure the highlight frame must have (set by the engine when it switches the camera). */
    @Volatile var highlightTargetNs: Long = 0L

    val wantsHighlight: Boolean get() = phase == Phase.HIGHLIGHT
    val isDone: Boolean get() = phase == Phase.DONE

    /**
     * Called for every analysis frame while the session runs. [exposureNs]/[iso] come from the matching
     * capture result (0 if unknown). Returns true while more frames are wanted.
     */
    fun offer(image: ImageProxy, frameExposureNs: Long, frameIso: Int): Boolean {
        when (phase) {
            Phase.DONE -> return false
            Phase.NORMAL -> {
                val want = plan.exposureNs
                if (want != null && frameExposureNs > 0 && abs(frameExposureNs - want) > want * 0.15) return true
                if (want != null && plan.iso != null && frameIso > 0 && abs(frameIso - plan.iso) > plan.iso * 0.2) return true
                val duration = plan.durationNs
                val ts = image.imageInfo.timestamp
                if (firstTimestamp == 0L) firstTimestamp = ts
                val elapsed = ts - firstTimestamp
                if (duration != null) {
                    main.post { listener.onProgress((elapsed * 1000 / duration).toInt().coerceIn(0, 1000), 1000) }
                    if (elapsed >= duration) {
                        moveOn()
                        return false
                    }
                }
                // Daylight HDR: grab all frames at once (≈0.2 s, merged afterwards). Night frames come slowly anyway.
                // Time-based modes: take frames as fast as they can be merged, spread over the whole time.
                val maxInFlight = when {
                    duration != null -> 3
                    plan.exposureNs == null -> plan.frames
                    else -> 4
                }
                val buffer = synchronized(pool) { pool.removeFirstOrNull() }
                    ?: if (inFlight.get() < maxInFlight) YuvFrame(image.width, image.height) else return true
                if (buffer.width != image.width || buffer.height != image.height) return true
                FrameCopy.copy(image, buffer)
                buffer.exposureNs = frameExposureNs
                buffer.iso = frameIso
                val index = accepted.incrementAndGet()
                inFlight.incrementAndGet()
                mergeThread.execute { mergeFrame(buffer, index) }
                if (index >= plan.frames || stopRequested) moveOn()
                return phase != Phase.DONE
            }
            Phase.HIGHLIGHT -> {
                val want = highlightTargetNs
                if (want == 0L || frameExposureNs <= 0 || abs(frameExposureNs - want) > want * 0.2) return true
                val frame = YuvFrame(image.width, image.height)
                FrameCopy.copy(image, frame)
                frame.exposureNs = frameExposureNs
                frame.iso = frameIso
                mergeThread.execute { mergeHighlight(frame) }
                finishOnce()
                return false
            }
        }
    }

    /** Stop early (shutter tapped again, or time ran out) and save what we have. */
    fun stop() {
        stopRequested = true
        finishOnce()
    }

    private fun moveOn() {
        if (plan.highlightDivisor != null && !stopRequested) phase = Phase.HIGHLIGHT else finishOnce()
    }

    /** Ends capturing exactly once; merging of the frames already taken continues, then the photo is saved. */
    private fun finishOnce() {
        if (!finished.compareAndSet(false, true)) return
        phase = Phase.DONE
        main.post { listener.onCaptured() }
        mergeThread.execute { complete() }
    }

    private fun mergeFrame(frame: YuvFrame, index: Int) {
        try {
            val ref = reference
            if (ref == null) {
                reference = frame
                refPyramid = LumaPyramid(frame)
                accumulator = MergeAccumulator(frame)
                exposureNs = frame.exposureNs
                iso = frame.iso
                val preview = runCatching { PhotoWriter.previewBitmap(frame, rotation, mirror, crop(frame.width, frame.height)) }.getOrNull()
                if (preview != null) main.post { listener.onFirstFrame(preview) }
            } else {
                val pyramid = LumaPyramid(frame)
                when (plan.merge) {
                    MergeMode.ROBUST -> {
                        val result = Aligner.align(refPyramid!!, pyramid)
                        if (result.reliable) accumulator!!.add(frame, result.transform, MergeAccumulator.sigmaForIso(maxOf(iso, frame.iso)))
                    }
                    MergeMode.BLUR_MOTION -> {
                        val result = Aligner.align(refPyramid!!, pyramid)
                        accumulator!!.addAverage(frame, if (result.reliable) result.transform else Affine.IDENTITY)
                    }
                    MergeMode.FOLLOW_SUBJECT -> {
                        val rough = Aligner.alignRegion(refPyramid!!, pyramid).transform
                        // A patch of about 1/5 of the frame in the middle: on the subject, away from the moving background.
                        val exact = Aligner.refineTranslation(reference!!, frame, rough, frame.width / 2, frame.height / 2, half = minOf(frame.width, frame.height) / 10)
                        accumulator!!.addAverage(frame, exact)
                    }
                }
                synchronized(pool) { pool.addLast(frame) }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "merge failed", e)
        } finally {
            inFlight.decrementAndGet()
            if (plan.durationNs == null) main.post { listener.onProgress(index, plan.frames) }
        }
    }

    private fun mergeHighlight(frame: YuvFrame) {
        val pyr = refPyramid ?: return
        val ref = reference ?: return
        runCatching {
            val result = Aligner.align(pyr, LumaPyramid(frame))
            if (!result.reliable) return
            highlight = HighlightFrame(frame, result.transform, measureRatio(ref, frame, result.transform))
        }.onFailure { Log.w(TAG, "highlight frame failed", it) }
    }

    /** How much darker the highlight frame is, measured on well-exposed pixels (robust to the camera's tone curve). */
    private fun measureRatio(ref: YuvFrame, short: YuvFrame, t: Affine): Float {
        val ratios = FloatArray(2048)
        var n = 0
        val step = maxOf(1, ref.y.size / 20000)
        var i = 0
        while (i < ref.y.size && n < ratios.size) {
            val yRef = ref.y[i].toInt() and 0xff
            if (yRef in 90..200) {
                val x = (i % ref.width).toFloat()
                val y = (i / ref.width).toFloat()
                val fx = t.mapX(x, y).toInt()
                val fy = t.mapY(x, y).toInt()
                if (fx in 0 until short.width && fy in 0 until short.height) {
                    val ys = short.y[fy * short.width + fx].toInt() and 0xff
                    if (ys > 12) ratios[n++] = Srgb.lin(yRef / 255f) / Srgb.lin(ys / 255f)
                }
            }
            i += step
        }
        if (n < 50) return plan.highlightDivisor ?: 4f
        ratios.sort(0, n)
        return ratios[n / 2].coerceIn(1.5f, 16f)
    }

    private fun complete() {
        val acc = accumulator
        if (acc == null) {
            main.post { listener.onSaved(null) }
            return
        }
        val merged = try {
            acc.finish()
        } catch (e: Throwable) {
            Log.w(TAG, "finish failed", e)
            main.post { listener.onSaved(null) }
            return
        }
        accumulator = null
        val ref = reference!!
        reference = null
        refPyramid = null
        synchronized(pool) { pool.clear() }
        val stats = FrameStats(exposureNs, iso, merged.frames)
        val h = highlight
        highlight = null
        encodeThread.execute {
            val uri = try {
                var t = tone
                if (t.night) t = t.copy(gain = ShotPlanner.nightGain(merged.medianLinearLuma(Srgb.levelToLinear)))
                val renderer = Renderer(merged, t, h, crop(ref.width, ref.height), rotation, mirror)
                finish(renderer, ultraHdr, stats)
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "out of memory while saving", e)
                null
            } catch (e: Throwable) {
                Log.w(TAG, "saving failed", e)
                null
            }
            main.post { listener.onSaved(uri) }
        }
    }

    companion object {
        private const val TAG = "FocoBurst"
        private val mergeThread: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "foco-merge") }
        private val encodeThread: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "foco-encode") }
    }
}
