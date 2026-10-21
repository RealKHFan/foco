package io.github.realkhfan.foco.camera

import android.hardware.camera2.CameraMetadata
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Zoom chips (".5  1×  2  5") and their labels, like Pixel Camera. */
object ZoomMath {

    /**
     * @param teleRatios zoom ratios of telephoto lenses relative to the main lens (e.g. 4.86 on a Pixel 8 Pro).
     *                   Empty when the phone does not tell apps about its physical lenses.
     */
    fun chips(minZoom: Float, maxZoom: Float, teleRatios: List<Float>, isFront: Boolean): List<Float> {
        val chips = mutableListOf<Float>()
        if (minZoom < 0.95f) chips += minZoom
        chips += 1f
        if (!isFront) {
            if (maxZoom >= 2f) chips += 2f
            // Only the real telephoto lens gets a chip (Pixel 8 Pro: 5), like Pixel Camera.
            val tele = teleRatios.filter { it > 2.5f && it <= maxZoom + 0.05f }.minOrNull()
            if (tele != null) {
                chips += tele.roundToInt().toFloat().coerceAtMost(maxZoom)
            } else if (maxZoom >= 10f) {
                chips += 5f
            }
        }
        return chips.distinct().sorted()
    }

    /** The chip that is highlighted: the biggest chip that is not above the current zoom. */
    fun selectedIndex(chips: List<Float>, zoom: Float): Int {
        if (chips.isEmpty()) return -1
        var index = 0
        chips.forEachIndexed { i, chip -> if (zoom >= chip - 0.02f) index = i }
        return index
    }

    fun chipLabel(chip: Float, zoom: Float, selected: Boolean): String =
        if (selected) formatZoom(zoom) else formatPlain(chip)

    /** "1×", "2.3×", ".5×", "12×" */
    fun formatZoom(zoom: Float): String = formatPlain(zoom) + "×"

    private fun formatPlain(value: Float): String {
        if (value >= 10f) return value.roundToInt().toString()
        val tenths = (value * 10f).roundToInt()
        val text = if (tenths % 10 == 0) (tenths / 10).toString() else "%d.%d".format(tenths / 10, tenths % 10)
        return if (text.startsWith("0.")) text.removePrefix("0") else text
    }
}

/** A physical lens behind the (logical) back camera. */
data class LensSpec(
    val id: String,
    val focalLengthMm: Float,
    val sensorWidthMm: Float,
    val sensorHeightMm: Float,
    val aperture: Float?,
    val minFocusDiopters: Float,
) {
    /** 35 mm-equivalent focal length. */
    val equivalentFocalMm: Float
        get() {
            val diagonal = hypot(sensorWidthMm, sensorHeightMm)
            return if (diagonal <= 0f) focalLengthMm else focalLengthMm * 43.27f / diagonal
        }
}

enum class LensKind(val label: String) { ULTRAWIDE("Ultrawide"), WIDE("Wide"), TELEPHOTO("Telephoto") }

object LensMath {
    fun kindForRatio(ratio: Float): LensKind = when {
        ratio < 0.8f -> LensKind.ULTRAWIDE
        ratio > 1.6f -> LensKind.TELEPHOTO
        else -> LensKind.WIDE
    }

    /**
     * One entry per lens module: phones list some lenses twice (e.g. a cropped high-resolution mode of the
     * telephoto, which showed up as a "9" chip). Same focal length = same lens; keep the biggest sensor area.
     */
    fun distinct(lenses: List<LensSpec>): List<LensSpec> {
        val out = mutableListOf<LensSpec>()
        for (lens in lenses.sortedByDescending { it.sensorWidthMm * it.sensorHeightMm }) {
            if (out.none { abs(it.focalLengthMm - lens.focalLengthMm) <= it.focalLengthMm * 0.03f }) out += lens
        }
        return out
    }

    /** Zoom ratio of every lens relative to the main lens (the one closest to the logical camera's focal length). */
    fun ratios(lenses: List<LensSpec>, logicalEquivalentMm: Float?): Map<String, Float> {
        if (lenses.isEmpty()) return emptyMap()
        val reference = logicalEquivalentMm
            ?: lenses.minBy { abs(it.equivalentFocalMm - 26f) }.equivalentFocalMm
        val main = lenses.minBy { abs(it.equivalentFocalMm - reference) }
        return lenses.associate { it.id to it.equivalentFocalMm / main.equivalentFocalMm }
    }
}

/** Shutter speed / ISO helpers for the Pro controls. */
object ExposureMath {
    private const val NS = 1_000_000_000.0

    /** Standard third-stop shutter speeds in seconds, fastest first. */
    private val SHUTTER_STOPS_S: List<Double> = listOf(
        8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000, 800, 640, 500, 400, 320, 250,
        200, 160, 125, 100, 80, 60, 50, 40, 30, 25, 20, 15, 13, 10, 8, 6, 5, 4, 3,
    ).map { 1.0 / it } + listOf(0.4, 0.5, 0.6, 0.8, 1.0, 1.3, 1.6, 2.0, 2.5, 3.2, 4.0, 5.0, 6.0, 8.0, 10.0, 13.0, 15.0, 20.0, 25.0, 30.0)

