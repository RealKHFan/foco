package io.github.realkhfan.foco.processing

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tan

/** Frame helpers for panoramas. */
object YuvOps {
    /**
     * Half-size, upright copy of a camera frame: 2×2 averaging, then turned by [rotation] degrees clockwise
     * (same convention as [Renderer]). Width and height of the source must be multiples of 4.
     */
    fun downscaleRotate(src: YuvFrame, rotation: Int): YuvFrame {
        val hw = src.width / 2
        val hh = src.height / 2
        val swap = rotation == 90 || rotation == 270
        val dw = if (swap) hh else hw
        val dh = if (swap) hw else hh
        val dst = YuvFrame(dw, dh)
        val sw = src.width
        // Output (ox, oy) → half-size, not yet rotated (cx, cy).
        fun mapX(ox: Int, oy: Int, w: Int, h: Int): Int = when (rotation) {
            90 -> oy
            180 -> w - 1 - ox
            270 -> w - 1 - oy
            else -> ox
        }
        fun mapY(ox: Int, oy: Int, w: Int, h: Int): Int = when (rotation) {
            90 -> h - 1 - ox
            180 -> h - 1 - oy
            270 -> ox
            else -> oy
        }
        Parallel.rows(dh) { from, to ->
            for (oy in from until to) {
                val o = oy * dw
                for (ox in 0 until dw) {
                    val cx = mapX(ox, oy, hw, hh)
                    val cy = mapY(ox, oy, hw, hh)
                    val p = (cy * 2) * sw + cx * 2
                    val sum = (src.y[p].toInt() and 0xff) + (src.y[p + 1].toInt() and 0xff) +
                        (src.y[p + sw].toInt() and 0xff) + (src.y[p + sw + 1].toInt() and 0xff)
                    dst.y[o + ox] = ((sum + 2) shr 2).toByte()
                }
            }
        }
        // Chroma: the source chroma plane is already half size, so 2×2 averaging gives the new chroma size.
        val scw = src.chromaWidth
        val dcw = dst.chromaWidth
        val dch = dst.chromaHeight
        val hcw = hw / 2
        val hch = hh / 2
        for (oy in 0 until dch) {
            for (ox in 0 until dcw) {
                val cx = mapX(ox, oy, hcw, hch)
                val cy = mapY(ox, oy, hcw, hch)
                val p = (cy * 2) * scw + cx * 2
                val o = oy * dcw + ox
                dst.u[o] = (((src.u[p].toInt() and 0xff) + (src.u[p + 1].toInt() and 0xff) +
                    (src.u[p + scw].toInt() and 0xff) + (src.u[p + scw + 1].toInt() and 0xff) + 2) shr 2).toByte()
                dst.v[o] = (((src.v[p].toInt() and 0xff) + (src.v[p + 1].toInt() and 0xff) +
                    (src.v[p + scw].toInt() and 0xff) + (src.v[p + scw + 1].toInt() and 0xff) + 2) shr 2).toByte()
            }
        }
        dst.timestampNs = src.timestampNs
        return dst
    }

    /** Small ARGB picture of a frame (every [step]-th pixel) for the live panorama strip. */
    fun thumbnail(frame: YuvFrame, step: Int): Triple<IntArray, Int, Int> {
        val w = frame.width / step
        val h = frame.height / step
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val sx = x * step
            val sy = y * step
            val yy = (frame.y[sy * frame.width + sx].toInt() and 0xff).toFloat()
            val ci = (sy / 2) * frame.chromaWidth + sx / 2
            val u = (frame.u[ci].toInt() and 0xff) - 128f
            val v = (frame.v[ci].toInt() and 0xff) - 128f
            out[y * w + x] = rgb(yy + 1.402f * v, yy - 0.344136f * u - 0.714136f * v, yy + 1.772f * u)
        }
        return Triple(out, w, h)
    }

    fun rgb(r: Float, g: Float, b: Float): Int {
        val ri = (r + 0.5f).toInt().coerceIn(0, 255)
        val gi = (g + 0.5f).toInt().coerceIn(0, 255)
        val bi = (b + 0.5f).toInt().coerceIn(0, 255)
        return (0xff shl 24) or (ri shl 16) or (gi shl 8) or bi
    }
}

