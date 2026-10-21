package io.github.realkhfan.foco

import io.github.realkhfan.foco.scan.DocumentDetector
import io.github.realkhfan.foco.scan.Enhance
import io.github.realkhfan.foco.scan.GrayImage
import io.github.realkhfan.foco.scan.Homography
import io.github.realkhfan.foco.scan.PageShape
import io.github.realkhfan.foco.scan.PaperSize
import io.github.realkhfan.foco.scan.PdfImagePage
import io.github.realkhfan.foco.scan.PdfPageSize
import io.github.realkhfan.foco.scan.PdfWriter
import io.github.realkhfan.foco.scan.Quad
import io.github.realkhfan.foco.scan.ScanFilter
import java.io.File
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Document scanning maths on synthetic pictures (pure JVM). */
class ScanCoreTest {

    /** Is (x, y) inside the convex quad [c]? */
    private fun inside(c: FloatArray, x: Float, y: Float): Boolean {
        for (i in 0 until 4) {
            val ax = c[i * 2]; val ay = c[i * 2 + 1]
            val bx = c[((i + 1) % 4) * 2]; val by = c[((i + 1) % 4) * 2 + 1]
            if ((bx - ax) * (y - ay) - (by - ay) * (x - ax) < 0) return false
        }
        return true
    }

