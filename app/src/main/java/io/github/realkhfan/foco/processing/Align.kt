package io.github.realkhfan.foco.processing

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Small grey images of a frame used to line frames up (1/4 and 1/16 size). */
class LumaPyramid(frame: YuvFrame) {
    val w4 = frame.width / 4
    val h4 = frame.height / 4
    val l4 = FloatArray(w4 * h4)
    val w16 = w4 / 4
    val h16 = h4 / 4
    val l16 = FloatArray(w16 * h16)

    init {
        val w = frame.width
        val y = frame.y
        Parallel.rows(h4) { from, to ->
            for (by in from until to) {
                for (bx in 0 until w4) {
                    var sum = 0
                    val x0 = bx * 4
                    for (dy in 0 until 4) {
                        val row = (by * 4 + dy) * w + x0
                        sum += (y[row].toInt() and 0xff) + (y[row + 1].toInt() and 0xff) +
                            (y[row + 2].toInt() and 0xff) + (y[row + 3].toInt() and 0xff)
                    }
                    l4[by * w4 + bx] = sum / 16f
                }
            }
        }
        for (by in 0 until h16) {
            for (bx in 0 until w16) {
                var sum = 0f
                for (dy in 0 until 4) {
                    val row = (by * 4 + dy) * w4 + bx * 4
                    sum += l4[row] + l4[row + 1] + l4[row + 2] + l4[row + 3]
                }
                l16[by * w16 + bx] = sum / 16f
            }
        }
    }
}

/**
 * Lines a frame up with the reference: a coarse global shift, then shifts of many tiles,
 * then one smooth transform (shift + small rotation/scale) fitted through the tiles.
 * Handles the hand shake of a few-second handheld burst.
 */
object Aligner {
    private const val GLOBAL_RADIUS = 8 // at 1/16 → ±128 px
    private const val TILE = 48 // at 1/4 → 192 px
    private const val TILE_RADIUS = 4 // at 1/4 → ±16 px around the global shift
    private const val SUBJECT_TILE = 24 // at 1/4 → 96 px
    private const val COLS = 8
    private const val ROWS = 6

    data class Result(val transform: Affine, val tilesUsed: Int, val reliable: Boolean)

    fun align(ref: LumaPyramid, frame: LumaPyramid): Result {
        val (gx, gy) = globalShift(ref, frame)
        val baseX = gx * 4
        val baseY = gy * 4

        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        val dxs = ArrayList<Float>()
        val dys = ArrayList<Float>()
        val margin = TILE_RADIUS + 2 + max(abs(baseX), abs(baseY))
        val spanX = ref.w4 - 2 * margin - TILE
        val spanY = ref.h4 - 2 * margin - TILE
        if (spanX > 0 && spanY > 0) {
            for (row in 0 until ROWS) {
                for (col in 0 until COLS) {
                    val x0 = margin + spanX * col / (COLS - 1)
                    val y0 = margin + spanY * row / (ROWS - 1)
                    val shift = tileShift(ref, frame, x0, y0, baseX, baseY) ?: continue
                    xs += (x0 + TILE / 2f) * 4f
                    ys += (y0 + TILE / 2f) * 4f
                    dxs += shift.first * 4f
                    dys += shift.second * 4f
                }
            }
        }
        val global = Affine.translation(baseX * 4f, baseY * 4f)
        if (xs.size < 6) return Result(medianTranslation(dxs, dys) ?: global, xs.size, xs.size >= 3)

        var keep = BooleanArray(xs.size) { true }
        var fit: Affine? = null
        repeat(3) {
            val f = fitAffine(xs, ys, dxs, dys, keep) ?: return@repeat
            fit = f
            val residuals = FloatArray(xs.size) { i ->
                val ex = f.mapX(xs[i], ys[i]) - (xs[i] + dxs[i])
                val ey = f.mapY(xs[i], ys[i]) - (ys[i] + dys[i])
                sqrt(ex * ex + ey * ey)
            }
            val sorted = residuals.filterIndexed { i, _ -> keep[i] }.sorted()
            val median = sorted[sorted.size / 2]
            val limit = max(2.5f, median * 2.5f)
            val next = BooleanArray(xs.size) { residuals[it] <= limit }
            if (next.count { it } < 6 || next.contentEquals(keep)) return@repeat
            keep = next
        }
        val f = fit ?: return Result(medianTranslation(dxs, dys) ?: global, xs.size, true)
        val sane = abs(f.a - 1f) < 0.04f && abs(f.d - 1f) < 0.04f && abs(f.b) < 0.04f && abs(f.c) < 0.04f
        return if (sane) {
            Result(f, keep.count { it }, true)
        } else {
            Result(medianTranslation(dxs, dys) ?: global, xs.size, true)
        }
    }

