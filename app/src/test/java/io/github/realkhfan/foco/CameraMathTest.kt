package io.github.realkhfan.foco

import android.hardware.camera2.CameraMetadata
import android.view.Surface
import io.github.realkhfan.foco.camera.ExposureMath
import io.github.realkhfan.foco.camera.FocusMath
import io.github.realkhfan.foco.camera.LensKind
import io.github.realkhfan.foco.camera.LensMath
import io.github.realkhfan.foco.camera.LensSpec
import io.github.realkhfan.foco.camera.ToneCurve
import io.github.realkhfan.foco.camera.WhiteBalanceMath
import io.github.realkhfan.foco.camera.ZoomMath
import io.github.realkhfan.foco.device.OrientationWatcher
import io.github.realkhfan.foco.media.MediaNaming
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure logic: runs on the JVM without Android. */
class CameraMathTest {

    @Test fun `pixel 8 pro shows dot5 1 2 5 chips`() {
        assertEquals(listOf(0.5f, 1f, 2f, 5f), ZoomMath.chips(0.5f, 30f, listOf(4.86f), isFront = false))
    }

    @Test fun `a second telephoto mode does not add a 9x chip`() {
        // What showed up on the Pixel 8 Pro: the telephoto listed twice (normal and cropped).
        assertEquals(listOf(0.5f, 1f, 2f, 5f), ZoomMath.chips(0.5f, 30f, listOf(4.86f, 9.4f), isFront = false))
    }

    @Test fun `duplicate lens modules are merged`() {
        val tele = LensSpec("4", 18f, 5.1f, 3.8f, 2.8f, 3f)
        val teleCropped = LensSpec("5", 18f, 2.6f, 1.9f, 2.8f, 3f)
        val main = LensSpec("3", 6.9f, 9.8f, 7.3f, 1.68f, 10f)
        assertEquals(listOf("3", "4"), LensMath.distinct(listOf(teleCropped, main, tele)).map { it.id }.sorted())
    }

    @Test fun `pixel 8 non-pro has no 5x chip`() {
        assertEquals(listOf(0.5f, 1f, 2f), ZoomMath.chips(0.5f, 8f, emptyList(), isFront = false))
    }

    @Test fun `unknown lenses but long zoom range still offers 5x`() {
        assertEquals(listOf(0.67f, 1f, 2f, 5f), ZoomMath.chips(0.67f, 20f, emptyList(), isFront = false))
    }

    @Test fun `front camera only has 1x`() {
        assertEquals(listOf(1f), ZoomMath.chips(1f, 4f, emptyList(), isFront = true))
    }

    @Test fun `old pixel without ultrawide`() {
        assertEquals(listOf(1f, 2f), ZoomMath.chips(1f, 7f, emptyList(), isFront = false))
    }

    @Test fun `selected chip follows zoom`() {
        val chips = listOf(0.5f, 1f, 2f, 5f)
        assertEquals(0, ZoomMath.selectedIndex(chips, 0.6f))
        assertEquals(1, ZoomMath.selectedIndex(chips, 1f))
        assertEquals(1, ZoomMath.selectedIndex(chips, 1.9f))
        assertEquals(2, ZoomMath.selectedIndex(chips, 2f))
        assertEquals(3, ZoomMath.selectedIndex(chips, 12f))
    }

    @Test fun `chip labels look like pixel camera`() {
        assertEquals(".5", ZoomMath.chipLabel(0.5f, 1f, selected = false))
        assertEquals("2", ZoomMath.chipLabel(2f, 1f, selected = false))
        assertEquals("1×", ZoomMath.chipLabel(1f, 1f, selected = true))
        assertEquals("2.3×", ZoomMath.chipLabel(2f, 2.3f, selected = true))
        assertEquals(".5×", ZoomMath.chipLabel(0.5f, 0.5f, selected = true))
        assertEquals("12×", ZoomMath.chipLabel(5f, 12.4f, selected = true))
    }

