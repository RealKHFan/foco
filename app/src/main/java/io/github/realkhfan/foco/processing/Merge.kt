package io.github.realkhfan.foco.processing

/**
 * Averages several aligned frames into one with much less noise. Each pixel of each frame is weighted
 * by how well it matches the reference, so things that moved between frames don't leave ghosts.
 */
class MergeAccumulator(private val ref: YuvFrame) {
    val width = ref.width
    val height = ref.height
    private val cw = ref.chromaWidth
    private val ch = ref.chromaHeight
    private var sumY: IntArray? = IntArray(width * height)
    private var weightY: ShortArray? = ShortArray(width * height)
    private var sumU: IntArray? = IntArray(cw * ch)
    private var sumV: IntArray? = IntArray(cw * ch)
    private var weightC: ShortArray? = ShortArray(cw * ch)
    var frames = 0
        private set

    init {
        val sy = sumY!!
        val wy = weightY!!
        for (i in sy.indices) {
            sy[i] = (ref.y[i].toInt() and 0xff) * FULL
            wy[i] = FULL.toShort()
        }
        val su = sumU!!
        val sv = sumV!!
        val wc = weightC!!
        for (i in su.indices) {
            su[i] = (ref.u[i].toInt() and 0xff) * FULL
            sv[i] = (ref.v[i].toInt() and 0xff) * FULL
            wc[i] = FULL.toShort()
        }
        frames = 1
    }

    /**
     * Adds [frame] sampled through [transform] (reference → frame coordinates).
     * [sigma] is the expected noise in 8-bit levels; differences far above it count as motion.
     * [brightness] scales the frame to the reference's exposure (1 for equal exposures).
     */
    fun add(frame: YuvFrame, transform: Affine, sigma: Float, brightness: Float = 1f) {
        require(frame.width == width && frame.height == height)
        val sy = sumY ?: error("finished")
        val wy = weightY!!
        val su = sumU!!
        val sv = sumV!!
        val wc = weightC!!
        val s2 = sigma * sigma
        val w = width
        val h = height
        val fy = frame.y
        val fu = frame.u
        val fv = frame.v
        val refY = ref.y
        val t = transform
        Parallel.rows(ch) { from, to ->
            val chromaWeights = IntArray(cw)
            for (cy in from until to) {
                for (ly in (cy * 2)..(cy * 2 + 1)) {
                    var mx = t.mapX(0f, ly.toFloat())
                    var my = t.mapY(0f, ly.toFloat())
                    val row = ly * w
                    for (x in 0 until w) {
                        if (mx >= 0f && my >= 0f && mx < w - 1 && my < h - 1) {
                            val ix = mx.toInt()
                            val iy = my.toInt()
                            val ax = mx - ix
                            val ay = my - iy
                            val p = iy * w + ix
                            val p00 = fy[p].toInt() and 0xff
                            val p10 = fy[p + 1].toInt() and 0xff
                            val p01 = fy[p + w].toInt() and 0xff
                            val p11 = fy[p + w + 1].toInt() and 0xff
                            val top = p00 + (p10 - p00) * ax
                            val bottom = p01 + (p11 - p01) * ax
                            val value = ((top + (bottom - top) * ay) * brightness).coerceAtMost(255f)
                            val r = (refY[row + x].toInt() and 0xff).toFloat()
                            val d = value - r
                            val weight = (FULL * s2 / (s2 + d * d)).toInt()
                            sy[row + x] += (value * weight + 0.5f).toInt()
                            wy[row + x] = (wy[row + x] + weight).toShort()
                            if (ly == cy * 2 && x and 1 == 0) chromaWeights[x shr 1] = weight
                        } else if (ly == cy * 2 && x and 1 == 0) {
                            chromaWeights[x shr 1] = -1
                        }
                        mx += t.a
                        my += t.c
                    }
                }
                // Chroma at half resolution, using the luma weight of the block.
                val ly = cy * 2 + 0.5f
                val crow = cy * cw
                for (cx in 0 until cw) {
                    val weight = chromaWeights[cx]
                    if (weight <= 0) continue
                    val lx = cx * 2 + 0.5f
                    val fcx = (t.mapX(lx, ly) - 0.5f) / 2f
                    val fcy = (t.mapY(lx, ly) - 0.5f) / 2f
                    if (fcx < 0f || fcy < 0f || fcx >= cw - 1 || fcy >= ch - 1) continue
                    val ix = fcx.toInt()
                    val iy = fcy.toInt()
                    val ax = fcx - ix
                    val ay = fcy - iy
                    val p = iy * cw + ix
                    val uv = bilinear(fu, p, cw, ax, ay)
                    val vv = bilinear(fv, p, cw, ax, ay)
                    su[crow + cx] += (uv * weight + 0.5f).toInt()
                    sv[crow + cx] += (vv * weight + 0.5f).toInt()
                    wc[crow + cx] = (wc[crow + cx] + weight).toShort()
                }
            }
        }
        frames++
    }

