package io.github.realkhfan.foco

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.realkhfan.foco.scan.ExportFormat
import io.github.realkhfan.foco.scan.ExportNames
import io.github.realkhfan.foco.scan.ExportOptions
import io.github.realkhfan.foco.scan.ExportQuality
import io.github.realkhfan.foco.scan.PdfPageSize
import io.github.realkhfan.foco.scan.Quad
import io.github.realkhfan.foco.scan.ScanExporter
import io.github.realkhfan.foco.scan.ScanFilter
import io.github.realkhfan.foco.scan.ScanManifest
import io.github.realkhfan.foco.scan.ScanPage
import io.github.realkhfan.foco.scan.ScanStore
import java.io.File
import java.util.Date
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The scan pipeline on a drawn photo of a page: keeping pages, finding the page, flattening, cleaning, exporting. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ScanAndroidTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var store: ScanStore

    @Before fun setUp() {
        store = ScanStore(app)
        store.clear()
    }

    @After fun tearDown() = store.clear()

    private fun expected() = Quad.scale(ScanFixtures.CORNERS, 1f / ScanFixtures.PHOTO_W, 1f / ScanFixtures.PHOTO_H)

    private fun maxError(a: FloatArray, b: FloatArray) = (0 until 4).maxOf { hypot(a[it * 2] - b[it * 2], a[it * 2 + 1] - b[it * 2 + 1]) }

    private fun luma(c: Int) = 0.299f * ((c shr 16) and 0xff) + 0.587f * ((c shr 8) and 0xff) + 0.114f * (c and 0xff)

    /** Average brightness of the paper (bright pixels only, so text doesn't count) in a box of fractions. */
    private fun paper(b: Bitmap, x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val values = mutableListOf<Float>()
        for (y in (y0 * b.height).toInt() until (y1 * b.height).toInt() step 3) {
            for (x in (x0 * b.width).toInt() until (x1 * b.width).toInt() step 3) values += luma(b.getPixel(x, y))
        }
        values.sort()
        return values.subList(values.size * 3 / 4, values.size).average().toFloat()
    }

    @Test fun findsThePageInEveryCameraRotation() {
        val photo = ScanFixtures.photo()
        for (rotation in listOf(0, 90, 180, 270)) {
            val page = store.add(ScanFixtures.sensorJpeg(photo, rotation), rotation, 0f)
            val err = maxError(page.corners, expected())
            assertTrue("rotation $rotation: corners off by $err", err < 0.02f)
            val upright = store.upright(page, 800)!!
            assertEquals("upright width", 600, upright.width)
            assertEquals("upright height", 800, upright.height)
        }
    }

    @Test fun flattensToA4AndRemovesTheShadow() {
        val page = store.add(ScanFixtures.sensorJpeg(ScanFixtures.photo(), 90), 90, 0f)
        val color = store.render(page, 1400)!!
        // As sharp as the photo allows (the page is ≈1240 px tall in the photo), never blown up.
        assertEquals("long side", 1240f, color.height.toFloat(), 20f)
        assertEquals("smaller when asked", 700, store.render(page, 700)!!.height)
        assertEquals("A4 proportions", 297f / 210f, color.height.toFloat() / color.width, 0.01f)

        // The original photo: the shadow makes the right side of the paper much darker.
        val original = store.render(page.edited(filter = ScanFilter.ORIGINAL), 1400)!!
        val shadowBefore = paper(original, 0.05f, 0.4f, 0.25f, 0.6f) - paper(original, 0.75f, 0.4f, 0.95f, 0.6f)
        assertTrue("shadow in the photo ($shadowBefore)", shadowBefore > 30f)
        // Color clean-up: paper is white everywhere.
        val left = paper(color, 0.05f, 0.4f, 0.25f, 0.6f)
        val right = paper(color, 0.75f, 0.4f, 0.95f, 0.6f)
        assertTrue("paper white: $left / $right", left > 235f && right > 235f)
        assertTrue("shadow gone: ${left - right}", abs(left - right) < 10f)
        // No table left at the edges.
        assertTrue("edges are paper", paper(color, 0f, 0f, 1f, 0.02f) > 220f)
        // The text is still there and dark.
        var dark = 0
        for (y in 0 until color.height step 2) for (x in 0 until color.width step 2) if (luma(color.getPixel(x, y)) < 100) dark++
        val darkShare = dark / (color.width * color.height / 4f)
        assertTrue("text kept ($darkShare)", darkShare in 0.03f..0.4f)

        // Black & white: only (almost) pure black and white.
        val bw = store.render(page.edited(filter = ScanFilter.BW), 1000)!!
        var grey = 0
        for (y in 0 until bw.height step 3) for (x in 0 until bw.width step 3) if (luma(bw.getPixel(x, y)) in 40f..215f) grey++
        assertTrue("black & white", grey < bw.width * bw.height / 9 / 50)
        // Kept for looking at (build/scan-*.png).
        File("build/scan-render").mkdirs()
        listOf("original" to original, "color" to color, "bw" to bw).forEach { (name, b) ->
            File("build/scan-render/$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        File("build/scan-render/photo.jpg").outputStream().use { ScanFixtures.photo().compress(Bitmap.CompressFormat.JPEG, 85, it) }
        // Grayscale: no colour (the heading square is blue on the page).
        val gray = store.render(page.edited(filter = ScanFilter.GRAY), 1000)!!
        val p = gray.getPixel((gray.width * 0.12f).toInt(), (gray.height * 0.088f).toInt())
        assertEquals((p shr 16) and 0xff, p and 0xff)
        assertTrue("blue heading square stays blue in Color", run {
            val q = color.getPixel((color.width * 0.12f).toInt(), (color.height * 0.088f).toInt())
            (q and 0xff) > ((q shr 16) and 0xff) + 40
        })
    }

    @Test fun turningThePageTurnsTheOutput() {
        val page = store.add(ScanFixtures.sensorJpeg(ScanFixtures.photo(shadow = false), 90), 90, 0f)
        val upright = store.render(page.edited(filter = ScanFilter.ORIGINAL), 700)!!
        val turned = store.render(page.edited(turn = 90, filter = ScanFilter.ORIGINAL), 700)!!
        assertEquals(upright.width, turned.height)
        assertEquals(upright.height, turned.width)
        // The blue heading square (top-left on the page) is now top-right.
        val q = turned.getPixel((turned.width * (1 - 0.088f)).toInt(), (turned.height * 0.12f).toInt())
        assertTrue("square moved with the turn", (q and 0xff) > ((q shr 16) and 0xff) + 40)
    }

    @Test fun pagesSurviveLeavingTheApp() {
        val a = store.add(ScanFixtures.sensorJpeg(ScanFixtures.photo(), 90), 90, 0.6f)
        val b = store.add(ScanFixtures.sensorJpeg(ScanFixtures.photo(shadow = false), 0), 0, 0f).edited(turn = 270, filter = ScanFilter.BW)
        val dropped = store.add(ScanFixtures.sensorJpeg(ScanFixtures.photo(), 0), 0, 0f)
        store.save(listOf(b, a))
        val loaded = ScanStore(app).load()
        assertEquals(listOf(b.id, a.id), loaded.map { it.id })
        assertEquals(b.key, loaded[0].key)
        assertEquals(ScanFilter.BW, loaded[0].filter)
        assertEquals(270, loaded[0].turn)
        assertArrayEquals(a.corners, loaded[1].corners, 1e-6f)
        assertEquals(0.6f, loaded[1].focal, 1e-6f)
        // The photo that wasn't kept is deleted.
        assertNull(store.upright(dropped, 100))
        assertNotNull(store.upright(a, 100))
    }

    @Test fun manifestLinesRoundTrip() {
        val p = ScanPage(17L, 270, floatArrayOf(0.1f, 0.2f, 0.9f, 0.15f, 0.95f, 0.9f, 0.05f, 0.85f), 180, ScanFilter.GRAY, 0.73f, 4)
        val back = ScanManifest.decode(ScanManifest.encode(p))!!
        assertEquals(p.key, back.key)
        assertEquals(p.rotation, back.rotation)
        assertEquals(p.turn, back.turn)
        assertEquals(p.filter, back.filter)
        assertEquals(p.focal, back.focal, 0f)
        assertArrayEquals(p.corners, back.corners, 0f)
        assertNull(ScanManifest.decode("garbage"))
        assertNull(ScanManifest.decode("1|0|0|COLOR|0|0|0.1,0.2"))
    }

    @Test fun cornerMathMatchesTheBitmapRotation() {
        val rawW = 400f
        val rawH = 300f
        val point = floatArrayOf(37f, 211f)
        for (rotation in listOf(0, 90, 180, 270)) {
            // What Bitmap.createBitmap does when turning the photo upright.
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            val bounds = RectF(0f, 0f, rawW, rawH).also { m.mapRect(it) }
            m.postTranslate(-bounds.left, -bounds.top)
            val upright = point.copyOf().also { m.mapPoints(it) }
            val corners = FloatArray(8) { upright[it % 2] }
            val back = ScanStore.toRaw(corners, rawW, rawH, rotation, 1f)
            assertEquals("x at $rotation", point[0], back[0], 0.01f)
            assertEquals("y at $rotation", point[1], back[1], 0.01f)
            // And through Quad.rotate (used by the live outline) the other way round.
            val forward = Quad.rotate(FloatArray(8) { point[it % 2] }, rawW, rawH, rotation)
            assertEquals(upright[0], forward[0], 0.01f)
            assertEquals(upright[1], forward[1], 0.01f)
        }
        // Turned output: the page's top-left goes to the top-right after a quarter turn.
        assertArrayEquals(floatArrayOf(10f, 0f, 10f, 20f, 0f, 20f, 0f, 0f), ScanStore.turnedCorners(10f, 20f, 90), 0f)
        assertArrayEquals(floatArrayOf(0f, 20f, 0f, 0f, 10f, 0f, 10f, 20f), ScanStore.turnedCorners(10f, 20f, 270), 0f)
    }

    @Test fun exportNames() {
        assertEquals(listOf("Taxes 2026.pdf"), ExportNames.files("Taxes 2026", ExportFormat.PDF, 5))
        assertEquals(listOf("Receipt.jpg"), ExportNames.files("Receipt", ExportFormat.JPG, 1))
        assertEquals(listOf("Receipt (1).png", "Receipt (2).png"), ExportNames.files("Receipt", ExportFormat.PNG, 2))
        assertEquals("a b c", ExportNames.clean("a/b:c "))
        assertEquals("Scan", ExportNames.clean("  //  "))
        assertTrue(ExportNames.defaultName(Date(0)).startsWith("Scan 19"))
        assertTrue(ExportNames.isDefault(ExportNames.defaultName()))
        assertTrue(!ExportNames.isDefault("Scan 2026-10-14 19.46 tax"))
        assertTrue(!ExportNames.isDefault("Rent contract"))
    }

    @Test fun exportsPdfJpgAndPngAndSharesThem() {
        val pages = listOf(
            store.add(ScanFixtures.sensorJpeg(ScanFixtures.photo(), 90), 90, 0f),
            store.add(ScanFixtures.sensorJpeg(ScanFixtures.photo(shadow = false), 90), 90, 0f).edited(turn = 90, filter = ScanFilter.BW),
        )
        val exporter = ScanExporter(app, store)
        val steps = mutableListOf<Pair<Int, Int>>()
        val pdf = exporter.build(pages, ExportOptions("My scan", ExportFormat.PDF, PdfPageSize.A4, ExportQuality.NORMAL)) { d, t -> steps += d to t }
        assertEquals(listOf("My scan.pdf"), pdf.map { it.name })
        assertEquals(3 to 3, steps.last())
        val bytes = pdf[0].readBytes()
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF-1.4"))
        assertTrue(text.contains("/Count 2"))
        assertTrue("portrait A4 page", text.contains("/MediaBox [0 0 595.28 841.89]"))
        assertTrue("landscape A4 page for the turned scan", text.contains("/MediaBox [0 0 841.89 595.28]"))
        assertTrue(text.trimEnd().endsWith("%%EOF"))
        File("build/scan-export-test.pdf").writeBytes(bytes)
        // Temporary page pictures are cleaned up.
        assertEquals(listOf("My scan.pdf"), pdf[0].parentFile!!.list()!!.toList())

        val jpgs = exporter.build(pages, ExportOptions("My scan", ExportFormat.JPG, quality = ExportQuality.HIGH))
        assertEquals(listOf("My scan (1).jpg", "My scan (2).jpg"), jpgs.map { it.name })
        val first = BitmapFactory.decodeFile(jpgs[0].path)
        assertEquals(store.render(pages[0], Int.MAX_VALUE)!!.height, first.height)
        val second = BitmapFactory.decodeFile(jpgs[1].path)
        assertTrue("turned page is landscape", second.width > second.height)
        assertTrue("the PDF shared earlier is still there after the next export", pdf[0].exists())
        assertTrue("each export has its own folder", pdf[0].parentFile != jpgs[0].parentFile)
        // Exports older than a day are cleaned up by the next export.
        pdf[0].parentFile!!.setLastModified(System.currentTimeMillis() - ScanExporter.KEEP_MS - 60_000)

        // Share: content:// links through Foco's own provider, read permission granted.
        val chooser = exporter.shareIntent(jpgs, ExportFormat.JPG)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND_MULTIPLE, send.action)
        assertEquals("image/jpeg", send.type)
        @Suppress("DEPRECATION")
        val uris = send.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals(2, uris.size)
        assertEquals("content", uris[0].scheme)
        assertEquals("${app.packageName}.files", uris[0].authority)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(2, send.clipData!!.itemCount)
        // The shared link opens the real file.
        val opened = app.contentResolver.openInputStream(uris[1])!!.use { it.readBytes() }
        assertArrayEquals(jpgs[1].readBytes(), opened)

        val png = exporter.build(pages.take(1), ExportOptions("One", ExportFormat.PNG, quality = ExportQuality.NORMAL))
        assertEquals(listOf("One.png"), png.map { it.name })
        assertEquals(first.height, BitmapFactory.decodeFile(png[0].path).height)
        assertTrue("day-old export removed", !pdf[0].exists())
        assertTrue("recent export kept", jpgs[0].exists())
    }
}