    @Test fun `lens ratios from focal lengths`() {
        // Roughly a Pixel 8 Pro: ultrawide, main, 5x telephoto.
        val lenses = listOf(
            LensSpec("uw", 2.0f, 6.4f, 4.8f, 1.95f, 50f),
            LensSpec("main", 6.9f, 9.8f, 7.3f, 1.68f, 10f),
            LensSpec("tele", 18f, 5.1f, 3.8f, 2.8f, 3f),
        )
        val ratios = LensMath.ratios(lenses, lenses[1].equivalentFocalMm)
        assertEquals(1f, ratios.getValue("main"), 0.001f)
        assertTrue(ratios.getValue("uw") < 0.8f)
        assertTrue(ratios.getValue("tele") in 4f..6f)
        assertEquals(LensKind.ULTRAWIDE, LensMath.kindForRatio(ratios.getValue("uw")))
        assertEquals(LensKind.TELEPHOTO, LensMath.kindForRatio(ratios.getValue("tele")))
    }

    @Test fun `shutter speeds are formatted like pixel`() {
        assertEquals("1/125s", ExposureMath.formatShutter(8_000_000L))
        assertEquals("1/10s", ExposureMath.formatShutter(100_000_000L))
        assertEquals("1/3s", ExposureMath.formatShutter(333_333_333L))
        assertEquals("0.5s", ExposureMath.formatShutter(500_000_000L))
        assertEquals("2s", ExposureMath.formatShutter(2_000_000_000L))
    }

    @Test fun `shutter and iso lists stay inside the camera range`() {
        val shutters = ExposureMath.shutterValues(100_000L, 500_000_000L)
        assertTrue(shutters.all { it in 100_000L..500_000_000L })
        assertTrue(shutters.size > 20)
        assertEquals(listOf(50, 64, 80, 100), ExposureMath.isoValues(50, 100))
    }

    @Test fun `fixing shutter keeps the exposure`() {
        val (s, iso) = ExposureMath.complete(1_000_000_000L / 15, null, 1_000_000_000L / 60, 400, 100_000L..1_000_000_000L, 50..6400)
        assertEquals(1_000_000_000L / 15, s)
        assertEquals(100, iso)
    }

    @Test fun `fixing iso keeps the exposure and clamps`() {
        val (s, iso) = ExposureMath.complete(null, 3200, 1_000_000_000L / 60, 400, 100_000L..1_000_000_000L, 50..6400)
        assertEquals(3200, iso)
        assertTrue(kotlin.math.abs((1_000_000_000L / 60 / 8) - s) <= 2L)
        val (s2, _) = ExposureMath.complete(null, 50, 500_000_000L, 6400, 100_000L..1_000_000_000L, 50..6400)
        assertEquals(1_000_000_000L, s2) // would need 64 s: clamped to the camera maximum
    }

    @Test fun `ev labels`() {
        assertEquals("0", ExposureMath.formatEv(0f))
        assertEquals("+0.7", ExposureMath.formatEv(4 / 6f))
        assertEquals("-2", ExposureMath.formatEv(-2f))
    }

    @Test fun `white balance falls back to nearest preset`() {
        val all = setOf(
            CameraMetadata.CONTROL_AWB_MODE_AUTO, CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT,
            CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT, CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
        )
        assertEquals(CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT, WhiteBalanceMath.presetFor(2500, all))
        assertEquals(CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT, WhiteBalanceMath.presetFor(5200, all))
        assertEquals(CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT, WhiteBalanceMath.presetFor(9000, all))
        assertEquals(CameraMetadata.CONTROL_AWB_MODE_AUTO, WhiteBalanceMath.presetFor(5000, setOf(CameraMetadata.CONTROL_AWB_MODE_AUTO)))
    }

    @Test fun `focus slider round trips`() {
        val min = 10f // 10 cm
        assertEquals(0f, FocusMath.sliderToDiopters(40, 40, min), 0.001f)
        assertEquals(min, FocusMath.sliderToDiopters(0, 40, min), 0.001f)
        assertEquals(20, FocusMath.dioptersToSlider(FocusMath.sliderToDiopters(20, 40, min), 40, min))
        assertEquals("∞", FocusMath.label(0f))
        assertEquals("2.0 m", FocusMath.label(0.5f))
        assertEquals("10 cm", FocusMath.label(10f))
    }

