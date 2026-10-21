package io.github.realkhfan.foco.processing

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class ShotKind { HDR, ZOOM, NIGHT, LONG_EXPOSURE, ACTION_PAN }

/**
 * How frames are combined. ROBUST: noise reduction that ignores anything that moved (HDR, Night, zoom).
 * BLUR_MOTION: plain average lined up on the background, so moving things blur (Long Exposure).
 * FOLLOW_SUBJECT: plain average lined up on the subject in the middle, so the background streaks (Action Pan).
 */
enum class MergeMode { ROBUST, BLUR_MOTION, FOLLOW_SUBJECT }

enum class Motion { TRIPOD, STEADY, SHAKY }

/** How a photo will be captured and processed. */
data class ShotPlan(
    val kind: ShotKind,
    val frames: Int,
    /** Manual per-frame exposure (night); null = keep auto exposure. */
    val exposureNs: Long? = null,
    val iso: Int? = null,
    /** HDR: also take one frame this many times darker to recover highlights; null = not needed. */
    val highlightDivisor: Float? = null,
    val sharpen: Float = 0f,
    /** Expected capture time, for the countdown. */
    val seconds: Float = 0f,
    val merge: MergeMode = MergeMode.ROBUST,
    /** Time-based capture: take frames for this long (frames is then the maximum). */
    val durationNs: Long? = null,
)

/** Decides between normal HDR, sharper zoom and night, and how many/which frames to take. */
object ShotPlanner {
    /** exposure (s) × ISO above which a scene counts as dark (≈ 1/30 s at ISO 900). */
    const val DARK = 30.0

    fun exposureValue(shutterNs: Long, iso: Int): Double = shutterNs / 1e9 * iso

    fun isDark(shutterNs: Long, iso: Int): Boolean = exposureValue(shutterNs, iso) >= DARK

    fun motionFor(radiansPerSecond: Float): Motion = when {
        radiansPerSecond < 0.012f -> Motion.TRIPOD
        radiansPerSecond < 0.09f -> Motion.STEADY
        else -> Motion.SHAKY
    }

    /** Night capture length chosen automatically from how dark it is and how still the phone is. */
    fun autoNightSeconds(shutterNs: Long, iso: Int, motion: Motion): Float {
        val e = exposureValue(shutterNs, iso)
        val base = when {
            e < 60 -> 1f
            e < 200 -> 2f
            e < 800 -> 3f
            e < 3000 -> 4f
            else -> 6f
        }
        return when (motion) {
            Motion.SHAKY -> min(base, 2f)
            Motion.STEADY -> base
            Motion.TRIPOD -> min(base * 1.5f, 10f)
        }
    }

    fun night(
        seconds: Float,
        meteredShutterNs: Long,
        meteredIso: Int,
        motion: Motion,
        shutterMaxNs: Long,
        isoMin: Int,
        isoMax: Int,
    ): ShotPlan {
        val maxFrameNs = when (motion) {
            Motion.TRIPOD -> 1_000_000_000L
            Motion.STEADY -> 333_333_333L
            Motion.SHAKY -> 125_000_000L
        }.coerceAtMost(shutterMaxNs).coerceAtLeast(33_333_333L)
        // Each frame twice as bright as the normal auto exposure (night mode looks brighter than the eye sees).
        val target = exposureValue(meteredShutterNs, meteredIso) * 2.0
        var t = maxFrameNs / 1e9
        var iso = target / t
        val isoCap = min(isoMax, 6400).coerceAtLeast(isoMin)
        if (iso < isoMin) {
            iso = isoMin.toDouble()
            t = (target / iso).coerceAtLeast(1 / 60.0)
        }
        iso = iso.coerceIn(isoMin.toDouble(), isoCap.toDouble())
        val frameNs = (t * 1e9).toLong().coerceIn(16_666_666L, maxFrameNs)
        val frames = (seconds / (frameNs / 1e9f)).roundToInt().coerceIn(3, 15)
        return ShotPlan(
            kind = ShotKind.NIGHT,
            frames = frames,
            exposureNs = frameNs,
            iso = iso.roundToInt(),
            sharpen = 0.25f,
            seconds = frames * frameNs / 1e9f,
        )
    }

    /** Normal photo: a short burst merged for less noise; extra dark frame when highlights are clipped. */
    fun daylight(clippedFraction: Float, digitalZoom: Float, zoomDetail: Boolean): ShotPlan {
        val zoom = zoomDetail && digitalZoom >= 1.4f
        return ShotPlan(
            kind = if (zoom) ShotKind.ZOOM else ShotKind.HDR,
            frames = if (zoom) 8 else 5,
            highlightDivisor = if (clippedFraction > 0.002f) 4f else null,
            sharpen = if (zoom) ((digitalZoom - 1f) * 0.45f).coerceIn(0.35f, 1.1f) else 0.2f,
            seconds = 0.3f,
        )
    }

    /**
     * Long Exposure: about 3 s of frames averaged, so water, traffic and crowds blur while the rest stays sharp.
     * In the dark, each frame is exposed longer too (smoother light trails).
     */
    fun longExposure(dark: Boolean, meteredShutterNs: Long, meteredIso: Int, motion: Motion, shutterMaxNs: Long, isoMin: Int, isoMax: Int): ShotPlan {
        val seconds = 3f
        if (dark) {
            val n = night(seconds, meteredShutterNs, meteredIso, motion, shutterMaxNs, isoMin, isoMax)
            return n.copy(kind = ShotKind.LONG_EXPOSURE, frames = 90, merge = MergeMode.BLUR_MOTION, durationNs = (seconds * 1e9).toLong(), sharpen = 0.15f)
        }
        return ShotPlan(
            kind = ShotKind.LONG_EXPOSURE, frames = 90, sharpen = 0.15f, seconds = seconds,
            merge = MergeMode.BLUR_MOTION, durationNs = (seconds * 1e9).toLong(),
        )
    }

    /** Action Pan: follow a moving subject for about a second; the subject stays sharp, the background streaks. */
    fun actionPan(): ShotPlan = ShotPlan(
        kind = ShotKind.ACTION_PAN, frames = 40, sharpen = 0.2f, seconds = 1.2f,
        merge = MergeMode.FOLLOW_SUBJECT, durationNs = 1_200_000_000L,
    )

    /** Night brightness: lift the merged picture until its median sits at a pleasant night level. */
    fun nightGain(medianLinear: Float): Float = (0.12f / max(medianLinear, 1e-4f)).coerceIn(1f, 8f)
}