    /**
     * Adds [frame] with equal weight (no motion rejection): for Long Exposure and Action Pan, where things that
     * moved are *meant* to blur. Luma is interpolated so the aligned parts stay sharp; colour uses the nearest sample.
     */
    fun addAverage(frame: YuvFrame, transform: Affine) {
        require(frame.width == width && frame.height == height)
        val sy = sumY ?: error("finished")
        val wy = weightY!!
        val su = sumU!!
        val sv = sumV!!
        val wc = weightC!!
        val w = width
        val h = height
        val t = transform
        val fy = frame.y
        val fu = frame.u
        val fv = frame.v
        Parallel.rows(height) { from, to ->
            for (y in from until to) {
                var mx = t.mapX(0f, y.toFloat())
                var my = t.mapY(0f, y.toFloat())
                val row = y * w
                for (x in 0 until w) {
                    if (mx >= 0f && my >= 0f && mx < w - 1 && my < h - 1) {
                        val ix = mx.toInt()
                        val iy = my.toInt()
                        val ax = mx - ix
                        val ay = my - iy
                        val p = iy * w + ix
                        val p00 = fy[p].toInt() and 0xff
                        val p10 = fy[p + 1].toInt() and 0xff
                        val p01 = fy[p + w].toInt() and 0xff
                        val p11 = fy[p + w + 1].toInt() and 0xff
                        val top = p00 + (p10 - p00) * ax
                        val bottom = p01 + (p11 - p01) * ax
                        sy[row + x] += ((top + (bottom - top) * ay) * FULL + 0.5f).toInt()
                        wy[row + x] = (wy[row + x] + FULL).toShort()
                    }
                    mx += t.a
                    my += t.c
                }
            }
        }
        Parallel.rows(ch) { from, to ->
            for (cy in from until to) {
                val ly = cy * 2 + 0.5f
                for (cx in 0 until cw) {
                    val lx = cx * 2 + 0.5f
                    val fcx = ((t.mapX(lx, ly) - 0.5f) / 2f + 0.5f).toInt()
                    val fcy = ((t.mapY(lx, ly) - 0.5f) / 2f + 0.5f).toInt()
                    if (fcx < 0 || fcy < 0 || fcx >= cw || fcy >= ch) continue
                    val p = fcy * cw + fcx
                    val o = cy * cw + cx
                    su[o] += (fu[p].toInt() and 0xff) * FULL
                    sv[o] += (fv[p].toInt() and 0xff) * FULL
                    wc[o] = (wc[o] + FULL).toShort()
                }
            }
        }
        frames++
    }

    private fun bilinear(plane: ByteArray, p: Int, stride: Int, ax: Float, ay: Float): Float {
        val p00 = plane[p].toInt() and 0xff
        val p10 = plane[p + 1].toInt() and 0xff
        val p01 = plane[p + stride].toInt() and 0xff
        val p11 = plane[p + stride + 1].toInt() and 0xff
        val top = p00 + (p10 - p00) * ax
        val bottom = p01 + (p11 - p01) * ax
        return top + (bottom - top) * ay
    }

    /** Ends the merge and frees the accumulators. Values are 8-bit levels × [Merged.SCALE]. */
    fun finish(): Merged {
        val sy = sumY ?: error("finished")
        val wy = weightY!!
        val su = sumU!!
        val sv = sumV!!
        val wc = weightC!!
        val y = ShortArray(width * height)
        val u = ShortArray(cw * ch)
        val v = ShortArray(cw * ch)
        Parallel.rows(height) { from, to ->
            for (i in from * width until to * width) {
                y[i] = ((sy[i].toLong() * Merged.SCALE + wy[i] / 2) / wy[i]).toInt().toShort()
            }
        }
        for (i in u.indices) {
            val wgt = wc[i].toInt()
            u[i] = ((su[i].toLong() * Merged.SCALE + wgt / 2) / wgt).toInt().toShort()
            v[i] = ((sv[i].toLong() * Merged.SCALE + wgt / 2) / wgt).toInt().toShort()
        }
        sumY = null
        weightY = null
        sumU = null
        sumV = null
        weightC = null
        return Merged(width, height, y, u, v, frames)
    }

    companion object {
        /** Weight of a perfectly matching pixel. Up to 500 frames × 64 still fits in a Short. */
        const val FULL = 64

        /** Expected noise in 8-bit levels for a given ISO (YUV from the ISP is already denoised a bit). */
        fun sigmaForIso(iso: Int): Float = (2.5f + iso / 500f).coerceIn(2.5f, 14f)
    }
}

/** A merged image: planar YUV with [SCALE]× precision (keeps the extra bits that averaging gives). */
class Merged(val width: Int, val height: Int, val y: ShortArray, val u: ShortArray, val v: ShortArray, val frames: Int) {
    val chromaWidth = width / 2
    val chromaHeight = height / 2

    /** Median linear luminance from a sparse sample (for automatic night brightness). */
    fun medianLinearLuma(lut: FloatArray): Float {
        val samples = FloatArray(4096)
        var n = 0
        val step = maxOf(1, y.size / samples.size)
        var i = 0
        while (i < y.size && n < samples.size) {
            samples[n++] = lut[(y[i] / (SCALE / 4)).coerceIn(0, lut.size - 1)]
            i += step
        }
        samples.sort(0, n)
        return if (n == 0) 0f else samples[n / 2]
    }

    companion object {
        const val SCALE = 64

        fun fromFrame(frame: YuvFrame): Merged = Merged(
            frame.width, frame.height,
            ShortArray(frame.y.size) { ((frame.y[it].toInt() and 0xff) * SCALE).toShort() },
            ShortArray(frame.u.size) { ((frame.u[it].toInt() and 0xff) * SCALE).toShort() },
            ShortArray(frame.v.size) { ((frame.v[it].toInt() and 0xff) * SCALE).toShort() },
            1,
        )
    }
}