    @Test fun `shadow curve is monotonic and anchored`() {
        for (s in listOf(-1f, -0.5f, 0f, 0.5f, 1f)) {
            val c = ToneCurve.shadows(s)
            assertEquals(0f, c[1], 0.0001f)
            assertEquals(1f, c[c.size - 1], 0.001f)
            for (i in 1 until c.size / 2) assertTrue("s=$s i=$i", c[i * 2 + 1] >= c[i * 2 - 1])
        }
        assertTrue(ToneCurve.shadows(1f)[9] > ToneCurve.shadows(0f)[9])
        assertTrue(ToneCurve.shadows(-1f)[9] < ToneCurve.shadows(0f)[9])
    }

    @Test fun `orientation snaps with hysteresis`() {
        assertEquals(0, OrientationWatcher.snap(30, 0))
        assertEquals(0, OrientationWatcher.snap(55, 0))
        assertEquals(90, OrientationWatcher.snap(80, 0))
        assertEquals(90, OrientationWatcher.snap(40, 90))
        assertEquals(0, OrientationWatcher.snap(350, 270))
        val upright = OrientationWatcher.tiltFor(0.3f, 9.8f, 0.5f, 0)
        assertEquals(1.75f, upright.levelDegrees!!, 0.1f)
        val flat = OrientationWatcher.tiltFor(0.5f, -0.3f, 9.8f, 0)
        assertTrue(flat.levelDegrees == null && flat.flatX!! > 2f && flat.flatY!! < -1f)
        assertEquals(Surface.ROTATION_270, OrientationWatcher.surfaceRotationFor(90))
        assertEquals(Surface.ROTATION_90, OrientationWatcher.surfaceRotationFor(270))
    }

    @Test fun `file names match pixel camera`() {
        val old = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            assertEquals("PXL_20261020_175959123", MediaNaming.baseName(1792519199123L))
        } finally {
            TimeZone.setDefault(old)
        }
    }
}

/** Gallery preview choice, mode swiping, slider maths and the microphone check. */
class NewLogicTest {
    @Test fun `photos are never tagged with an old location`() {
        val tracker = io.github.realkhfan.foco.device.LocationTracker
        val now = 1_800_000_000_000L
        val threeDaysAgo = now - 3 * 24 * 3600_000L
        // A very accurate fix from 3 days ago must not replace a rough one from a minute ago…
        assertTrue(!tracker.isBetter(threeDaysAgo, 4f, now - 60_000, 40f))
        // …and the newer fix always wins; accuracy only breaks a tie.
        assertTrue(tracker.isBetter(now, 40f, now - 60_000, 4f))
        assertTrue(tracker.isBetter(now, 5f, now, 20f))
        assertTrue(!tracker.isBetter(now, 20f, now, 5f))
        // Too old to tag a photo with at all.
        assertTrue(tracker.isFresh(now - 9 * 60_000, now))
        assertTrue(!tracker.isFresh(now - 11 * 60_000, now))
        assertTrue(!tracker.isFresh(threeDaysAgo, now))
    }

    private fun entry(id: Long, path: String, taken: Long) =
        io.github.realkhfan.foco.media.MediaEntry(io.github.realkhfan.foco.media.MediaItem(id, false, "image/jpeg"), path, taken)

    @Test fun `gallery preview skips photos whose file was moved or deleted`() {
        val entries = listOf(
            entry(3, "/DCIM/Camera/PXL_3.jpg", 3000), // trashed by another app: file gone
            entry(2, "/DCIM/Camera/PXL_2.jpg", 2000),
            entry(1, "/DCIM/Camera/PXL_1.jpg", 1000),
        )
        val exists = { e: io.github.realkhfan.foco.media.MediaEntry -> e.item.id != 3L }
        val picked = io.github.realkhfan.foco.media.LatestPicker.existing(entries, exists)
        assertEquals(2L, picked.first().item.id)
        assertEquals(listOf(3L), io.github.realkhfan.foco.media.LatestPicker.ghosts(entries, exists).map { it.item.id })
    }

    @Test fun `gallery preview goes by the time the photo was taken`() {
        // Added later (e.g. restored from trash) but taken earlier: the newer shot wins.
        val entries = listOf(entry(10, "/a.jpg", 1000), entry(5, "/b.jpg", 5000))
        assertEquals(5L, io.github.realkhfan.foco.media.LatestPicker.existing(entries) { true }.first().item.id)
    }

