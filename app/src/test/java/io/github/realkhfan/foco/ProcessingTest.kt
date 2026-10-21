package io.github.realkhfan.foco

import io.github.realkhfan.foco.processing.Affine
import io.github.realkhfan.foco.processing.Aligner
import io.github.realkhfan.foco.processing.JpegSplice
import io.github.realkhfan.foco.processing.LumaPyramid
import io.github.realkhfan.foco.processing.MergeAccumulator
import io.github.realkhfan.foco.processing.Merged
import io.github.realkhfan.foco.processing.Motion
import io.github.realkhfan.foco.processing.Renderer
import io.github.realkhfan.foco.processing.ShotKind
import io.github.realkhfan.foco.processing.ShotPlanner
import io.github.realkhfan.foco.processing.Srgb
import io.github.realkhfan.foco.processing.ToneSettings
import io.github.realkhfan.foco.processing.YuvFrame
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The multi-frame pipeline on synthetic pictures: pure JVM, no phone needed. */
class ProcessingTest {
    private val w = 960
    private val h = 720

    /** A smooth, textured "scene" so alignment has something to lock on to. */
    private fun scene(x: Float, y: Float): Float {
        val a = sin(x * 0.031f) * cos(y * 0.027f) * 50f
        val b = sin((x + y) * 0.071f) * 25f
        val c = cos(x * 0.013f - y * 0.019f) * 30f
        val blob = if (((x / 37f).toInt() + (y / 41f).toInt()) % 3 == 0) 20f else 0f
        return (120f + a + b + c + blob).coerceIn(0f, 255f)
    }

    /** Frame showing the scene through [t] (reference → frame mapping inverted), plus noise. */
    private fun frame(t: Affine, noise: Float, seed: Long): YuvFrame {
        val f = YuvFrame(w, h)
        val rnd = Random(seed)
        // frame(p) = scene(T⁻¹ p): build the inverse of the small affine.
        val det = t.a * t.d - t.b * t.c
        val ia = t.d / det; val ib = -t.b / det; val ic = -t.c / det; val id = t.a / det
        for (yy in 0 until h) for (xx in 0 until w) {
            val px = xx - t.tx
            val py = yy - t.ty
            val sx = ia * px + ib * py
            val sy = ic * px + id * py
            val v = scene(sx, sy) + rnd.nextGaussian().toFloat() * noise
            f.y[yy * w + xx] = v.toInt().coerceIn(0, 255).toByte()
        }
        for (i in f.u.indices) { f.u[i] = 128.toByte(); f.v[i] = 128.toByte() }
        return f
    }

    @Test fun `aligner finds shift and small rotation`() {
        val ref = frame(Affine.IDENTITY, 0f, 1)
        val angle = Math.toRadians(0.4).toFloat()
        val truth = Affine(cos(angle), -sin(angle), 11.3f, sin(angle), cos(angle), -7.6f)
        val moved = frame(truth, 0f, 2)
        val result = Aligner.align(LumaPyramid(ref), LumaPyramid(moved))
        assertTrue("reliable", result.reliable)
        // Check the mapping at several points across the frame.
        for ((x, y) in listOf(100f to 100f, 480f to 360f, 860f to 620f, 100f to 620f)) {
            val ex = result.transform.mapX(x, y) - truth.mapX(x, y)
            val ey = result.transform.mapY(x, y) - truth.mapY(x, y)
            assertTrue("error at ($x,$y) = ${sqrt(ex * ex + ey * ey)}", sqrt(ex * ex + ey * ey) < 1.0f)
        }
    }