    private val ISO_STOPS = listOf(
        25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600,
        2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800, 16000, 20000, 25600, 32000, 40000, 51200,
    )

    fun shutterValues(minNs: Long, maxNs: Long): List<Long> =
        SHUTTER_STOPS_S.map { (it * NS).roundToLong() }.filter { it in minNs..maxNs }

    fun isoValues(min: Int, max: Int): List<Int> = ISO_STOPS.filter { it in min..max }

    /** "1/125s", "1/10s", "0.5s", "2s" */
    fun formatShutter(ns: Long): String {
        val seconds = ns / NS
        return if (seconds < 0.35) {
            val denominator = 1.0 / seconds
            val text = if (denominator >= 10) denominator.roundToInt().toString()
            else trimDouble((denominator * 10).roundToInt() / 10.0)
            "1/${text}s"
        } else {
            "${trimDouble((seconds * 10).roundToInt() / 10.0)}s"
        }
    }

    private fun trimDouble(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

    /**
     * Fill in whichever of shutter/ISO the user left on auto, keeping the metered exposure
     * (shutter × ISO) constant – the same thing Pixel Camera does when you fix only one of them.
     */
    fun complete(
        manualShutterNs: Long?,
        manualIso: Int?,
        meteredShutterNs: Long,
        meteredIso: Int,
        shutterRange: LongRange,
        isoRange: IntRange,
    ): Pair<Long, Int> {
        val product = meteredShutterNs.toDouble() * meteredIso
        return when {
            manualShutterNs != null && manualIso != null ->
                manualShutterNs.coerceIn(shutterRange) to manualIso.coerceIn(isoRange)
            manualShutterNs != null -> {
                val s = manualShutterNs.coerceIn(shutterRange)
                s to (product / s).roundToInt().coerceIn(isoRange)
            }
            manualIso != null -> {
                val iso = manualIso.coerceIn(isoRange)
                (product / iso).roundToLong().coerceIn(shutterRange) to iso
            }
            else -> meteredShutterNs.coerceIn(shutterRange) to meteredIso.coerceIn(isoRange)
        }
    }

    /** "+0.7", "0", "-1.3" */
    fun formatEv(ev: Float): String {
        val tenths = (ev * 10).roundToInt()
        if (tenths == 0) return "0"
        val sign = if (tenths > 0) "+" else "-"
        val a = abs(tenths)
        return if (a % 10 == 0) "$sign${a / 10}" else "$sign${a / 10}.${a % 10}"
    }

    fun nearestIndex(values: List<Long>, target: Long): Int =
        values.indices.minByOrNull { abs(values[it] - target) } ?: 0

    fun nearestIndexInt(values: List<Int>, target: Int): Int =
        values.indices.minByOrNull { abs(values[it] - target) } ?: 0
}

object WhiteBalanceMath {
    val KELVIN_VALUES: List<Int> = (2300..9000 step 100).toList()

    /** Nearest AWB preset for phones that cannot take an exact colour temperature. */
    fun presetFor(kelvin: Int, available: Set<Int>): Int {
        val presets = listOf(
            CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT to 2700,
            CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT to 3000,
            CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT to 4000,
            CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT to 5500,
            CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to 6500,
            CameraMetadata.CONTROL_AWB_MODE_SHADE to 7500,
        ).filter { it.first in available }
        if (presets.isEmpty()) return CameraMetadata.CONTROL_AWB_MODE_AUTO
        return presets.minBy { abs(it.second - kelvin) }.first
    }
}

object FocusMath {
    /** Slider position 0 = nearest, [steps] = infinity (like Pixel: flower on the left, mountain on the right). */
    fun sliderToDiopters(position: Int, steps: Int, minFocusDiopters: Float): Float =
        (1f - position.toFloat() / steps) * minFocusDiopters

    fun dioptersToSlider(diopters: Float, steps: Int, minFocusDiopters: Float): Int =
        if (minFocusDiopters <= 0f) steps else ((1f - diopters / minFocusDiopters) * steps).roundToInt().coerceIn(0, steps)

    fun label(diopters: Float): String = when {
        diopters <= 0.05f -> "∞"
        1f / diopters >= 1f -> "%.1f m".format(1f / diopters)
        else -> "${(100f / diopters).roundToInt()} cm"
    }
}

/** Tone curve for the Shadows control. */
object ToneCurve {
    private fun srgb(x: Float): Float =
        if (x <= 0.0031308f) 12.92f * x else 1.055f * x.toDouble().pow(1 / 2.4).toFloat() - 0.055f

    /**
     * Returns interleaved (in, out) pairs for TonemapCurve. The curve is the normal sRGB curve with the
     * dark part lifted ([shadows] > 0) or pressed down ([shadows] < 0). Always monotonic.
     */
    fun shadows(shadows: Float, points: Int = 33): FloatArray {
        val s = shadows.coerceIn(-1f, 1f)
        val out = FloatArray(points * 2)
        for (i in 0 until points) {
            val x = i.toFloat() / (points - 1)
            val g = srgb(x)
            val y = (g + s * 0.8f * g * (1 - g) * (1 - g)).coerceIn(0f, 1f)
            out[i * 2] = x
            out[i * 2 + 1] = y
        }
        return out
    }
}
