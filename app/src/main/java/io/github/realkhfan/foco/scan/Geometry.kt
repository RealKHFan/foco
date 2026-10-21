package io.github.realkhfan.foco.scan

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A page outline: 4 corners as (x, y) pairs in the order top-left, top-right, bottom-right, bottom-left.
 * Pure Kotlin so detection and geometry can be tested without a phone.
 */
object Quad {
    fun area(c: FloatArray): Float {
        var a = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            a += c[i * 2] * c[j * 2 + 1] - c[j * 2] * c[i * 2 + 1]
        }
        return abs(a) / 2f
    }

    fun isConvex(c: FloatArray): Boolean {
        var sign = 0
        for (i in 0 until 4) {
            val ax = c[((i + 1) % 4) * 2] - c[i * 2]
            val ay = c[((i + 1) % 4) * 2 + 1] - c[i * 2 + 1]
            val bx = c[((i + 2) % 4) * 2] - c[((i + 1) % 4) * 2]
            val by = c[((i + 2) % 4) * 2 + 1] - c[((i + 1) % 4) * 2 + 1]
            val cross = ax * by - ay * bx
            val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (s == 0) return false
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    /** Puts 4 points in the order top-left, top-right, bottom-right, bottom-left (clockwise on screen). */
    fun order(points: FloatArray): FloatArray {
        val cx = (points[0] + points[2] + points[4] + points[6]) / 4f
        val cy = (points[1] + points[3] + points[5] + points[7]) / 4f
        val idx = (0 until 4).sortedBy { atan2(points[it * 2 + 1] - cy, points[it * 2] - cx) }
        // atan2 order from -π: top-left region first in screen coordinates (y down) → clockwise.
        val ordered = FloatArray(8)
        idx.forEachIndexed { k, i -> ordered[k * 2] = points[i * 2]; ordered[k * 2 + 1] = points[i * 2 + 1] }
        // Start at the corner with the smallest x + y.
        var start = 0
        for (k in 1 until 4) if (ordered[k * 2] + ordered[k * 2 + 1] < ordered[start * 2] + ordered[start * 2 + 1]) start = k
        return FloatArray(8) { ordered[((it / 2 + start) % 4) * 2 + it % 2] }
    }

    /** Whole image with a small margin, for when no page is found. */
    fun full(width: Float, height: Float, inset: Float = 0.03f): FloatArray {
        val dx = width * inset
        val dy = height * inset
        return floatArrayOf(dx, dy, width - dx, dy, width - dx, height - dy, dx, height - dy)
    }

    fun scale(c: FloatArray, sx: Float, sy: Float) = FloatArray(8) { if (it % 2 == 0) c[it] * sx else c[it] * sy }

    /**
     * Turns corners given in a frame by [rotation] degrees clockwise (same convention as the camera's
     * rotationDegrees) into the upright picture. [width]/[height] are the frame's size before turning.
     */
    fun rotate(c: FloatArray, width: Float, height: Float, rotation: Int): FloatArray {
        val out = FloatArray(8)
        for (i in 0 until 4) {
            val x = c[i * 2]
            val y = c[i * 2 + 1]
            when (rotation) {
                90 -> { out[i * 2] = height - y; out[i * 2 + 1] = x }
                180 -> { out[i * 2] = width - x; out[i * 2 + 1] = height - y }
                270 -> { out[i * 2] = y; out[i * 2 + 1] = width - x }
                else -> { out[i * 2] = x; out[i * 2 + 1] = y }
            }
        }
        return order(out)
    }
}

/** 3×3 projective transform, row-major, h[8] = 1. */
class Homography(val h: DoubleArray) {
    fun mapX(x: Double, y: Double): Double = (h[0] * x + h[1] * y + h[2]) / (h[6] * x + h[7] * y + h[8])
    fun mapY(x: Double, y: Double): Double = (h[3] * x + h[4] * y + h[5]) / (h[6] * x + h[7] * y + h[8])

    companion object {
        /** The transform that takes the 4 [from] points to the 4 [to] points. */
        fun between(from: FloatArray, to: FloatArray): Homography? {
            val a = Array(8) { DoubleArray(9) }
            for (i in 0 until 4) {
                val x = from[i * 2].toDouble(); val y = from[i * 2 + 1].toDouble()
                val u = to[i * 2].toDouble(); val v = to[i * 2 + 1].toDouble()
                a[i * 2] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
                a[i * 2 + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
            }
            // Gaussian elimination with partial pivoting.
            for (col in 0 until 8) {
                var pivot = col
                for (r in col + 1 until 8) if (abs(a[r][col]) > abs(a[pivot][col])) pivot = r
                if (abs(a[pivot][col]) < 1e-12) return null
                val tmp = a[col]; a[col] = a[pivot]; a[pivot] = tmp
                for (r in 0 until 8) {
                    if (r == col) continue
                    val f = a[r][col] / a[col][col]
                    for (k in col until 9) a[r][k] -= f * a[col][k]
                }
            }
            return Homography(DoubleArray(9) { if (it == 8) 1.0 else a[it][8] / a[it][it] })
        }
    }
}

enum class PaperSize(val ratio: Float) { NONE(0f), A4(297f / 210f), LETTER(11f / 8.5f) }

/** How big the flattened page should be, with its real proportions (taking the camera's perspective into account). */
object PageShape {
    /**
     * Height ÷ width of the real page, from the corners and the camera's focal length in pixels (the method of
     * Zhang & He, "Whiteboard scanning"). Falls back to the average side lengths when the maths is unstable.
     */
    fun aspect(c: FloatArray, focal: Float?, cx: Float, cy: Float): Float {
        val simple = simpleAspect(c)
        if (focal == null || focal <= 0f) return simple
        // m1 = top-left, m2 = top-right, m3 = bottom-left, m4 = bottom-right (homogeneous, centred).
        fun m(i: Int) = doubleArrayOf((c[i * 2] - cx).toDouble(), (c[i * 2 + 1] - cy).toDouble(), 1.0)
        val m1 = m(0); val m2 = m(1); val m4 = m(2); val m3 = m(3)
        fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
        fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        val m14 = cross(m1, m4)
        val d2 = dot(cross(m2, m4), m3)
        val d3 = dot(cross(m3, m4), m2)
        if (abs(d2) < 1e-9 || abs(d3) < 1e-9) return simple
        val k2 = dot(m14, m3) / d2
        val k3 = dot(m14, m2) / d3
        val n2 = DoubleArray(3) { k2 * m2[it] - m1[it] }
        val n3 = DoubleArray(3) { k3 * m3[it] - m1[it] }
        val f2 = focal.toDouble() * focal
        // With centred coordinates the camera matrix is diag(f, f, 1).
        val w2 = (n2[0] * n2[0] + n2[1] * n2[1]) / f2 + n2[2] * n2[2]
        val h2 = (n3[0] * n3[0] + n3[1] * n3[1]) / f2 + n3[2] * n3[2]
        if (w2 <= 0 || h2 <= 0) return simple
        val r = sqrt(h2 / w2).toFloat()
        // Only trust it if it agrees roughly with the simple estimate (bad corners give wild answers).
        return if (r.isFinite() && r > simple * 0.6f && r < simple * 1.6f) r else simple
    }

    fun simpleAspect(c: FloatArray): Float {
        fun d(i: Int, j: Int) = hypot(c[i * 2] - c[j * 2], c[i * 2 + 1] - c[j * 2 + 1])
        val w = (d(0, 1) + d(3, 2)) / 2f
        val h = (d(0, 3) + d(1, 2)) / 2f
        return if (w <= 0f) 1f else h / w
    }

    /** Snaps to A4 / Letter (either way round) when the measured proportions are within [tolerance]. */
    fun snap(aspect: Float, tolerance: Float = 0.05f): Pair<Float, PaperSize> {
        for (paper in listOf(PaperSize.A4, PaperSize.LETTER)) {
            if (abs(aspect / paper.ratio - 1f) < tolerance) return paper.ratio to paper
            if (abs(aspect * paper.ratio - 1f) < tolerance) return (1f / paper.ratio) to paper
        }
        return aspect to PaperSize.NONE
    }

    /** Output size in pixels: as sharp as the photo allows, at most [maxLong] on the long side. */
    fun outputSize(c: FloatArray, aspect: Float, maxLong: Int): Pair<Int, Int> {
        fun d(i: Int, j: Int) = hypot(c[i * 2] - c[j * 2], c[i * 2 + 1] - c[j * 2 + 1])
        val w = max(d(0, 1), d(3, 2))
        val h = max(d(0, 3), d(1, 2))
        // Use the larger measured side and the real proportions for the other one.
        var outW: Float
        var outH: Float
        if (h >= w) { outH = h; outW = h / aspect } else { outW = w; outH = w * aspect }
        val long = max(outW, outH)
        if (long > maxLong) { outW *= maxLong / long; outH *= maxLong / long }
        return max(16, outW.roundToInt()) to max(16, outH.roundToInt())
    }
}

/** A grey picture (0..255) for detection. */
class GrayImage(val width: Int, val height: Int, val data: FloatArray)

/**
 * Finds a document in a picture: strong straight edges are found with a Hough transform, then every
 * combination of two roughly parallel pairs is scored by how well its four sides follow real edges and how
 * big it is. Works with any background; best with a page that stands out from what it lies on.
 */
object DocumentDetector {
    class Result(val corners: FloatArray, val score: Float)

    private const val THETA_BINS = 180
    private val COS = FloatArray(THETA_BINS) { kotlin.math.cos(Math.toRadians(it.toDouble())).toFloat() }
    private val SIN = FloatArray(THETA_BINS) { kotlin.math.sin(Math.toRadians(it.toDouble())).toFloat() }

    private class Line(val theta: Int, val rho: Float, val votes: Float)

    fun detect(img: GrayImage, minAreaFraction: Float = 0.12f): Result? {
        val w = img.width
        val h = img.height
        if (w < 32 || h < 32) return null
        val blurred = blur(blur(img.data, w, h), w, h)
        val gx = FloatArray(w * h)
        val gy = FloatArray(w * h)
        val mag = FloatArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val sx = (blurred[i - w + 1] + 2 * blurred[i + 1] + blurred[i + w + 1]) - (blurred[i - w - 1] + 2 * blurred[i - 1] + blurred[i + w - 1])
            val sy = (blurred[i + w - 1] + 2 * blurred[i + w] + blurred[i + w + 1]) - (blurred[i - w - 1] + 2 * blurred[i - w] + blurred[i - w + 1])
            gx[i] = sx; gy[i] = sy
            mag[i] = sqrt(sx * sx + sy * sy)
        }
        val threshold = max(percentile(mag, 0.88f), 24f)

        // Hough transform, each edge point voting only near its own gradient direction.
        val diag = hypot(w.toFloat(), h.toFloat())
        val rhoBins = (2 * diag).toInt() + 1
        val acc = FloatArray(THETA_BINS * rhoBins)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val m = mag[i]
            if (m < threshold) continue
            var t0 = Math.toDegrees(atan2(gy[i], gx[i]).toDouble()).roundToInt()
            t0 = ((t0 % 180) + 180) % 180
            val weight = min(m, threshold * 4f)
            for (dt in -3..3) {
                val t = (t0 + dt + 180) % 180
                val rho = x * COS[t] + y * SIN[t]
                acc[t * rhoBins + (rho + diag).roundToInt()] += weight
            }
        }
        val lines = peaks(acc, rhoBins, diag, 18)
        if (lines.size < 4) return null

        // Pairs of roughly parallel lines that are far enough apart.
        val cx = w / 2f
        val cy = h / 2f
        val minSep = min(w, h) * 0.2f
        class Pair2(val a: Line, val b: Line, val angle: Float)
        val pairs = mutableListOf<Pair2>()
        for (i in lines.indices) for (j in i + 1 until lines.size) {
            val a = lines[i]; val b = lines[j]
            val diff = angleDiff(a.theta, b.theta)
            if (diff > 30) continue
            val da = cx * COS[a.theta] + cy * SIN[a.theta] - a.rho
            var db = cx * COS[b.theta] + cy * SIN[b.theta] - b.rho
            if (abs(a.theta - b.theta) > 90) db = -db
            if (abs(da - db) < minSep) continue
            pairs += Pair2(a, b, meanAngle(a.theta, b.theta))
        }
        var best: Result? = null
        val area = (w * h).toFloat()
        for (p in pairs.indices) for (q in p + 1 until pairs.size) {
            val pa = pairs[p]; val qa = pairs[q]
            if (angleDiff(pa.angle.roundToInt(), qa.angle.roundToInt()) < 50) continue
            val pts = FloatArray(8)
            var ok = true
            var k = 0
            for (l1 in listOf(pa.a, pa.b)) for (l2 in listOf(qa.a, qa.b)) {
                val pt = intersect(l1, l2)
                if (pt == null) { ok = false; break }
                pts[k++] = pt.first; pts[k++] = pt.second
            }
            if (!ok) continue
            val corners = Quad.order(pts)
            if (!Quad.isConvex(corners)) continue
            var inside = true
            for (c in 0 until 4) {
                val x = corners[c * 2]; val y = corners[c * 2 + 1]
                if (x < -w * 0.06f || y < -h * 0.06f || x > w * 1.06f || y > h * 1.06f) inside = false
            }
            if (!inside) continue
            val areaFrac = Quad.area(corners) / area
            if (areaFrac < minAreaFraction) continue
            if (!anglesOk(corners)) continue
            val support = FloatArray(4) { side -> sideSupport(corners, side, gx, gy, mag, w, h, threshold * 0.6f) }
            if (support.min() < 0.3f) continue
            val score = support.average().toFloat() * (0.7f + 0.3f * sqrt(areaFrac))
            if (score > (best?.score ?: 0.42f)) best = Result(corners, score)
        }
        return best?.let { r ->
            Result(FloatArray(8) { if (it % 2 == 0) r.corners[it].coerceIn(0f, w - 1f) else r.corners[it].coerceIn(0f, h - 1f) }, r.score)
        }
    }

    private fun anglesOk(c: FloatArray): Boolean {
        for (i in 0 until 4) {
            val p = i; val prev = (i + 3) % 4; val next = (i + 1) % 4
            val ax = c[prev * 2] - c[p * 2]; val ay = c[prev * 2 + 1] - c[p * 2 + 1]
            val bx = c[next * 2] - c[p * 2]; val by = c[next * 2 + 1] - c[p * 2 + 1]
            val cos = (ax * bx + ay * by) / (hypot(ax, ay) * hypot(bx, by))
            if (abs(cos) > 0.72f) return false // corners between ~44° and ~136°
        }
        return true
    }

    /** Share of points along a side that sit on a strong edge running the same way. */
    private fun sideSupport(c: FloatArray, side: Int, gx: FloatArray, gy: FloatArray, mag: FloatArray, w: Int, h: Int, t: Float): Float {
        val x0 = c[side * 2]; val y0 = c[side * 2 + 1]
        val x1 = c[((side + 1) % 4) * 2]; val y1 = c[((side + 1) % 4) * 2 + 1]
        val len = hypot(x1 - x0, y1 - y0)
        if (len < 8f) return 0f
        val nx = -(y1 - y0) / len
        val ny = (x1 - x0) / len
        val steps = (len / 2f).toInt().coerceAtLeast(8)
        var hits = 0
        var total = 0
        for (s in 0..steps) {
            val f = 0.06f + 0.88f * s / steps
            val px = x0 + (x1 - x0) * f
            val py = y0 + (y1 - y0) * f
            if (px < 1 || py < 1 || px >= w - 1 || py >= h - 1) continue
            total++
            for (o in -2..2) {
                val sx = (px + nx * o).roundToInt()
                val sy = (py + ny * o).roundToInt()
                if (sx < 1 || sy < 1 || sx >= w - 1 || sy >= h - 1) continue
                val i = sy * w + sx
                if (mag[i] < t) continue
                if (abs((gx[i] * nx + gy[i] * ny) / mag[i]) > 0.85f) { hits++; break }
            }
        }
        return if (total < 6) 0f else hits.toFloat() / total
    }

    private fun intersect(a: Line, b: Line): Pair<Float, Float>? {
        val det = COS[a.theta] * SIN[b.theta] - SIN[a.theta] * COS[b.theta]
        if (abs(det) < 1e-4f) return null
        val x = (a.rho * SIN[b.theta] - b.rho * SIN[a.theta]) / det
        val y = (COS[a.theta] * b.rho - COS[b.theta] * a.rho) / det
        return x to y
    }

    private fun angleDiff(a: Int, b: Int): Int { val d = abs(a - b) % 180; return min(d, 180 - d) }

    private fun meanAngle(a: Int, b: Int): Float {
        var bb = b
        if (abs(a - b) > 90) bb = if (b > a) b - 180 else b + 180
        return (((a + bb) / 2f) % 180f + 180f) % 180f
    }

    private fun peaks(acc: FloatArray, rhoBins: Int, diag: Float, count: Int): List<Line> {
        val work = acc.copyOf()
        val out = mutableListOf<Line>()
        val max = work.max()
        if (max <= 0f) return out
        repeat(count) {
            var bi = 0
            for (i in work.indices) if (work[i] > work[bi]) bi = i
            val v = work[bi]
            if (v < max * 0.12f) return out
            val t = bi / rhoBins
            val r = bi % rhoBins
            out += Line(t, r - diag, v)
            // Suppress the neighbourhood (θ ±8°, ρ ±8 px), wrapping θ across 0/180 with ρ flipped.
            for (dt in -8..8) {
                var tt = t + dt
                var rr = r
                if (tt < 0) { tt += 180; rr = rhoBins - 1 - r }
                if (tt >= 180) { tt -= 180; rr = rhoBins - 1 - r }
                for (dr in -8..8) {
                    val q = rr + dr
                    if (q in 0 until rhoBins) work[tt * rhoBins + q] = 0f
                }
            }
        }
        return out
    }

    private fun blur(src: FloatArray, w: Int, h: Int): FloatArray {
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val l = src[y * w + max(0, x - 1)]; val r = src[y * w + min(w - 1, x + 1)]
            tmp[i] = (l + 2 * src[i] + r) / 4f
        }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val u = tmp[max(0, y - 1) * w + x]; val d = tmp[min(h - 1, y + 1) * w + x]
            out[i] = (u + 2 * tmp[i] + d) / 4f
        }
        return out
    }

    private fun percentile(v: FloatArray, p: Float): Float {
        val hist = IntArray(512)
        for (x in v) hist[min(511, x.toInt())]++
        val target = (v.size * p).toInt()
        var sum = 0
        for (i in hist.indices) { sum += hist[i]; if (sum >= target) return i.toFloat() }
        return 511f
    }
}
