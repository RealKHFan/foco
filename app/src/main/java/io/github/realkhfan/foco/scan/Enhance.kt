package io.github.realkhfan.foco.scan

import io.github.realkhfan.foco.processing.Parallel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How a scanned page looks. */
enum class ScanFilter(val label: String) {
    ORIGINAL("Original"), COLOR("Color"), GRAY("Grayscale"), BW("Black & white"),
}

/**
 * Page clean-up on ARGB pixels. The paper's own brightness is estimated (ignoring the text) and divided out,
 * which removes shadows and uneven light and makes the paper white; then text is darkened a little.
 */
object Enhance {
    fun apply(px: IntArray, w: Int, h: Int, filter: ScanFilter) {
        if (filter == ScanFilter.ORIGINAL) return
        val luma = FloatArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            luma[i] = 0.299f * ((c shr 16) and 0xff) + 0.587f * ((c shr 8) and 0xff) + 0.114f * (c and 0xff)
        }
        val bg = background(luma, w, h)
        // Black point: the darkest few percent of the flattened page become black (text gets crisp).
        val flat = FloatArray(min(luma.size, 40_000))
        val step = max(1, luma.size / flat.size)
        var n = 0
        var i = 0
        while (i < luma.size && n < flat.size) { flat[n++] = luma[i] / bg[i] * 255f; i += step }
        flat.sort(0, n)
        val black = flat[(n * 0.02f).toInt()].coerceIn(0f, 110f)
        val white = 242f
        val scale = 255f / (white - black)

        when (filter) {
            ScanFilter.COLOR -> Parallel.rows(h) { from, to ->
                for (k in from * w until to * w) {
                    val c = px[k]
                    val gain = 255f / bg[k]
                    var r = ((c shr 16) and 0xff) * gain
                    var g = ((c shr 8) and 0xff) * gain
                    var b = (c and 0xff) * gain
                    // Contrast on brightness, a little more colour so stamps and highlights stay visible.
                    val l = 0.299f * r + 0.587f * g + 0.114f * b
                    val l2 = ((l - black) * scale).coerceIn(0f, 255f)
                    val s = if (l > 1f) l2 / l else 0f
                    r = l2 + (r * s - l2) * 1.15f
                    g = l2 + (g * s - l2) * 1.15f
                    b = l2 + (b * s - l2) * 1.15f
                    px[k] = argb(r, g, b)
                }
            }
            ScanFilter.GRAY -> Parallel.rows(h) { from, to ->
                for (k in from * w until to * w) {
                    val v = ((luma[k] / bg[k] * 255f - black) * scale).coerceIn(0f, 255f)
                    px[k] = argb(v, v, v)
                }
            }
            ScanFilter.BW -> {
                val flatLuma = FloatArray(w * h) { ((luma[it] / bg[it] * 255f - black) * scale).coerceIn(0f, 255f) }
                threshold(flatLuma, w, h, px)
            }
            ScanFilter.ORIGINAL -> Unit
        }
    }

    /** Brightness of the bare paper everywhere: maximum filter (removes text), then smoothing, at low resolution. */
    fun background(luma: FloatArray, w: Int, h: Int): FloatArray {
        // Blocks of ≈1/100 of the page, grown by 4 blocks: wide enough that headings, logos and thick lines
        // aren't mistaken for shadow, fine enough to follow the edge of a hand's shadow.
        val f = max(4, max(w, h) / 100)
        val sw = (w + f - 1) / f
        val sh = (h + f - 1) / f
        val small = FloatArray(sw * sh)
        // Brightest pixel of each block: paper rather than ink.
        for (sy in 0 until sh) for (sx in 0 until sw) {
            var m = 0f
            val y0 = sy * f; val x0 = sx * f
            for (y in y0 until min(h, y0 + f) step 2) for (x in x0 until min(w, x0 + f) step 2) m = max(m, luma[y * w + x])
            small[sy * sw + sx] = m
        }
        val grown = maxFilter(small, sw, sh, 4)
        val smooth = boxBlur(boxBlur(grown, sw, sh, 3), sw, sh, 3)
        val out = FloatArray(w * h)
        Parallel.rows(h) { from, to ->
            for (y in from until to) {
                val fy = ((y + 0.5f) / f - 0.5f).coerceIn(0f, sh - 1f)
                val iy = min(fy.toInt(), sh - 2).coerceAtLeast(0)
                val ay = fy - iy
                for (x in 0 until w) {
                    val fx = ((x + 0.5f) / f - 0.5f).coerceIn(0f, sw - 1f)
                    val ix = min(fx.toInt(), sw - 2).coerceAtLeast(0)
                    val ax = fx - ix
                    val p = iy * sw + ix
                    val x1 = if (sw > 1) 1 else 0
                    val y1 = if (sh > 1) sw else 0
                    val top = smooth[p] + (smooth[p + x1] - smooth[p]) * ax
                    val bottom = smooth[p + y1] + (smooth[p + y1 + x1] - smooth[p + y1]) * ax
                    out[y * w + x] = max(40f, top + (bottom - top) * ay)
                }
            }
        }
        return out
    }

    /**
     * Black & white with a local threshold (Bradley): a pixel is ink if clearly darker than its surroundings,
     * or dark in any case (so large dark areas stay solid).
     */
    private fun threshold(l: FloatArray, w: Int, h: Int, px: IntArray) {
        val integral = DoubleArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0.0
            for (x in 0 until w) {
                row += l[y * w + x]
                integral[(y + 1) * (w + 1) + x + 1] = integral[y * (w + 1) + x + 1] + row
            }
        }
        val r = max(8, max(w, h) / 40)
        val white = 0xffffffff.toInt()
        val ink = 0xff000000.toInt()
        Parallel.rows(h) { from, to ->
            for (y in from until to) {
                val y0 = max(0, y - r); val y1 = min(h, y + r + 1)
                for (x in 0 until w) {
                    val x0 = max(0, x - r); val x1 = min(w, x + r + 1)
                    val sum = integral[y1 * (w + 1) + x1] - integral[y0 * (w + 1) + x1] - integral[y1 * (w + 1) + x0] + integral[y0 * (w + 1) + x0]
                    val mean = sum / ((x1 - x0) * (y1 - y0))
                    val v = l[y * w + x]
                    // Clearly dark is always ink (also inside big dark areas); otherwise darker than around it.
                    px[y * w + x] = if (v < 110f || v < mean * 0.85 && v < 215) ink else white
                }
            }
        }
    }

    private fun maxFilter(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            var m = 0f
            for (d in -r..r) m = max(m, src[y * w + (x + d).coerceIn(0, w - 1)])
            tmp[y * w + x] = m
        }
        for (y in 0 until h) for (x in 0 until w) {
            var m = 0f
            for (d in -r..r) m = max(m, tmp[(y + d).coerceIn(0, h - 1) * w + x])
            out[y * w + x] = m
        }
        return out
    }

    private fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            for (d in -r..r) s += src[y * w + (x + d).coerceIn(0, w - 1)]
            tmp[y * w + x] = s / (2 * r + 1)
        }
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            for (d in -r..r) s += tmp[(y + d).coerceIn(0, h - 1) * w + x]
            out[y * w + x] = s / (2 * r + 1)
        }
        return out
    }

    private fun argb(r: Float, g: Float, b: Float): Int =
        (0xff shl 24) or (r.roundToInt().coerceIn(0, 255) shl 16) or (g.roundToInt().coerceIn(0, 255) shl 8) or b.roundToInt().coerceIn(0, 255)
}