/** One panorama frame: upright, with the phone's turn ([yaw], to the right = positive) and tilt when it was taken. */
class PanoShot(val frame: YuvFrame, val yaw: Float, val pitch: Float)

/**
 * Stitches panorama frames: each frame is projected onto a cylinder (so straight lines stay straight when you
 * turn), lined up with its neighbour (the gyroscope gives the first guess, the pictures the exact position),
 * evened out in brightness and joined with a soft seam in the middle of each overlap.
 *
 * @param focal focal length in pixels of the frames.
 */
class PanoramaStitcher(shotsIn: List<PanoShot>, private val focal: Float) {
    private val shots: List<PanoShot>
    private val w: Int
    private val h: Int
    private val thetaMax: Float
    /** Width of one frame on the cylinder. */
    val cylWidth: Int

    init {
        require(shotsIn.size >= 2) { "need at least two frames" }
        shots = shotsIn.sortedBy { it.yaw }
        w = shots[0].frame.width
        h = shots[0].frame.height
        thetaMax = atan(w / 2f / focal)
        cylWidth = (2 * focal * thetaMax).toInt()
    }

    class Layout(val order: List<PanoShot>, val xs: FloatArray, val ys: FloatArray, val gains: FloatArray, val confident: BooleanArray)

    /** A cylinder-projected grey picture of a frame at 1/[k] size (NaN where the frame has no data). */
    private class Warp(val width: Int, val height: Int, val data: FloatArray)

    private fun warp(shot: PanoShot, k: Int): Warp {
        val f = focal / k
        val ww = cylWidth / k
        val hh = h / k
        val srcW = w / k
        val srcH = h / k
        // Box-downscaled luma of the frame.
        val small = FloatArray(srcW * srcH)
        val y = shot.frame.y
        for (sy in 0 until srcH) for (sx in 0 until srcW) {
            var s = 0
            for (dy in 0 until k) {
                val row = (sy * k + dy) * w + sx * k
                for (dx in 0 until k) s += y[row + dx].toInt() and 0xff
            }
            small[sy * srcW + sx] = s.toFloat() / (k * k)
        }
        val out = FloatArray(ww * hh)
        Parallel.rows(ww) { from, to ->
            for (xc in from until to) {
                val theta = (xc - ww / 2f) / f
                val xs = f * tan(theta) + srcW / 2f
                val inv = 1f / cos(theta)
                for (yc in 0 until hh) {
                    val ys = (yc - hh / 2f) * inv + srcH / 2f
                    out[yc * ww + xc] = if (xs < 0f || ys < 0f || xs >= srcW - 1 || ys >= srcH - 1) {
                        Float.NaN
                    } else {
                        val ix = xs.toInt(); val iy = ys.toInt(); val ax = xs - ix; val ay = ys - iy
                        val p = iy * srcW + ix
                        val top = small[p] + (small[p + 1] - small[p]) * ax
                        val bottom = small[p + srcW] + (small[p + srcW + 1] - small[p + srcW]) * ax
                        top + (bottom - top) * ay
                    }
                }
            }
        }
        return Warp(ww, hh, out)
    }

