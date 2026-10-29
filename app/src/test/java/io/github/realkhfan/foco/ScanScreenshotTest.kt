package io.github.realkhfan.foco

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.realkhfan.foco.scan.ExportOptions
import io.github.realkhfan.foco.scan.ExportNames
import io.github.realkhfan.foco.scan.Quad
import io.github.realkhfan.foco.scan.ScanFilter
import io.github.realkhfan.foco.scan.ScanPage
import io.github.realkhfan.foco.scan.ScanStore
import io.github.realkhfan.foco.settings.AppSettings
import io.github.realkhfan.foco.ui.AppState
import io.github.realkhfan.foco.ui.CameraActions
import io.github.realkhfan.foco.ui.CameraScreen
import io.github.realkhfan.foco.ui.Capabilities
import io.github.realkhfan.foco.ui.PhotoMode
import io.github.realkhfan.foco.ui.ScanCropScreen
import io.github.realkhfan.foco.ui.ScanEdit
import io.github.realkhfan.foco.ui.ScanReviewScreen
import io.github.realkhfan.foco.ui.photoModes
import io.github.realkhfan.foco.ui.theme.FocoTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Scan screens, with pages really taken through the scan pipeline from drawn photos. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w448dp-h997dp-xhdpi")
class ScanScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val actions = object : CameraActions {}
    private lateinit var store: ScanStore
    private lateinit var settings: AppSettings
    private val photo by lazy { ScanFixtures.photo() }

    @Before fun setUp() {
        app.getSharedPreferences("foco_settings", 0).edit().clear().commit()
        settings = AppSettings(app)
        store = ScanStore(app)
        store.clear()
    }

    @After fun tearDown() = store.clear()

    private fun pages(): List<ScanPage> {
        val a = store.add(ScanFixtures.sensorJpeg(photo, 90), 90, 0f)
        val b = store.add(ScanFixtures.sensorJpeg(ScanFixtures.photo(ScanFixtures.page(title = "Invoice 2041", seed = 9), shadow = false), 90), 90, 0f)
        val c = store.add(ScanFixtures.sensorJpeg(ScanFixtures.photo(ScanFixtures.page(title = "Lease, page 2", seed = 4)), 90), 90, 0f)
            .edited(filter = ScanFilter.BW)
        return listOf(a, b, c)
    }

    private fun state(pages: List<ScanPage>, block: AppState.() -> Unit = {}) = AppState().apply {
        permissionGranted = true
        cameraReady = true
        processing = true
        caps = Capabilities(hasFlash = true, hasFront = true)
        zoom = 1f
        minZoom = 0.5f
        maxZoom = 30f
        zoomChips = listOf(0.5f, 1f, 2f, 5f)
        photoMode = PhotoMode.SCAN
        scanPages.addAll(pages)
        for (p in pages) {
            scanImages["${p.key}@${ScanController.THUMB}"] = store.render(p, ScanController.THUMB)!!
            scanImages["${p.key}@${ScanController.PREVIEW}"] = store.render(p, ScanController.PREVIEW)!!
        }
        scanExportOptions = ExportOptions(ExportNames.defaultName(java.util.Date(1_792_000_000_000L)))
        block()
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage("build/screenshots/$name.png")

    @Test fun scanViewfinder() {
        val s = state(pages().take(2)) {
            scanQuad = Quad.scale(ScanFixtures.CORNERS, 1f / ScanFixtures.PHOTO_W, 1f / ScanFixtures.PHOTO_H)
        }
        compose.setContent { FocoTheme(dynamic = false) { CameraScreen(s, settings, actions) { DocumentViewfinder(photo, it) } } }
        capture("25_scan")
    }

    @Test fun scanFirstPage() {
        val s = state(emptyList()) {
            scanQuad = Quad.scale(ScanFixtures.CORNERS, 1f / ScanFixtures.PHOTO_W, 1f / ScanFixtures.PHOTO_H)
            modeHint = "Point at a page: Foco finds its edges"
        }
        compose.setContent { FocoTheme(dynamic = false) { CameraScreen(s, settings, actions) { DocumentViewfinder(photo, it) } } }
        capture("26_scan_first_page")
    }

    @Test fun cornerEditor() {
        val page = pages().first()
        val edit = ScanEdit(page, store.upright(page, 1600)!!, page.corners, isNew = true)
        compose.setContent { FocoTheme(dynamic = false) { ScanCropScreen(edit, actions) } }
        capture("27_scan_corners")
        // Drag the bottom-right corner: the magnifier shows where it is.
        compose.onRoot().performTouchInput {
            val c = edit.corners
            // The picture is fitted below the 56 dp title row, inside 16 dp padding.
            val top = 56.dp() + 16.dp()
            val boxW = width - 32.dp()
            val boxH = height - top - 16.dp() - 84.dp()
            val scale = minOf(boxW / 1200f, boxH / 1600f)
            val iw = 1200f * scale
            val ih = 1600f * scale
            val left = 16.dp() + (boxW - iw) / 2
            val y0 = top + (boxH - ih) / 2
            val start = Offset(left + c[4] * iw, y0 + c[5] * ih)
            down(start)
            moveTo(start + Offset(30f, 20f))
            moveTo(start + Offset(36f, 26f))
        }
        compose.waitForIdle()
        capture("28_scan_corners_drag")
        assertFalse("corners moved", edit.corners.contentEquals(page.corners))
    }

    @Test fun review() {
        val s = state(pages()) { scanReviewOpen = true; scanReviewIndex = 0 }
        compose.setContent { FocoTheme(dynamic = false) { ScanReviewScreen(s, actions) } }
        capture("29_scan_review")
    }

    @Test fun reviewBlackAndWhite() {
        val s = state(pages()) { scanReviewOpen = true; scanReviewIndex = 2 }
        compose.setContent { FocoTheme(dynamic = false) { ScanReviewScreen(s, actions) } }
        compose.waitForIdle()
        capture("30_scan_review_bw")
        assertEquals(2, s.scanReviewIndex)
    }

    @Test fun export() {
        val s = state(pages()) { scanReviewOpen = true; scanExportOpen = true }
        compose.setContent { FocoTheme(dynamic = false) { ScanReviewScreen(s, actions) } }
        capture("31_scan_export")
    }

    @Test fun exportPictures() {
        val s = state(pages()) {
            scanReviewOpen = true
            scanExportOpen = true
            scanExportOptions = scanExportOptions.copy(format = io.github.realkhfan.foco.scan.ExportFormat.JPG)
            scanExportProgress = 0.45f
        }
        compose.setContent { FocoTheme(dynamic = false) { ScanReviewScreen(s, actions) } }
        capture("32_scan_export_progress")
    }

    @Test fun exported() {
        val s = state(pages()) {
            scanReviewOpen = true
            scanExportOpen = true
            scanExportDone = "Saved 3 pages as PDF to Documents/Foco Scans"
        }
        compose.setContent { FocoTheme(dynamic = false) { ScanReviewScreen(s, actions) } }
        capture("33_scan_exported")
    }

    @Test fun reviewWithLargestText() {
        org.robolectric.RuntimeEnvironment.setFontScale(2f)
        val s = state(pages()) { scanReviewOpen = true; scanReviewIndex = 2 }
        compose.setContent { FocoTheme(dynamic = false) { ScanReviewScreen(s, actions) } }
        capture("34_scan_review_large_text")
        // Labels stay on one line…
        fun height(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().layoutInfo.height
        assertEquals("B&W on one line", height("Color"), height("B&W"))
        assertEquals("Rotate on one line", height("Crop"), height("Rotate"))
        // …and "To all", off-screen at first, comes into view by swiping the row.
        compose.onNodeWithText("Color").performTouchInput { swipeLeft(startX = centerX, endX = centerX - 700f) }
        val toAll = compose.onNodeWithText("To all").fetchSemanticsNode().boundsInRoot
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("To all on screen after swiping: $toAll", toAll.left >= screen.left && toAll.right <= screen.right)
    }

    @Test fun exportWithLargestText() {
        org.robolectric.RuntimeEnvironment.setFontScale(2f)
        val s = state(pages()) { scanReviewOpen = true; scanExportOpen = true }
        compose.setContent { FocoTheme(dynamic = false) { ScanReviewScreen(s, actions) } }
        capture("35_scan_export_large_text")
        // Stacked full-width buttons: "Save to…" and "Save" can't be mistaken for each other.
        val save = compose.onNodeWithText("Save").fetchSemanticsNode().boundsInRoot
        val saveTo = compose.onNodeWithText("Save to…").fetchSemanticsNode().boundsInRoot
        assertTrue("stacked", save.top > saveTo.bottom)
    }

    @Test fun noScanOnTheLockScreen() {
        val s = AppState().apply { secure = true }
        assertFalse(PhotoMode.SCAN in s.photoModes)
        s.secure = false
        assertEquals(PhotoMode.SCAN, s.photoModes.last())
    }

    private fun Int.dp(): Float = this * app.resources.displayMetrics.density
}

/** The drawn photo of a page on a table, standing in for the camera picture (3:4 like the preview). */
@Composable
private fun DocumentViewfinder(photo: Bitmap, modifier: Modifier) {
    Box(modifier) {
        Image(photo.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}