    /**
     * Action Pan: how the subject moved, measured only inside a region (fractions of the frame, default the
     * middle). The background is ignored on purpose. Translation only.
     */
    fun alignRegion(
        ref: LumaPyramid,
        frame: LumaPyramid,
        left: Float = 0.35f,
        top: Float = 0.3f,
        right: Float = 0.65f,
        bottom: Float = 0.7f,
    ): Result {
        // Coarse: whole region at 1/16 scale, ±12 (= ±192 px).
        val r16 = 12
        val x0 = (left * ref.w16).toInt().coerceAtLeast(r16)
        val y0 = (top * ref.h16).toInt().coerceAtLeast(r16)
        val x1 = (right * ref.w16).toInt().coerceAtMost(ref.w16 - r16)
        val y1 = (bottom * ref.h16).toInt().coerceAtMost(ref.h16 - r16)
        var cx = 0
        var cy = 0
        if (x1 - x0 >= 4 && y1 - y0 >= 4) {
            val n = 2 * r16 + 1
            val costs = FloatArray(n * n)
            val refMean = mean(ref.l16, ref.w16, x0, y0, x1 - x0, y1 - y0)
            for (sy in -r16..r16) for (sx in -r16..r16) {
                val fMean = mean(frame.l16, ref.w16, x0 + sx, y0 + sy, x1 - x0, y1 - y0)
                var sad = 0f
                for (yy in y0 until y1) {
                    val ro = yy * ref.w16
                    val fo = (yy + sy) * ref.w16 + sx
                    for (xx in x0 until x1) sad += abs((ref.l16[ro + xx] - refMean) - (frame.l16[fo + xx] - fMean))
                }
                costs[(sy + r16) * n + sx + r16] = sad
            }
            // Two things move differently: the subject you follow (small shift) and the background (big shift).
            // Of the good matches, take the one closest to "no movement".
            val best = costs.min()
            var bestDist = Int.MAX_VALUE
            for (j in 0 until n) for (i in 0 until n) {
                val c = costs[j * n + i]
                if (c > best * 1.35f) continue
                var isMin = true
                for (dj in -1..1) for (di in -1..1) {
                    val jj = j + dj; val ii = i + di
                    if ((di != 0 || dj != 0) && jj in 0 until n && ii in 0 until n && costs[jj * n + ii] < c) isMin = false
                }
                if (!isMin) continue
                val sx = i - r16; val sy = j - r16
                val d = sx * sx + sy * sy
                if (d < bestDist) { bestDist = d; cx = sx; cy = sy }
            }
        }
        // Fine: tiles inside the region at 1/4 scale around the coarse shift; the median wins.
        val baseX = cx * 4
        val baseY = cy * 4
        val dxs = ArrayList<Float>()
        val dys = ArrayList<Float>()
        val tx0 = (left * ref.w4).toInt()
        val ty0 = (top * ref.h4).toInt()
        val tw = ((right - left) * ref.w4).toInt()
        val th = ((bottom - top) * ref.h4).toInt()
        // Small tiles so they stay on the subject and don't pick up the moving background at its edges.
        val tile = SUBJECT_TILE
        for (row in 0 until 3) for (col in 0 until 3) {
            val x = tx0 + (tw - tile) * col / 2
            val y = ty0 + (th - tile) * row / 2
            if (x - TILE_RADIUS + baseX < 0 || y - TILE_RADIUS + baseY < 0) continue
            if (x + tile + TILE_RADIUS + baseX > ref.w4 || y + tile + TILE_RADIUS + baseY > ref.h4) continue
            if (x < 0 || y < 0 || x + tile > ref.w4 || y + tile > ref.h4) continue
            val shift = tileShift(ref, frame, x, y, baseX, baseY, tile) ?: continue
            dxs += shift.first * 4f
            dys += shift.second * 4f
        }
        val t = medianTranslation(dxs, dys) ?: Affine.translation(baseX * 4f, baseY * 4f)
        return Result(t, dxs.size, true)
    }