    /** Mean absolute difference where both overlap with B shifted by (dx, dy); MAX if the overlap is too small. */
    private fun cost(a: Warp, b: Warp, dx: Float, dy: Float, step: Int): Float {
        var sum = 0.0
        var n = 0
        val x0 = max(0, ceil(dx).toInt())
        val x1 = min(a.width, (b.width + dx).toInt())
        val y0 = max(0, ceil(dy).toInt())
        val y1 = min(a.height, (b.height + dy).toInt())
        if (x1 - x0 < a.width * 0.12f || y1 - y0 < a.height * 0.5f) return Float.MAX_VALUE
        var yy = y0
        while (yy < y1 - 1) {
            val by = yy - dy
            val iby = by.toInt()
            val ay = by - iby
            var xx = x0
            while (xx < x1 - 1) {
                val av = a.data[yy * a.width + xx]
                val bx = xx - dx
                val ibx = bx.toInt()
                if (!av.isNaN() && ibx >= 0 && iby >= 0 && ibx < b.width - 1 && iby < b.height - 1) {
                    val ax = bx - ibx
                    val p = iby * b.width + ibx
                    val p00 = b.data[p]; val p10 = b.data[p + 1]; val p01 = b.data[p + b.width]; val p11 = b.data[p + b.width + 1]
                    if (!p00.isNaN() && !p10.isNaN() && !p01.isNaN() && !p11.isNaN()) {
                        val top = p00 + (p10 - p00) * ax
                        val bottom = p01 + (p11 - p01) * ax
                        sum += abs(av - (top + (bottom - top) * ay))
                        n++
                    }
                }
                xx += step
            }
            yy += step
        }
        return if (n < 200) Float.MAX_VALUE else (sum / n).toFloat()
    }

    private fun meanOverlap(a: Warp, b: Warp, dx: Float, dy: Float): Pair<Float, Float> {
        var sa = 0.0; var sb = 0.0; var n = 0
        val x0 = max(0, ceil(dx).toInt()); val x1 = min(a.width, (b.width + dx).toInt())
        val y0 = max(0, ceil(dy).toInt()); val y1 = min(a.height, (b.height + dy).toInt())
        for (yy in y0 until y1 step 2) for (xx in x0 until x1 step 2) {
            val av = a.data[yy * a.width + xx]
            val bxi = (xx - dx).roundToInt(); val byi = (yy - dy).roundToInt()
            if (bxi < 0 || byi < 0 || bxi >= b.width || byi >= b.height) continue
            val bv = b.data[byi * b.width + bxi]
            if (av.isNaN() || bv.isNaN()) continue
            sa += av; sb += bv; n++
        }
        return if (n == 0) 1f to 1f else (sa / n).toFloat() to (sb / n).toFloat()
    }

    private data class Match(val dx: Float, val dy: Float, val cost: Float, val confident: Boolean)

    /** Exhaustive search on a grid; returns the best shift and whether it clearly beats the others. */
    private fun search(a: Warp, b: Warp, cx: Float, cy: Float, rx: Int, ry: Int, step: Int): Match {
        val nx = 2 * rx + 1
        val ny = 2 * ry + 1
        val costs = FloatArray(nx * ny)
        Parallel.rows(ny) { from, to ->
            for (j in from until to) for (i in 0 until nx) costs[j * nx + i] = cost(a, b, cx + i - rx, cy + j - ry, step)
        }
        var best = 0
        for (i in costs.indices) if (costs[i] < costs[best]) best = i
        val bi = best % nx
        val bj = best / nx
        val valid = costs.filter { it < Float.MAX_VALUE }.sorted()
        if (valid.isEmpty()) return Match(cx, cy, Float.MAX_VALUE, false)
        val median = valid[valid.size / 2]
        var dx = cx + bi - rx
        var dy = cy + bj - ry
        if (bi in 1 until nx - 1) dx += parabola(costs[best - 1], costs[best], costs[best + 1])
        if (bj in 1 until ny - 1) dy += parabola(costs[best - nx], costs[best], costs[best + nx])
        val confident = costs[best] < median * 0.75f
        return Match(dx, dy, costs[best], confident)
    }

    private fun parabola(l: Float, m: Float, r: Float): Float {
        if (l == Float.MAX_VALUE || r == Float.MAX_VALUE) return 0f
        val d = l - 2 * m + r
        return if (d <= 1e-6f) 0f else (0.5f * (l - r) / d).coerceIn(-0.5f, 0.5f)
    }