    @Test fun `merging aligned noisy frames removes most noise`() {
        val clean = frame(Affine.IDENTITY, 0f, 0)
        val ref = frame(Affine.IDENTITY, 8f, 10)
        val acc = MergeAccumulator(ref)
        val refPyr = LumaPyramid(ref)
        val shifts = listOf(3.2f to -1.5f, -2.7f to 4.1f, 5.5f to 2.2f, -1.1f to -3.9f, 0.6f to 6.3f, -4.4f to 0.8f, 2.0f to 2.0f)
        shifts.forEachIndexed { i, (dx, dy) ->
            val f = frame(Affine.translation(dx, dy), 8f, 20L + i)
            val t = Aligner.align(refPyr, LumaPyramid(f)).transform
            acc.add(f, t, MergeAccumulator.sigmaForIso(3200))
        }
        val merged = acc.finish()
        assertEquals(8, merged.frames)
        fun rmsError(get: (Int) -> Float): Float {
            var s = 0.0; var n = 0
            for (yy in 40 until h - 40) for (xx in 40 until w - 40) {
                val i = yy * w + xx
                val d = get(i) - (clean.y[i].toInt() and 0xff)
                s += d * d; n++
            }
            return sqrt(s / n).toFloat()
        }
        val before = rmsError { (ref.y[it].toInt() and 0xff).toFloat() }
        val after = rmsError { merged.y[it] / Merged.SCALE.toFloat() }
        assertTrue("noise before=$before after=$after", after < before * 0.6f)
    }

    @Test fun `moving object does not ghost`() {
        val ref = frame(Affine.IDENTITY, 2f, 3)
        val acc = MergeAccumulator(ref)
        repeat(4) { k ->
            val f = frame(Affine.IDENTITY, 2f, 30L + k)
            // A bright square that is only in the other frames ("someone walked through").
            for (yy in 300 until 400) for (xx in 400 until 500) f.y[yy * w + xx] = 250.toByte()
            acc.add(f, Affine.IDENTITY, 4f)
        }
        val m = acc.finish()
        val inside = m.y[350 * w + 450] / Merged.SCALE.toFloat()
        val refValue = (ref.y[350 * w + 450].toInt() and 0xff).toFloat()
        assertTrue("ghost: merged=$inside ref=$refValue", abs(inside - refValue) < 20f)
    }

    @Test fun `neutral grey stays grey and rotation swaps size`() {
        val f = YuvFrame(64, 48)
        f.y.fill(118.toByte()); f.u.fill(128.toByte()); f.v.fill(128.toByte())
        val r = Renderer(Merged.fromFrame(f), ToneSettings(), rotation = 90)
        assertEquals(48, r.outWidth)
        assertEquals(64, r.outHeight)
        val out = IntArray(r.outWidth * r.outHeight)
        r.render(0, r.outHeight, out)
        val px = out[out.size / 2]
        val red = (px shr 16) and 0xff; val green = (px shr 8) and 0xff; val blue = px and 0xff
        assertTrue(abs(red - 118) <= 1 && abs(green - 118) <= 1 && abs(blue - 118) <= 1)
    }

    @Test fun `night gain brightens and gain map is produced`() {
        val f = YuvFrame(64, 48)
        f.y.fill(30.toByte()); f.u.fill(128.toByte()); f.v.fill(128.toByte())
        for (i in 0 until 64 * 4) f.y[i] = 250.toByte() // a light in the frame
        val merged = Merged.fromFrame(f)
        val gain = ShotPlanner.nightGain(merged.medianLinearLuma(Srgb.levelToLinear))
        assertTrue(gain > 2f)
        val r = Renderer(merged, ToneSettings(night = true, gain = gain, headroom = 4f))
        val out = IntArray(r.outWidth * r.outHeight)
        r.render(0, r.outHeight, out)
        val dark = out[40 * 64 + 10] and 0xff
        assertTrue("brightened: $dark", dark > 60)
        val map = r.gainMap()
        assertNotNull(map)
        assertEquals(0, map!!.width % 4)
        assertTrue(map.data.any { (it.toInt() and 0xff) > 0 })
    }

