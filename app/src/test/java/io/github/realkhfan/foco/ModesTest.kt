package io.github.realkhfan.foco

import io.github.realkhfan.foco.processing.Affine
import io.github.realkhfan.foco.processing.Aligner
import io.github.realkhfan.foco.processing.LumaPyramid
import io.github.realkhfan.foco.processing.MergeAccumulator
import io.github.realkhfan.foco.processing.Merged
import io.github.realkhfan.foco.processing.PanoShot
import io.github.realkhfan.foco.processing.PanoramaStitcher
import io.github.realkhfan.foco.processing.YuvFrame
import io.github.realkhfan.foco.processing.YuvOps
import java.util.Random
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Panorama, Long Exposure and Action Pan on synthetic scenes (pure JVM). */
class ModesTest {

    // ------------------------------------------------------------------ panorama

    /** A textured world around the camera: [phi] = direction (radians), [t] = tan(elevation). */
    private fun world(phi: Float, t: Float): Float {
        val a = sin(phi * 23f) * cos(t * 17f) * 40f
        val b = sin((phi * 61f + t * 37f)) * 22f
        val c = cos(phi * 7f - t * 5f) * 30f
        val blocks = if (((phi * 40f).toInt() + (t * 30f + 100).toInt()) % 3 == 0) 18f else 0f
        return (125f + a + b + c + blocks).coerceIn(0f, 255f)
    }

    private val pw = 480
    private val ph = 640
    private val focal = 520f

    /** What the camera sees when turned by [yaw] (and tilted by [tilt] in tan units). */
    private fun view(yaw: Float, tilt: Float): YuvFrame {
        val f = YuvFrame(pw, ph)
        for (y in 0 until ph) for (x in 0 until pw) {
            val xr = x - pw / 2f
            val phi = yaw + atan(xr / focal)
            val t = (y - ph / 2f) / sqrt(xr * xr + focal * focal) + tilt
            f.y[y * pw + x] = world(phi, t).toInt().toByte()
        }
        f.u.fill(128.toByte()); f.v.fill(128.toByte())
        return f
    }

    private fun stitchAndCompare(recordedSign: Float) {
        val rnd = Random(7)
        val trueYaws = listOf(0f, 0.36f, 0.71f, 1.05f, 1.42f)
        val shots = trueYaws.map { yaw ->
            val tilt = (rnd.nextFloat() - 0.5f) * 0.02f
            // The gyroscope is a bit off; the stitcher has to find the real positions.
            PanoShot(view(yaw, tilt), recordedSign * (yaw + (rnd.nextFloat() - 0.5f) * 0.06f), 0f)
        }
        val stitcher = PanoramaStitcher(shots, focal)
        val layout = stitcher.layout()
        val out = stitcher.output(layout)
        val expectedWidth = (trueYaws.last() - trueYaws.first()) * focal + stitcher.cylWidth * 0.84f
        assertTrue("width ${out.width} vs $expectedWidth", abs(out.width - expectedWidth) < 20)
        assertTrue("height ${out.height}", out.height > ph * 0.7f)
        val pixels = IntArray(out.width * out.height)
        out.render(0, out.height, pixels)
        // Compare with the true world, allowing for the unknown vertical offset of the first frame.
        var err = 0.0
        var n = 0
        val phi0 = layout.order[0].let { s -> trueYaws[shots.indexOf(s)] }
        for (row in 20 until out.height - 20 step 3) for (col in 20 until out.width - 20 step 3) {
            val x = (out.left + col).toFloat()
            val y = (out.top + row).toFloat()
            val phi = phi0 + (x - layout.xs[0] - stitcher.cylWidth / 2f) / focal
            val t = (y - layout.ys[0] - ph / 2f) / focal
            val expected = world(phi, t + shotsTilt0(shots, layout))
            val actual = (pixels[row * out.width + col] shr 8) and 0xff
            err += abs(actual - expected)
            n++
        }
        val mae = err / n
        println("panorama ${out.width}x${out.height}, mean error ${"%.2f".format(mae)} levels")
        assertTrue("mean error $mae", mae < 6.0)
    }