    /** A page lying on a wooden table, with text lines, seen at [corners]. */
    private fun scene(w: Int, h: Int, corners: FloatArray, seed: Long = 1, clutter: Boolean = true): GrayImage {
        val rnd = Random(seed)
        val data = FloatArray(w * h)
        val toPage = Homography.between(corners, floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f))!!
        for (y in 0 until h) for (x in 0 until w) {
            var v = 95f + 18f * sin(x * 0.09f + sin(y * 0.03f) * 3f) + rnd.nextGaussian().toFloat() * 4f
            if (clutter && x in 20..60 && y in (h - 50)..(h - 20)) v = 30f // a phone on the table
            if (inside(corners, x.toFloat(), y.toFloat())) {
                val u = toPage.mapX(x.toDouble(), y.toDouble())
                val t = toPage.mapY(x.toDouble(), y.toDouble())
                v = 222f + rnd.nextGaussian().toFloat() * 3f
                // Lines of text.
                if (u in 0.1..0.9 && t in 0.1..0.9 && ((t * 30).toInt() % 2 == 0) && ((u * 40).toInt() % 3 != 0)) v = 60f
            }
            data[y * w + x] = v.coerceIn(0f, 255f)
        }
        return GrayImage(w, h, data)
    }

    private fun maxCornerError(a: FloatArray, b: FloatArray): Float =
        (0 until 4).maxOf { hypot(a[it * 2] - b[it * 2], a[it * 2 + 1] - b[it * 2 + 1]) }

    @Test fun `finds a page seen at an angle`() {
        val truth = Quad.order(floatArrayOf(70f, 40f, 255f, 52f, 275f, 215f, 48f, 200f))
        val result = DocumentDetector.detect(scene(320, 240, truth))
        assertNotNull(result)
        val err = maxCornerError(result!!.corners, truth)
        assertTrue("corner error $err px", err < 4f)
    }

    @Test fun `finds a turned page`() {
        val cx = 160f; val cy = 120f; val a = Math.toRadians(28.0)
        val pts = FloatArray(8)
        listOf(-80f to -105f, 80f to -105f, 80f to 105f, -80f to 105f).forEachIndexed { i, (px, py) ->
            pts[i * 2] = cx + (px * cos(a) - py * sin(a)).toFloat() * 0.85f
            pts[i * 2 + 1] = cy + (px * sin(a) + py * cos(a)).toFloat() * 0.85f
        }
        val truth = Quad.order(pts)
        val result = DocumentDetector.detect(scene(320, 240, truth, seed = 2))
        assertNotNull(result)
        assertTrue("corner error ${maxCornerError(result!!.corners, truth)}", maxCornerError(result.corners, truth) < 5f)
    }

    @Test fun `no page means no outline`() {
        val rnd = Random(4)
        val img = GrayImage(320, 240, FloatArray(320 * 240) { 100f + rnd.nextGaussian().toFloat() * 6f })
        assertNull(DocumentDetector.detect(img))
    }

    @Test fun `corners turn with the picture`() {
        val c = floatArrayOf(10f, 20f, 90f, 20f, 90f, 60f, 10f, 60f) // in a 100×80 frame
        val r = Quad.rotate(c, 100f, 80f, 90) // upright frame is 80×100
        // Top-left after a clockwise quarter turn comes from the bottom-left.
        assertEquals(20f, r[0], 0.01f); assertEquals(10f, r[1], 0.01f)
        assertEquals(60f, r[2], 0.01f); assertEquals(10f, r[3], 0.01f)
        assertTrue(Quad.isConvex(r))
    }

    @Test fun `homography maps the corners`() {
        val src = floatArrayOf(12f, 30f, 210f, 18f, 230f, 300f, 5f, 280f)
        val dst = floatArrayOf(0f, 0f, 100f, 0f, 100f, 140f, 0f, 140f)
        val hm = Homography.between(src, dst)!!
        for (i in 0 until 4) {
            assertEquals(dst[i * 2].toDouble(), hm.mapX(src[i * 2].toDouble(), src[i * 2 + 1].toDouble()), 1e-6)
            assertEquals(dst[i * 2 + 1].toDouble(), hm.mapY(src[i * 2].toDouble(), src[i * 2 + 1].toDouble()), 1e-6)
        }
    }

    /** Projects an A4 sheet (210×297 mm) tilted away from the camera, like holding the phone at an angle. */
    @Test fun `real page proportions from a tilted view`() {
        val f = 2900f; val cx = 2040f; val cy = 1536f
        val tilt = Math.toRadians(35.0)
        val corners = FloatArray(8)
        listOf(-105.0 to -148.5, 105.0 to -148.5, 105.0 to 148.5, -105.0 to 148.5).forEachIndexed { i, (x, y) ->
            // Rotate the sheet around its x axis, then put it 450 mm in front of the camera.
            val y3 = y * cos(tilt)
            val z3 = 450 + y * sin(tilt)
            corners[i * 2] = (cx + f * x / z3).toFloat()
            corners[i * 2 + 1] = (cy + f * y3 / z3).toFloat()
        }
        val simple = PageShape.simpleAspect(corners)
        val estimated = PageShape.aspect(corners, f, cx, cy)
        assertTrue("simple estimate is fooled by perspective: $simple", abs(simple - 1.414f) > 0.1f)
        assertEquals(1.414f, estimated, 0.03f)
        val (snapped, paper) = PageShape.snap(estimated)
        assertEquals(PaperSize.A4, paper)
        assertEquals(297f / 210f, snapped, 0.0001f)
        val (w, h) = PageShape.outputSize(corners, snapped, 3508)
        assertTrue(h > w && abs(h.toFloat() / w - 1.414f) < 0.01f)
    }

    private fun page(w: Int, h: Int, shadow: Boolean): IntArray = IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        val light = if (shadow) 0.55f + 0.45f * x / w else 1f // dark on the left: a shadow
        val ink = (y / 12) % 2 == 0 && x % 40 < 30 && y > 20 && y < h - 20
        val base = if (ink) 45f else 225f
        val v = (base * light).toInt()
        (0xff shl 24) or (v shl 16) or (v shl 8) or v
    }

    @Test fun `color filter removes the shadow and keeps the text`() {
        val w = 400; val h = 300
        val px = page(w, h, shadow = true)
        Enhance.apply(px, w, h, ScanFilter.COLOR)
        var paperMin = 255; var inkMax = 0
        for (y in 20 until h - 20) for (x in 0 until w) {
            val v = px[y * w + x] and 0xff
            val ink = (y / 12) % 2 == 0 && x % 40 < 30
            val edge = (y % 12 == 0 || y % 12 == 11) || x % 40 == 0 || x % 40 == 29
            if (edge) continue
            if (ink) inkMax = maxOf(inkMax, v) else paperMin = minOf(paperMin, v)
        }
        assertTrue("paper should be white everywhere, darkest $paperMin", paperMin > 225)
        assertTrue("ink should stay dark, lightest $inkMax", inkMax < 110)
    }

    @Test fun `black and white gives pure ink on white`() {
        val w = 400; val h = 300
        val px = page(w, h, shadow = true)
        Enhance.apply(px, w, h, ScanFilter.BW)
        assertEquals(0xffffffff.toInt(), px[30 * w + 35]) // gap between words in a text line → white
        assertEquals(0xff000000.toInt(), px[25 * w + 10]) // inside a word in the shadow → black
        assertEquals(0xffffffff.toInt(), px[18 * w + 10]) // between lines → white
        assertTrue(px.all { it == 0xffffffff.toInt() || it == 0xff000000.toInt() })
    }

    private fun jpeg(name: String): ByteArray = javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()

    @Test fun `pdf has every page and a valid cross-reference table`() {
        val pages = listOf(PdfImagePage(jpeg("page_portrait.jpg"), 210, 297), PdfImagePage(jpeg("page_landscape.jpg"), 300, 200))
        val bytes = PdfWriter.toBytes(pages, PdfPageSize.A4, "Scan (test) – ä")
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF-1.4"))
        assertTrue(text.contains("/Count 2"))
        assertTrue(text.contains("/MediaBox [0 0 841.89 595.28]")) // landscape scan → landscape page
        assertTrue(text.contains("/Title <FEFF"))
        // Every xref entry points at "N 0 obj".
        val xrefAt = text.substring(text.lastIndexOf("startxref") + 10).trim().lines().first().toInt()
        val entries = text.substring(xrefAt).lines().drop(3).takeWhile { it.endsWith("n ") }
        entries.forEachIndexed { i, line ->
            val offset = line.substring(0, 10).toInt()
            assertTrue("object ${i + 1}", text.startsWith("${i + 1} 0 obj", offset))
        }
        // Keep a copy for checking with a real PDF reader.
        File("build/scan-test.pdf").apply { parentFile?.mkdirs() }.writeBytes(bytes)
    }

    @Test fun `pdf text strings are escaped`() {
        assertEquals("(a \\(b\\) c\\\\)", PdfWriter.text("a (b) c\\"))
        assertEquals("<FEFF00E4>", PdfWriter.text("ä"))
    }
}