    @Test fun `a newer file that android doesn't know yet is found in the folder`() {
        val known = listOf(entry(1, "/DCIM/Camera/a.jpg", 1000))
        val folder = listOf(
            io.github.realkhfan.foco.media.FolderFile("/DCIM/Camera/a.jpg", 1000),
            io.github.realkhfan.foco.media.FolderFile("/DCIM/Camera/new.jpg", 9000),
            io.github.realkhfan.foco.media.FolderFile("/DCIM/Camera/old.jpg", 500),
        )
        assertEquals("/DCIM/Camera/new.jpg", io.github.realkhfan.foco.media.LatestPicker.newerUnindexed(folder, known)?.path)
        assertEquals(null, io.github.realkhfan.foco.media.LatestPicker.newerUnindexed(folder.take(1), known))
        assertTrue(io.github.realkhfan.foco.media.LatestPicker.isCameraMedia("PXL_1.jpg"))
        assertTrue(!io.github.realkhfan.foco.media.LatestPicker.isCameraMedia(".trashed-1730000000-PXL_1.jpg"))
        assertTrue(!io.github.realkhfan.foco.media.LatestPicker.isCameraMedia("notes.txt"))
    }

    @Test fun `swiping the mode strip`() {
        val item = 300f
        assertEquals(0, io.github.realkhfan.foco.ui.ModeSwipe.steps(40f, item))
        assertEquals(1, io.github.realkhfan.foco.ui.ModeSwipe.steps(-120f, item)) // left → the mode on the right
        assertEquals(-1, io.github.realkhfan.foco.ui.ModeSwipe.steps(150f, item))
        assertEquals(2, io.github.realkhfan.foco.ui.ModeSwipe.steps(-500f, item))
    }

    @Test fun `ruler slider moves the scale under a fixed marker`() {
        // Dragging the scale to the left brings higher values to the middle.
        assertEquals(12f, io.github.realkhfan.foco.ui.rulerPosition(10f, -40f, 20f, 60), 0.001f)
        assertEquals(8f, io.github.realkhfan.foco.ui.rulerPosition(10f, 40f, 20f, 60), 0.001f)
        assertEquals(0f, io.github.realkhfan.foco.ui.rulerPosition(1f, 500f, 20f, 60), 0.001f)
        assertEquals(60f, io.github.realkhfan.foco.ui.rulerPosition(59f, -500f, 20f, 60), 0.001f)
        // A flick to the left keeps going a few steps.
        assertTrue(io.github.realkhfan.foco.ui.rulerFlingTarget(10f, -1000f, 20f, 60) > 10)
        assertEquals(10, io.github.realkhfan.foco.ui.rulerFlingTarget(10.2f, 0f, 20f, 60))
        assertEquals(8f, io.github.realkhfan.foco.ui.rulerSpacingDp(67), 0.001f)
        assertEquals(30f, io.github.realkhfan.foco.ui.rulerSpacingDp(10), 0.001f)
    }

    @Test fun `blocked microphone gives pure silence`() {
        assertTrue(io.github.realkhfan.foco.device.MicCheck.isSilent(ShortArray(4000), 4000))
        val quietRoom = ShortArray(4000) { if (it % 97 == 0) 3 else 0 }
        assertTrue(!io.github.realkhfan.foco.device.MicCheck.isSilent(quietRoom, 4000))
        assertTrue(io.github.realkhfan.foco.device.MicCheck.isSilent(quietRoom, 0))
    }

    @Test fun `long exposure and action pan plans are time based`() {
        val le = io.github.realkhfan.foco.processing.ShotPlanner.longExposure(false, 10_000_000L, 100, io.github.realkhfan.foco.processing.Motion.STEADY, 1_000_000_000L, 50, 6400)
        assertEquals(io.github.realkhfan.foco.processing.MergeMode.BLUR_MOTION, le.merge)
        assertEquals(3_000_000_000L, le.durationNs)
        assertEquals(null, le.exposureNs)
        val dark = io.github.realkhfan.foco.processing.ShotPlanner.longExposure(true, 66_000_000L, 6400, io.github.realkhfan.foco.processing.Motion.STEADY, 1_000_000_000L, 50, 12800)
        assertTrue(dark.exposureNs!! > 66_000_000L)
        val ap = io.github.realkhfan.foco.processing.ShotPlanner.actionPan()
        assertEquals(io.github.realkhfan.foco.processing.MergeMode.FOLLOW_SUBJECT, ap.merge)
    }
}