    /**
     * Sharpens a translation to a fraction of a pixel on the full-size pictures, in a patch around
     * ([centerX], [centerY]) of the reference (squared differences, ±6 px, then a parabola fit).
     */
    fun refineTranslation(ref: YuvFrame, frame: YuvFrame, start: Affine, centerX: Int, centerY: Int, half: Int = 160): Affine {
        val w = ref.width
        val r = 6
        val bx = start.tx.roundToInt()
        val by = start.ty.roundToInt()
        val x0 = (centerX - half).coerceAtLeast(r + abs(bx) + 1)
        val x1 = (centerX + half).coerceAtMost(w - r - abs(bx) - 2)
        val y0 = (centerY - half).coerceAtLeast(r + abs(by) + 1)
        val y1 = (centerY + half).coerceAtMost(ref.height - r - abs(by) - 2)
        if (x1 - x0 < 32 || y1 - y0 < 32) return start
        val n = 2 * r + 1
        val costs = DoubleArray(n * n)
        for (j in 0 until n) for (i in 0 until n) {
            val sx = bx + i - r
            val sy = by + j - r
            var mr = 0.0; var mf = 0.0; var cnt = 0
            for (y in y0 until y1 step 2) for (x in x0 until x1 step 2) {
                mr += ref.y[y * w + x].toInt() and 0xff
                mf += frame.y[(y + sy) * w + x + sx].toInt() and 0xff
                cnt++
            }
            mr /= cnt; mf /= cnt
            var ssd = 0.0
            for (y in y0 until y1 step 2) for (x in x0 until x1 step 2) {
                val d = ((ref.y[y * w + x].toInt() and 0xff) - mr) - ((frame.y[(y + sy) * w + x + sx].toInt() and 0xff) - mf)
                ssd += d * d
            }
            costs[j * n + i] = ssd / cnt
        }
        var best = 0
        for (k in costs.indices) if (costs[k] < costs[best]) best = k
        val bi = best % n
        val bj = best / n
        fun para(l: Double, m: Double, rr: Double): Float {
            val d = l - 2 * m + rr
            return if (d <= 1e-9) 0f else (0.5 * (l - rr) / d).toFloat().coerceIn(-0.5f, 0.5f)
        }
        var dx = (bx + bi - r).toFloat()
        var dy = (by + bj - r).toFloat()
        if (bi in 1 until n - 1) dx += para(costs[best - 1], costs[best], costs[best + 1])
        if (bj in 1 until n - 1) dy += para(costs[best - n], costs[best], costs[best + n])
        return Affine.translation(dx, dy)
    }

    /** Best whole-pixel shift at 1/16 scale (zero-mean, so exposure differences don't matter). */
    internal fun globalShift(ref: LumaPyramid, frame: LumaPyramid): Pair<Int, Int> {
        val w = ref.w16
        val h = ref.h16
        val r = GLOBAL_RADIUS
        if (w <= 2 * r + 4 || h <= 2 * r + 4) return 0 to 0
        val refMean = mean(ref.l16, w, r, r, w - 2 * r, h - 2 * r)
        var best = Float.MAX_VALUE
        var bx = 0
        var by = 0
        for (sy in -r..r) {
            for (sx in -r..r) {
                val fMean = mean(frame.l16, w, r + sx, r + sy, w - 2 * r, h - 2 * r)
                var sad = 0f
                for (yy in r until h - r) {
                    val ro = yy * w
                    val fo = (yy + sy) * w + sx
                    for (xx in r until w - r) {
                        sad += abs((ref.l16[ro + xx] - refMean) - (frame.l16[fo + xx] - fMean))
                    }
                }
                // Slight preference for small shifts on flat scenes.
                sad *= 1f + 0.002f * (abs(sx) + abs(sy))
                if (sad < best) {
                    best = sad
                    bx = sx
                    by = sy
                }
            }
        }
        return bx to by
    }

