package io.github.realkhfan.foco.processing

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** Fast sRGB ↔ linear conversion tables. */
object Srgb {
    private val toLinear = FloatArray(4096) { i -> decode(i / 4095f) }
    /** Indexed by sqrt(linear) so the shadows get fine steps. */
    private val toGamma = FloatArray(4097) { i -> val l = (i / 4096f).let { it * it }; encode(l) }

    fun decode(g: Float): Float = if (g <= 0.04045f) g / 12.92f else ((g + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    fun encode(l: Float): Float = if (l <= 0.0031308f) 12.92f * l else (1.055f * l.toDouble().pow(1 / 2.4).toFloat() - 0.055f)

    fun lin(g: Float): Float = toLinear[(g * 4095f + 0.5f).toInt().coerceIn(0, 4095)]
    fun gam(l: Float): Float = if (l <= 0f) 0f else if (l >= 1f) 1f else toGamma[(sqrt(l) * 4096f).toInt()]

    /** For [Merged.medianLinearLuma]: index = level × 4 (0..1020). */
    val levelToLinear = FloatArray(1024) { decode((it / 4f) / 255f) }
}

data class ToneSettings(
    val night: Boolean = false,
    /** Linear brightness gain (night). */
    val gain: Float = 1f,
    /** Linear black point removed after the gain (night: hides the noise floor). */
    val blackLevel: Float = 0f,
    /** Unsharp-mask amount on the luma (0 = off). */
    val sharpen: Float = 0f,
    /** -1..1, the Pro "Shadows" control (and a little lift for night). */
    val shadows: Float = 0f,
    val saturation: Float = 1f,
    /** Ultra HDR: maximum brightness boost stored in the gain map (1 = no gain map). */
    val headroom: Float = 1f,
    /** Blur the colour channels to remove colour noise (night). */
    val chromaDenoise: Boolean = false,
)

/** The extra short exposure used to recover clipped highlights. [ratio] = how much darker it is (linear). */
class HighlightFrame(val frame: YuvFrame, val transform: Affine, val ratio: Float)

/**
 * Turns a merged frame into the final picture: optional sharpening, highlight recovery from a short frame,
 * night brightening, tone curve, colour, crop, rotation and mirroring. Also produces the Ultra HDR gain map.
 */
class Renderer(
    private val m: Merged,
    private val tone: ToneSettings,
    private val highlight: HighlightFrame? = null,
    crop: IntArray? = null,
    val rotation: Int = 0,
    val mirror: Boolean = false,
) {
    private val cropLeft = crop?.get(0) ?: 0
    private val cropTop = crop?.get(1) ?: 0
    private val cropW = crop?.get(2) ?: m.width
    private val cropH = crop?.get(3) ?: m.height
    val outWidth = if (rotation == 90 || rotation == 270) cropH else cropW
    val outHeight = if (rotation == 90 || rotation == 270) cropW else cropH

    private val u: ShortArray = if (tone.chromaDenoise) boxBlur(m.u, m.chromaWidth, m.chromaHeight, 2) else m.u
    private val v: ShortArray = if (tone.chromaDenoise) boxBlur(m.v, m.chromaWidth, m.chromaHeight, 2) else m.v

    /** Fills [out] (ARGB, [outWidth] × [rows]) with output rows starting at [row0]. */
    fun render(row0: Int, rows: Int, out: IntArray) {
        Parallel.rows(rows) { from, to ->
            for (r in from until to) {
                val oy = row0 + r
                val base = r * outWidth
                for (ox in 0 until outWidth) {
                    out[base + ox] = shadeOutput(ox, oy, null)
                }
            }
        }
    }

    class GainMap(val width: Int, val height: Int, val data: ByteArray, val headroom: Float)

    /** Quarter-resolution gain map (log2 boost / log2 headroom, 0..255). Null when Ultra HDR is off. */
    fun gainMap(scale: Int = 4): GainMap? {
        if (tone.headroom <= 1.01f) return null
        val gw = (outWidth / scale) and 3.inv() // multiple of 4: no row padding
        val gh = outHeight / scale
        if (gw <= 0 || gh <= 0) return null
        val data = ByteArray(gw * gh)
        val logMax = ln(tone.headroom)
        Parallel.rows(gh) { from, to ->
            val gain = FloatArray(1)
            for (gy in from until to) {
                for (gx in 0 until gw) {
                    shadeOutput((gx * scale + scale / 2).coerceAtMost(outWidth - 1), (gy * scale + scale / 2).coerceAtMost(outHeight - 1), gain)
                    val g = gain[0].coerceIn(1f, tone.headroom)
                    data[gy * gw + gx] = ((ln(g) / logMax) * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
                }
            }
        }
        return GainMap(gw, gh, data, tone.headroom)
    }

    private fun shadeOutput(oxIn: Int, oy: Int, gainOut: FloatArray?): Int {
        val ox = if (mirror) outWidth - 1 - oxIn else oxIn
        val cx: Int
        val cy: Int
        when (rotation) {
            90 -> { cx = oy; cy = cropH - 1 - ox }
            180 -> { cx = cropW - 1 - ox; cy = cropH - 1 - oy }
            270 -> { cx = cropW - 1 - oy; cy = ox }
            else -> { cx = ox; cy = oy }
        }
        return shade(cropLeft + cx, cropTop + cy, gainOut)
    }

    private fun shade(sx: Int, sy: Int, gainOut: FloatArray?): Int {
        val w = m.width
        val h = m.height
        val ys = m.y
        val i = sy * w + sx
        var yv = ys[i] / SCALE_F
        if (tone.sharpen > 0f && sx > 0 && sy > 0 && sx < w - 1 && sy < h - 1) {
            val blur = (4 * ys[i] + 2 * (ys[i - 1] + ys[i + 1] + ys[i - w] + ys[i + w]) +
                ys[i - w - 1] + ys[i - w + 1] + ys[i + w - 1] + ys[i + w + 1]) / (16f * SCALE_F)
            yv += tone.sharpen * (yv - blur)
        }
        // Chroma (half resolution), bilinear.
        val fcx = ((sx - 0.5f) / 2f).coerceIn(0f, m.chromaWidth - 1.001f)
        val fcy = ((sy - 0.5f) / 2f).coerceIn(0f, m.chromaHeight - 1.001f)
        val uv = bilinear(u, fcx, fcy) / SCALE_F - 128f
        val vv = bilinear(v, fcx, fcy) / SCALE_F - 128f

        var r = Srgb.lin(((yv + 1.402f * vv) / 255f).coerceIn(0f, 1f))
        var g = Srgb.lin(((yv - 0.344136f * uv - 0.714136f * vv) / 255f).coerceIn(0f, 1f))
        var b = Srgb.lin(((yv + 1.772f * uv) / 255f).coerceIn(0f, 1f))

        // Highlight recovery: where the normal frames are (nearly) clipped, use the darker frame.
        val hl = highlight
        if (hl != null && yv > 200f) {
            val t = smoothstep(200f, 245f, yv)
            val f = hl.frame
            val fx = hl.transform.mapX(sx.toFloat(), sy.toFloat())
            val fy = hl.transform.mapY(sx.toFloat(), sy.toFloat())
            if (fx >= 0f && fy >= 0f && fx < f.width - 1 && fy < f.height - 1) {
                val hy = bilinearBytes(f.y, f.width, fx, fy)
                val hcx = ((fx - 0.5f) / 2f).coerceIn(0f, f.chromaWidth - 1.001f)
                val hcy = ((fy - 0.5f) / 2f).coerceIn(0f, f.chromaHeight - 1.001f)
                val hu = bilinearBytes(f.u, f.chromaWidth, hcx, hcy) - 128f
                val hv = bilinearBytes(f.v, f.chromaWidth, hcx, hcy) - 128f
                val hr = Srgb.lin(((hy + 1.402f * hv) / 255f).coerceIn(0f, 1f)) * hl.ratio
                val hg = Srgb.lin(((hy - 0.344136f * hu - 0.714136f * hv) / 255f).coerceIn(0f, 1f)) * hl.ratio
                val hb = Srgb.lin(((hy + 1.772f * hu) / 255f).coerceIn(0f, 1f)) * hl.ratio
                val normalL = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val shortL = 0.2126f * hr + 0.7152f * hg + 0.0722f * hb
                // The short frame must be at least about as bright; otherwise something moved: keep the normal pixel.
                if (shortL >= normalL * 0.85f) {
                    r += (hr - r) * t
                    g += (hg - g) * t
                    b += (hb - b) * t
                }
            }
        }

        if (tone.night) {
            val k = tone.gain
            val bl = tone.blackLevel
            val norm = 1f / (1f - bl)
            r = max(0f, r * k - bl) * norm
            g = max(0f, g * k - bl) * norm
            b = max(0f, b * k - bl) * norm
        }

        val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val ls = toneCurve(lum)
        if (lum > 1e-6f) {
            val s = ls / lum
            r *= s; g *= s; b *= s
            val peak = max(r, max(g, b))
            if (peak > 1f) { r /= peak; g /= peak; b /= peak }
        }
        if (gainOut != null) {
            var gain = if (ls > 1e-4f) lum / ls else 1f
            if (hl == null || tone.night) {
                // No real highlight data: gently boost bright areas on HDR screens.
                gain = max(gain, 1f + (tone.headroom - 1f) * 0.5f * smoothstep(0.7f, 1f, ls))
            }
            gainOut[0] = gain
        }

        var gr = Srgb.gam(r)
        var gg = Srgb.gam(g)
        var gb = Srgb.gam(b)
        val sh = tone.shadows
        if (sh != 0f) {
            gr += sh * 0.8f * gr * (1 - gr) * (1 - gr)
            gg += sh * 0.8f * gg * (1 - gg) * (1 - gg)
            gb += sh * 0.8f * gb * (1 - gb) * (1 - gb)
        }
        val sat = tone.saturation
        if (sat != 1f) {
            val l = 0.299f * gr + 0.587f * gg + 0.114f * gb
            gr = l + sat * (gr - l)
            gg = l + sat * (gg - l)
            gb = l + sat * (gb - l)
        }
        val ri = (gr * 255f + 0.5f).toInt().coerceIn(0, 255)
        val gi = (gg * 255f + 0.5f).toInt().coerceIn(0, 255)
        val bi = (gb * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (0xff shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    /** Scene luminance → display luminance. */
    private fun toneCurve(l: Float): Float = when {
        tone.night -> {
            val white = 4f
            l * (1f + l / (white * white)) / (1f + l)
        }
        highlight != null -> {
            val knee = 0.8f
            if (l <= knee) l else { val x = l - knee; val range = 1f - knee; knee + range * x / (x + range) }
        }
        else -> if (l > 1f) 1f else l
    }

    private fun bilinear(plane: ShortArray, fx: Float, fy: Float): Float {
        val cw = m.chromaWidth
        val ix = fx.toInt()
        val iy = fy.toInt()
        val ax = fx - ix
        val ay = fy - iy
        val p = iy * cw + ix
        val top = plane[p] + (plane[p + 1] - plane[p]) * ax
        val bottom = plane[p + cw] + (plane[p + cw + 1] - plane[p + cw]) * ax
        return top + (bottom - top) * ay
    }

    private fun bilinearBytes(plane: ByteArray, stride: Int, fx: Float, fy: Float): Float {
        val ix = fx.toInt()
        val iy = fy.toInt()
        val ax = fx - ix
        val ay = fy - iy
        val p = iy * stride + ix
        val p00 = plane[p].toInt() and 0xff
        val p10 = plane[p + 1].toInt() and 0xff
        val p01 = plane[p + stride].toInt() and 0xff
        val p11 = plane[p + stride + 1].toInt() and 0xff
        val top = p00 + (p10 - p00) * ax
        val bottom = p01 + (p11 - p01) * ax
        return top + (bottom - top) * ay
    }

    companion object {
        private const val SCALE_F = Merged.SCALE.toFloat()

        /** Separable box blur (radius r) of a ShortArray plane. */
        fun boxBlur(src: ShortArray, w: Int, h: Int, r: Int): ShortArray {
            val tmp = ShortArray(src.size)
            val out = ShortArray(src.size)
            val n = 2 * r + 1
            Parallel.rows(h) { from, to ->
                for (y in from until to) {
                    val o = y * w
                    var sum = 0
                    for (k in -r..r) sum += src[o + k.coerceIn(0, w - 1)]
                    for (x in 0 until w) {
                        tmp[o + x] = (sum / n).toShort()
                        sum += src[o + (x + r + 1).coerceAtMost(w - 1)] - src[o + (x - r).coerceAtLeast(0)]
                    }
                }
            }
            Parallel.rows(w) { from, to ->
                for (x in from until to) {
                    var sum = 0
                    for (k in -r..r) sum += tmp[k.coerceIn(0, h - 1) * w + x]
                    for (y in 0 until h) {
                        out[y * w + x] = (sum / n).toShort()
                        sum += tmp[(y + r + 1).coerceAtMost(h - 1) * w + x] - tmp[(y - r).coerceAtLeast(0) * w + x]
                    }
                }
            }
            return out
        }

        /** Small, fast preview (nearest-neighbour) straight from a frame, for the thumbnail. */
        fun preview(frame: YuvFrame, maxSide: Int, rotation: Int, mirror: Boolean, crop: IntArray?): Triple<IntArray, Int, Int> {
            val m = Merged.fromFrameSampled(frame, maxSide)
            val scale = frame.width.toFloat() / m.width
            val scaledCrop = crop?.let { c -> IntArray(4) { (c[it] / scale).toInt() }.also { s -> s[2] = s[2].coerceAtMost(m.width - s[0]); s[3] = s[3].coerceAtMost(m.height - s[1]) } }
            val r = Renderer(m, ToneSettings(), null, scaledCrop, rotation, mirror)
            val out = IntArray(r.outWidth * r.outHeight)
            r.render(0, r.outHeight, out)
            return Triple(out, r.outWidth, r.outHeight)
        }
    }
}

/** Small copy of a frame (every n-th pixel) for previews. */
fun Merged.Companion.fromFrameSampled(frame: YuvFrame, maxSide: Int): Merged {
    val step = maxOf(2, (maxOf(frame.width, frame.height) + maxSide - 1) / maxSide) and 1.inv()
    val w = (frame.width / step) and 1.inv()
    val h = (frame.height / step) and 1.inv()
    val y = ShortArray(w * h)
    for (yy in 0 until h) for (xx in 0 until w) y[yy * w + xx] = ((frame.y[yy * step * frame.width + xx * step].toInt() and 0xff) * SCALE).toShort()
    val cw = w / 2
    val ch = h / 2
    val u = ShortArray(cw * ch)
    val v = ShortArray(cw * ch)
    for (yy in 0 until ch) for (xx in 0 until cw) {
        val src = (yy * step) * frame.chromaWidth + xx * step
        u[yy * cw + xx] = ((frame.u[src].toInt() and 0xff) * SCALE).toShort()
        v[yy * cw + xx] = ((frame.v[src].toInt() and 0xff) * SCALE).toShort()
    }
    return Merged(w, h, y, u, v, 1)
}