    // The first frame's own tilt shifts the whole panorama slightly; take it from the test's random sequence.
    private fun shotsTilt0(shots: List<PanoShot>, layout: PanoramaStitcher.Layout): Float {
        val s = layout.order[0]
        // Recover the tilt the frame was rendered with by comparing its centre row with the world.
        var best = 0f
        var bestErr = Float.MAX_VALUE
        var t = -0.02f
        while (t <= 0.02f) {
            var e = 0f
            for (x in 100 until 380 step 20) {
                val xr = x - pw / 2f
                val phi = shots.indexOf(s).let { 0f } + atan(xr / focal)
                e += abs((s.frame.y[(ph / 2) * pw + x].toInt() and 0xff) - world(phi + yawOf(s), t))
            }
            if (e < bestErr) { bestErr = e; best = t }
            t += 0.0005f
        }
        return best
    }

    private val yawCache = HashMap<PanoShot, Float>()
    private fun yawOf(s: PanoShot): Float = yawCache.getOrPut(s) {
        // Find the true yaw by matching the centre column against the world.
        var best = 0f
        var bestErr = Float.MAX_VALUE
        var yaw = -0.1f
        while (yaw < 1.6f) {
            var e = 0f
            for (y in 100 until 540 step 40) e += abs((s.frame.y[y * pw + pw / 2].toInt() and 0xff) - world(yaw, (y - ph / 2f) / focal))
            if (e < bestErr) { bestErr = e; best = yaw }
            yaw += 0.002f
        }
        best
    }

    @Test fun `panorama lines up frames from a slightly wrong gyroscope`() = stitchAndCompare(1f)

    @Test fun `panorama works when the gyroscope direction is reversed`() = stitchAndCompare(-1f)

    @Test fun `downscale and rotate a frame`() {
        val src = YuvFrame(8, 4)
        for (y in 0 until 4) for (x in 0 until 8) src.y[y * 8 + x] = (x * 10 + y).toByte()
        src.u.fill(100.toByte()); src.v.fill(150.toByte())
        val r = YuvOps.downscaleRotate(src, 90)
        assertEquals(2, r.width)
        assertEquals(4, r.height)
        assertEquals(8, r.y[0].toInt() and 0xff) // bottom-left of the source ends up top-left
        assertEquals(6, r.y[1].toInt() and 0xff)
        assertEquals(100, r.u[0].toInt() and 0xff)
        val same = YuvOps.downscaleRotate(src, 0)
        assertEquals(4, same.width)
        assertEquals(2, same.height)
    }

    // ------------------------------------------------------------------ long exposure & action pan

    // Big enough for the aligner's coarse level (it works on 1/16 size, made for 12 MP frames).
    private val w = 1280
    private val h = 960

    private fun texture(x: Float, y: Float): Float =
        (120f + sin(x * 0.07f) * cos(y * 0.05f) * 50f + sin((x + y) * 0.19f) * 25f +
            if (((x / 23f).toInt() + (y / 29f).toInt()) % 2 == 0) 20f else 0f).coerceIn(0f, 255f)

    private fun frame(fill: (Int, Int) -> Float): YuvFrame {
        val f = YuvFrame(w, h)
        for (y in 0 until h) for (x in 0 until w) f.y[y * w + x] = fill(x, y).toInt().coerceIn(0, 255).toByte()
        f.u.fill(128.toByte()); f.v.fill(128.toByte())
        return f
    }