    /** Shift of one tile at 1/4 scale with sub-pixel refinement, or null for flat tiles. */
    private fun tileShift(ref: LumaPyramid, frame: LumaPyramid, x0: Int, y0: Int, baseX: Int, baseY: Int, tile: Int = TILE): Pair<Float, Float>? {
        val w = ref.w4
        val refMean = mean(ref.l4, w, x0, y0, tile, tile)
        var variance = 0f
        for (yy in y0 until y0 + tile) for (xx in x0 until x0 + tile) {
            val d = ref.l4[yy * w + xx] - refMean
            variance += d * d
        }
        if (sqrt(variance / (tile * tile)) < 2.5f) return null

        val n = 2 * TILE_RADIUS + 1
        val costs = FloatArray(n * n)
        var best = Float.MAX_VALUE
        var bi = 0
        var bj = 0
        for (j in 0 until n) {
            for (i in 0 until n) {
                val sx = baseX + i - TILE_RADIUS
                val sy = baseY + j - TILE_RADIUS
                val fx0 = x0 + sx
                val fy0 = y0 + sy
                if (fx0 < 0 || fy0 < 0 || fx0 + tile > frame.w4 || fy0 + tile > frame.h4) {
                    costs[j * n + i] = Float.MAX_VALUE
                    continue
                }
                val fMean = mean(frame.l4, w, fx0, fy0, tile, tile)
                var sad = 0f
                for (yy in 0 until tile) {
                    val ro = (y0 + yy) * w + x0
                    val fo = (fy0 + yy) * w + fx0
                    for (xx in 0 until tile) sad += abs((ref.l4[ro + xx] - refMean) - (frame.l4[fo + xx] - fMean))
                }
                costs[j * n + i] = sad
                if (sad < best) {
                    best = sad
                    bi = i
                    bj = j
                }
            }
        }
        if (bi == 0 || bj == 0 || bi == n - 1 || bj == n - 1) return null // hit the search edge: unreliable
        val subX = parabola(costs[bj * n + bi - 1], costs[bj * n + bi], costs[bj * n + bi + 1])
        val subY = parabola(costs[(bj - 1) * n + bi], costs[bj * n + bi], costs[(bj + 1) * n + bi])
        return (baseX + bi - TILE_RADIUS + subX) to (baseY + bj - TILE_RADIUS + subY)
    }

    private fun parabola(left: Float, mid: Float, right: Float): Float {
        val denom = left - 2 * mid + right
        if (denom <= 1e-6f) return 0f
        return (0.5f * (left - right) / denom).coerceIn(-0.5f, 0.5f)
    }

    private fun mean(data: FloatArray, stride: Int, x0: Int, y0: Int, w: Int, h: Int): Float {
        var s = 0.0
        for (yy in y0 until y0 + h) {
            val o = yy * stride
            for (xx in x0 until x0 + w) s += data[o + xx]
        }
        return (s / (w * h)).toFloat()
    }

    private fun medianTranslation(dxs: List<Float>, dys: List<Float>): Affine? {
        if (dxs.isEmpty()) return null
        return Affine.translation(dxs.sorted()[dxs.size / 2], dys.sorted()[dys.size / 2])
    }

    /** Least squares: frame = A · ref. Solves the two 3×3 normal equations. */
    internal fun fitAffine(xs: List<Float>, ys: List<Float>, dxs: List<Float>, dys: List<Float>, keep: BooleanArray): Affine? {
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0; var sx = 0.0; var sy = 0.0; var n = 0.0
        var bx0 = 0.0; var bx1 = 0.0; var bx2 = 0.0
        var by0 = 0.0; var by1 = 0.0; var by2 = 0.0
        // Centre coordinates for numerical stability.
        var cx = 0.0; var cy = 0.0; var count = 0
        for (i in xs.indices) if (keep[i]) { cx += xs[i]; cy += ys[i]; count++ }
        if (count < 3) return null
        cx /= count; cy /= count
        for (i in xs.indices) {
            if (!keep[i]) continue
            val x = xs[i] - cx
            val y = ys[i] - cy
            val fx = x + dxs[i]
            val fy = y + dys[i]
            sxx += x * x; sxy += x * y; syy += y * y; sx += x; sy += y; n += 1
            bx0 += x * fx; bx1 += y * fx; bx2 += fx
            by0 += x * fy; by1 += y * fy; by2 += fy
        }
        val m = arrayOf(doubleArrayOf(sxx, sxy, sx), doubleArrayOf(sxy, syy, sy), doubleArrayOf(sx, sy, n))
        val px = solve3(m, doubleArrayOf(bx0, bx1, bx2)) ?: return null
        val py = solve3(m, doubleArrayOf(by0, by1, by2)) ?: return null
        // Undo the centring: f = A(x - c) + t' + c  →  t = t' + c - A c
        val a = px[0]; val b = px[1]; val c = py[0]; val d = py[1]
        val tx = px[2] + cx - (a * cx + b * cy)
        val ty = py[2] + cy - (c * cx + d * cy)
        return Affine(a.toFloat(), b.toFloat(), tx.toFloat(), c.toFloat(), d.toFloat(), ty.toFloat())
    }

    private fun solve3(m: Array<DoubleArray>, rhs: DoubleArray): DoubleArray? {
        fun det(a: Array<DoubleArray>) =
            a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1]) -
                a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0]) +
                a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0])
        val d = det(m)
        if (abs(d) < 1e-9) return null
        return DoubleArray(3) { col ->
            val copy = Array(3) { r -> DoubleArray(3) { c -> if (c == col) rhs[r] else m[r][c] } }
            det(copy) / d
        }
    }
}