    /** Positions of all frames on the cylinder (pixels at frame scale), brightness gains and confidence. */
    fun layout(progress: (Float) -> Unit = {}): Layout {
        val n = shots.size
        var order = shots
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        val gains = FloatArray(n) { 1f }
        val confident = BooleanArray(n) { true }
        var prev8 = warp(order[0], 8)
        var prev2 = warp(order[0], 2)
        var flip = 1f
        var directionChecked = false
        var i = 1
        while (i < n) {
            val cur8 = warp(order[i], 8)
            val predDx = flip * focal * (order[i].yaw - order[i - 1].yaw) / 8f
            val predDy = -focal * (order[i].pitch - order[i - 1].pitch) / 8f
            val rx = max(6, (cur8.width * 0.18f).toInt())
            val ry = max(5, (cur8.height * 0.07f + abs(predDy) * 1.5f).toInt())
            var match = search(prev8, cur8, predDx, 0f, rx, ry, 2)
            if (!directionChecked) {
                directionChecked = true
                // In case the gyroscope's direction is the other way round on this phone: try the mirror guess too.
                val other = search(prev8, cur8, -predDx, 0f, rx, ry, 2)
                if (other.cost < match.cost * 0.9f) {
                    // Frames were sorted the wrong way round: reverse them and start again.
                    flip = -1f
                    order = order.reversed()
                    prev8 = warp(order[0], 8)
                    prev2 = warp(order[0], 2)
                    i = 1
                    continue
                }
            }
            val coarseDx = if (match.confident) match.dx else predDx
            val coarseDy = if (match.confident) match.dy else predDy
            val cur2 = warp(order[i], 2)
            val fine = search(prev2, cur2, coarseDx * 4f, coarseDy * 4f, 4, 4, 3)
            val dx = (if (fine.cost < Float.MAX_VALUE) fine.dx else coarseDx * 4f) * 2f
            val dy = (if (fine.cost < Float.MAX_VALUE) fine.dy else coarseDy * 4f) * 2f
            xs[i] = xs[i - 1] + dx
            ys[i] = ys[i - 1] + dy
            confident[i] = match.confident
            val (ma, mb) = meanOverlap(prev8, cur8, dx / 8f, dy / 8f)
            gains[i] = (gains[i - 1] * (if (mb > 1f) ma / mb else 1f)).coerceIn(0.8f, 1.25f)
            prev8 = cur8
            prev2 = cur2
            progress(i.toFloat() / (n - 1) * 0.5f)
            i++
        }
        return Layout(order, xs, ys, gains, confident)
    }