    @Test fun `long exposure blurs what moves and keeps the background sharp`() {
        val shake = listOf(0f to 0f, 2.3f to -1.1f, -1.7f to 2.4f, 3.1f to 0.6f, -2.2f to -2.9f, 0.8f to 3.3f, -0.4f to -1.8f, 1.6f to 1.2f)
        // A bright car drives through the middle; the camera shakes a little.
        fun scene(i: Int, dx: Float, dy: Float) = frame { x, y ->
            val sx = x + dx
            val sy = y + dy
            val carX = 200f + i * 100f
            if (sy in 400f..520f && sx in carX..carX + 120f) 250f else texture(sx, sy)
        }
        val ref = scene(0, 0f, 0f)
        val acc = MergeAccumulator(ref)
        val pyr = LumaPyramid(ref)
        shake.drop(1).forEachIndexed { k, (dx, dy) ->
            val f = scene(k + 1, dx, dy)
            acc.addAverage(f, Aligner.align(pyr, LumaPyramid(f)).transform)
        }
        val m = acc.finish()
        // Background (top area) matches the sharp texture.
        var bgErr = 0.0; var n = 0
        for (y in 60 until 340 step 3) for (x in 60 until 1220 step 3) {
            bgErr += abs(m.y[y * w + x] / Merged.SCALE.toFloat() - texture(x.toFloat(), y.toFloat())); n++
        }
        assertTrue("background error ${bgErr / n}", bgErr / n < 4.0)
        // The car is spread along its path: no position is the full bright car any more.
        val onPath = m.y[460 * w + 600] / Merged.SCALE.toFloat()
        assertTrue("car trail $onPath", onPath in 135f..230f)
    }

    @Test fun `action pan keeps the subject sharp and streaks the background`() {
        val rnd = Random(3)
        // The camera follows the subject (small wobble), so the background slides 30 px per frame.
        // A subject with natural, non-repeating detail (a cyclist, a dog …).
        val subject = { x: Float, y: Float ->
            60f + sin(x * 0.045f + 0.7f) * cos(y * 0.037f) * 45f + sin((x - 2 * y) * 0.021f) * 25f +
                (if ((x - 160f) * (x - 160f) + (y - 120f) * (y - 120f) < 3600f) 50f else 0f) +
                (if (x in 40f..90f && y in 200f..290f) 35f else 0f)
        }
        fun scene(i: Int, wobbleX: Float, wobbleY: Float) = frame { x, y ->
            val sx = x + wobbleX
            val sy = y + wobbleY
            if (sx in 480f..800f && sy in 320f..640f) subject(sx - 480f, sy - 320f) else texture(sx + i * 40f, sy)
        }
        val ref = scene(0, 0f, 0f)
        val acc = MergeAccumulator(ref)
        val pyr = LumaPyramid(ref)
        val wobbles = (1..7).map { (rnd.nextFloat() - 0.5f) * 8f to (rnd.nextFloat() - 0.5f) * 8f }
        wobbles.forEachIndexed { k, (wx, wy) ->
            val f = scene(k + 1, wx, wy)
            val t = Aligner.refineTranslation(ref, f, Aligner.alignRegion(pyr, LumaPyramid(f)).transform, w / 2, h / 2, half = h / 10)
            // frame(p) shows the scene at p + wobble, so reference → frame is a shift by -wobble.
            assertTrue("subject shift x ${t.tx} vs ${-wx}", abs(t.tx + wx) < 0.5f)
            assertTrue("subject shift y ${t.ty} vs ${-wy}", abs(t.ty + wy) < 0.5f)
            acc.addAverage(f, t)
        }
        val m = acc.finish()
        var subjErr = 0.0; var n = 0
        for (y in 340 until 620 step 3) for (x in 500 until 780 step 3) {
            subjErr += abs(m.y[y * w + x] / Merged.SCALE.toFloat() - subject(x - 480f, y - 320f)); n++
        }
        assertTrue("subject error ${subjErr / n}", subjErr / n < 6.0)
        // Background: horizontal detail is smoothed out (streaks).
        fun detail(get: (Int) -> Float): Float {
            var s = 0f
            for (x in 40 until 440) s += abs(get(120 * w + x + 1) - get(120 * w + x))
            return s
        }
        val sharp = detail { (ref.y[it].toInt() and 0xff).toFloat() }
        val streaked = detail { m.y[it] / Merged.SCALE.toFloat() }
        assertTrue("background detail $streaked vs $sharp", streaked < sharp * 0.5f)
    }

    @Test fun `average merge with identity gives the plain mean`() {
        val a = frame { _, _ -> 100f }
        val b = frame { _, _ -> 200f }
        val acc = MergeAccumulator(a)
        acc.addAverage(b, Affine.IDENTITY)
        val m = acc.finish()
        assertEquals(150f, m.y[1000] / Merged.SCALE.toFloat(), 0.6f)
    }
}