    @Test fun `highlight frame recovers clipped sky`() {
        val normal = YuvFrame(64, 48)
        normal.y.fill(255.toByte()); normal.u.fill(128.toByte()); normal.v.fill(128.toByte())
        val short = YuvFrame(64, 48)
        short.y.fill(170.toByte()); short.u.fill(128.toByte()); short.v.fill(128.toByte())
        val ratio = Srgb.lin(1f) / Srgb.lin(170 / 255f) * 1.6f // the real sky was brighter than white
        val r = Renderer(
            Merged.fromFrame(normal), ToneSettings(headroom = 4f),
            io.github.realkhfan.foco.processing.HighlightFrame(short, Affine.IDENTITY, ratio),
        )
        val out = IntArray(64 * 48)
        r.render(0, 48, out)
        val level = out[100] and 0xff
        assertTrue("sky compressed into SDR, not blown: $level", level in 200..252)
        val g = r.gainMap()!!
        assertTrue("HDR boost stored", (g.data[0].toInt() and 0xff) > 60)
    }

    @Test fun `planner picks night and zoom`() {
        assertTrue(ShotPlanner.isDark(100_000_000L, 1600))
        assertTrue(!ShotPlanner.isDark(10_000_000L, 100))
        val night = ShotPlanner.night(3f, 66_000_000L, 6400, Motion.STEADY, 1_000_000_000L, 50, 12800)
        assertEquals(ShotKind.NIGHT, night.kind)
        assertTrue(night.exposureNs!! in 100_000_000L..333_333_333L)
        assertTrue(night.frames in 3..15)
        val shaky = ShotPlanner.night(3f, 66_000_000L, 6400, Motion.SHAKY, 1_000_000_000L, 50, 12800)
        assertTrue(shaky.exposureNs!! <= 125_000_000L)
        assertEquals(ShotKind.ZOOM, ShotPlanner.daylight(0f, 2.5f, true).kind)
        assertEquals(ShotKind.HDR, ShotPlanner.daylight(0.01f, 1f, true).kind)
        assertNotNull(ShotPlanner.daylight(0.01f, 1f, true).highlightDivisor)
        assertTrue(ShotPlanner.autoNightSeconds(66_000_000L, 6400, Motion.TRIPOD) > ShotPlanner.autoNightSeconds(66_000_000L, 6400, Motion.SHAKY))
    }

    @Test fun `exif is spliced after jfif and replaces old exif`() {
        val soi = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        val app0 = byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0, 6, 'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte())
        val app2 = byteArrayOf(0xFF.toByte(), 0xE2.toByte(), 0, 4, 1, 2)
        val sos = byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 2, 9, 9, 0xFF.toByte(), 0xD9.toByte())
        val jpeg = soi + app0 + app2 + sos
        val exif = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0, 10, 'E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0, 7, 7)
        val out = JpegSplice.insertExif(jpeg, exif)
        assertArrayEquals(soi + app0 + exif + app2 + sos, out)
        assertArrayEquals(exif, JpegSplice.exifSegment(out))
        val exif2 = exif.copyOf().also { it[10] = 5 }
        val again = JpegSplice.insertExif(out, exif2)
        assertArrayEquals(soi + app0 + exif2 + app2 + sos, again)
    }

    @Test fun `full size merge is fast enough`() {
        // 12.5 MP like the Pixel 8 Pro main camera.
        val big = YuvFrame(4080, 3072)
        val rnd = Random(5)
        rnd.nextBytes(big.y)
        big.u.fill(128.toByte()); big.v.fill(128.toByte())
        val other = YuvFrame(4080, 3072).also { it.copyFrom(big) }
        val acc = MergeAccumulator(big)
        val pyr = LumaPyramid(big)
        val start = System.nanoTime()
        val t = Aligner.align(pyr, LumaPyramid(other)).transform
        acc.add(other, t, 4f)
        val merged = acc.finish()
        val r = Renderer(merged, ToneSettings(sharpen = 0.3f, headroom = 2f), rotation = 90)
        val band = IntArray(r.outWidth * 128)
        var row = 0
        while (row < r.outHeight) { val n = minOf(128, r.outHeight - row); r.render(row, n, band); row += n }
        r.gainMap()
        val ms = (System.nanoTime() - start) / 1e6
        println("align+merge+render 12.5MP: ${ms.toInt()} ms on ${Runtime.getRuntime().availableProcessors()} cores")
        assertTrue("took $ms ms", ms < 30_000)
    }
}