    /** The finished panorama: [render] fills rows of ARGB pixels. */
    inner class Output(private val layout: Layout) {
        private val n = layout.order.size
        private val trim = (cylWidth * 0.08f).toInt()
        private val seams = FloatArray(max(0, n - 1))
        private val band: Float
        val left: Int
        val width: Int
        val top: Int
        val height: Int

        init {
            var minOverlap = Float.MAX_VALUE
            for (k in 0 until n - 1) {
                val overlapStart = layout.xs[k + 1]
                val overlapEnd = layout.xs[k] + cylWidth
                seams[k] = (overlapStart + overlapEnd) / 2f
                minOverlap = min(minOverlap, overlapEnd - overlapStart)
            }
            band = if (minOverlap == Float.MAX_VALUE) 0f else (minOverlap / 2f).coerceIn(4f, 96f)
            left = (layout.xs[0] + trim).toInt()
            val right = (layout.xs[n - 1] + cylWidth - trim).toInt()
            width = max(0, right - left)
            var t = -1e9f
            var b = 1e9f
            for (k in 0 until n) {
                val from = if (k == 0) layout.xs[0] + trim else seams[k - 1] - band / 2
                val to = if (k == n - 1) layout.xs[n - 1] + cylWidth - trim else seams[k] + band / 2
                val maxTheta = max(abs(from - layout.xs[k] - cylWidth / 2f), abs(to - layout.xs[k] - cylWidth / 2f)) / focal
                val c = cos(maxTheta.coerceAtMost(thetaMax))
                t = max(t, layout.ys[k] + h / 2f * (1 - c))
                b = min(b, layout.ys[k] + h / 2f * (1 + c))
            }
            top = ceil(t).toInt() + 1
            height = max(0, b.toInt() - top - 1) and 1.inv()
        }

        fun render(row0: Int, rows: Int, out: IntArray) {
            Parallel.rows(width) { from, to ->
                val pa = FloatArray(2)
                val pb = FloatArray(2)
                val pc = FloatArray(2)
                for (col in from until to) {
                    val x = (left + col).toFloat()
                    var k = 0
                    while (k < n - 1 && x >= seams[k] + band / 2) k++
                    var other = -1
                    var alpha = 0f
                    if (k < n - 1 && x > seams[k] - band / 2) {
                        other = k + 1
                        alpha = smoothstep(seams[k] - band / 2, seams[k] + band / 2, x)
                    }
                    val hasA = column(k, x, pa)
                    val hasB = other >= 0 && column(other, x, pb)
                    // Fallback when the main frame has no data at this column (shouldn't happen inside the crop).
                    val spare = if (k + 1 < n) k + 1 else k - 1
                    val hasC = spare >= 0 && column(spare, x, pc)
                    for (r in 0 until rows) {
                        val y = (top + row0 + r).toFloat()
                        val a = if (hasA) sample(k, pa, y) else 0
                        var c = if (hasB) {
                            val b = sample(other, pb, y)
                            when {
                                a == 0 -> b
                                b == 0 -> a
                                else -> mix(a, b, alpha)
                            }
                        } else {
                            a
                        }
                        if (c == 0 && hasC) c = sample(spare, pc, y)
                        out[r * width + col] = if (c == 0) BLACK else c
                    }
                }
            }
        }

        /** Per-column part of the cylinder → frame mapping: source x and 1/cos(θ). False if outside the frame. */
        private fun column(k: Int, x: Float, outParams: FloatArray): Boolean {
            val theta = (x - layout.xs[k] - cylWidth / 2f) / focal
            if (abs(theta) >= thetaMax) return false
            val xs = focal * tan(theta) + w / 2f
            if (xs < 0f || xs >= w - 1) return false
            outParams[0] = xs
            outParams[1] = 1f / cos(theta)
            return true
        }

        /** Colour of frame [k] at cylinder row [y] for a column prepared by [column], or 0 if outside. */
        private fun sample(k: Int, params: FloatArray, y: Float): Int {
            val shot = layout.order[k]
            val xs = params[0]
            val ys = (y - layout.ys[k] - h / 2f) * params[1] + h / 2f
            if (ys < 0f || ys >= h - 1) return 0
            val f = shot.frame
            val ix = xs.toInt(); val iy = ys.toInt(); val ax = xs - ix; val ay = ys - iy
            val p = iy * w + ix
            val p00 = f.y[p].toInt() and 0xff; val p10 = f.y[p + 1].toInt() and 0xff
            val p01 = f.y[p + w].toInt() and 0xff; val p11 = f.y[p + w + 1].toInt() and 0xff
            val top = p00 + (p10 - p00) * ax
            val bottom = p01 + (p11 - p01) * ax
            val g = layout.gains[k]
            val yy = (top + (bottom - top) * ay) * g
            val cx = ((xs - 0.5f) / 2f).toInt().coerceIn(0, f.chromaWidth - 1)
            val cy = ((ys - 0.5f) / 2f).toInt().coerceIn(0, f.chromaHeight - 1)
            val u = ((f.u[cy * f.chromaWidth + cx].toInt() and 0xff) - 128f) * g
            val v = ((f.v[cy * f.chromaWidth + cx].toInt() and 0xff) - 128f) * g
            return YuvOps.rgb(yy + 1.402f * v, yy - 0.344136f * u - 0.714136f * v, yy + 1.772f * u)
        }

        private fun mix(a: Int, b: Int, t: Float): Int {
            val ra = (a shr 16) and 0xff; val ga = (a shr 8) and 0xff; val ba = a and 0xff
            val rb = (b shr 16) and 0xff; val gb = (b shr 8) and 0xff; val bb = b and 0xff
            return YuvOps.rgb(ra + (rb - ra) * t, ga + (gb - ga) * t, ba + (bb - ba) * t)
        }
    }

    fun output(layout: Layout): Output = Output(layout)

    companion object {
        private const val BLACK = 0xff000000.toInt()
    }
}
