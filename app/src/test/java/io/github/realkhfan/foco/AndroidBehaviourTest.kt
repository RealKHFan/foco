package io.github.realkhfan.foco

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.realkhfan.foco.media.CaptureOutput
import io.github.realkhfan.foco.media.GalleryIntents
import io.github.realkhfan.foco.media.MediaItem
import io.github.realkhfan.foco.settings.AppSettings
import io.github.realkhfan.foco.settings.FlashSetting
import io.github.realkhfan.foco.settings.TimerSetting
import io.github.realkhfan.foco.ui.Capabilities
import io.github.realkhfan.foco.ui.ProState
import io.github.realkhfan.foco.ui.ProTool
import io.github.realkhfan.foco.ui.Readout
import io.github.realkhfan.foco.ui.nightWillRun
import io.github.realkhfan.foco.ui.toolModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AndroidBehaviourTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val photo = MediaItem(id = 4242, isVideo = false, mimeType = "image/jpeg")
    private val video = MediaItem(id = 77, isVideo = true, mimeType = "video/mp4")

    // ------------------------------------------------------------ gallery button

    @Test fun `media uri carries the media store id`() {
        assertEquals("content://media/external/images/media/4242", photo.uri.toString())
        assertEquals("content://media/external/video/media/77", video.uri.toString())
    }

    @Test fun `google photos intent is the same one pixel camera sends`() {
        val intent = GalleryIntents.googlePhotos(photo)
        assertEquals("com.android.camera.action.REVIEW", intent.action)
        assertEquals("com.google.android.apps.photos", intent.`package`)
        assertEquals(photo.uri, intent.data)
        assertEquals("image/jpeg", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test fun `opens google photos when it is installed`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        shadowOf(app).checkActivities(true)
        val photos = ComponentName(GalleryIntents.GOOGLE_PHOTOS, "com.google.android.apps.photos.pager.HostPhotoPagerActivity")
        val pm = shadowOf(app.packageManager)
        pm.addActivityIfNotPresent(photos)
        pm.addIntentFilterForActivity(photos, IntentFilter(GalleryIntents.ACTION_REVIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addDataType("image/*")
        })
        var viewer = false
        val target = GalleryIntents.open(activity, photo) { viewer = true }
        assertEquals(GalleryIntents.Target.GOOGLE_PHOTOS, target)
        assertFalse(viewer)
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(GalleryIntents.GOOGLE_PHOTOS, started.`package`)
        assertEquals("content://media/external/images/media/4242", started.dataString)
    }

    @Test fun `without google photos the swipeable viewer opens even if another gallery exists`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        shadowOf(app).checkActivities(true)
        val gallery = ComponentName("org.fossify.gallery", "org.fossify.gallery.ViewPagerActivity")
        val pm = shadowOf(app.packageManager)
        pm.addActivityIfNotPresent(gallery)
        pm.addIntentFilterForActivity(gallery, IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addDataType("image/*")
        })
        var viewer = false
        val target = GalleryIntents.open(activity, photo) { viewer = true }
        assertEquals(GalleryIntents.Target.BUILT_IN, target)
        assertTrue(viewer)
    }

    @Test fun `night slider maps off auto and seconds`() {
        val caps = Capabilities()
        val auto = toolModel(ProTool.NIGHT, caps, ProState(), Readout(), nightEstimate = 2.2f)
        assertEquals("3 s", auto.bubble)
        assertTrue(auto.isAuto)
        assertEquals(0f, auto.apply(0).nightSeconds)
        assertEquals(null, auto.apply(5).nightSeconds)
        assertEquals(4f, auto.apply(7).nightSeconds)
        assertEquals("Off", toolModel(ProTool.NIGHT, caps, ProState(nightSeconds = 0f), Readout()).bubble)
        assertEquals("1.5 s", toolModel(ProTool.NIGHT, caps, ProState(nightSeconds = 1.5f), Readout()).bubble)
    }

    @Test fun `night runs automatically only in the dark`() {
        val settings = AppSettings(app)
        settings.flash = FlashSetting.NIGHT_AUTO
        val s = io.github.realkhfan.foco.ui.AppState().apply { processing = true }
        assertFalse(s.nightWillRun(settings))
        s.dark = true
        assertTrue(s.nightWillRun(settings)) // default "More light: Night auto"
        s.pro = ProState(nightSeconds = 0f)
        assertFalse(s.nightWillRun(settings))
        s.pro = ProState()
        settings.flash = FlashSetting.OFF
        assertFalse(s.nightWillRun(settings))
        s.photoMode = io.github.realkhfan.foco.ui.PhotoMode.NIGHT
        assertTrue(s.nightWillRun(settings))
    }

    @Test fun `uses the built-in viewer when there is no gallery at all`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        shadowOf(app).checkActivities(true)
        var viewer = false
        assertEquals(GalleryIntents.Target.BUILT_IN, GalleryIntents.open(activity, photo) { viewer = true })
        assertTrue(viewer)
    }

    @Test fun `camera folder is only listed again after it changed`() {
        val dir = java.nio.file.Files.createTempDirectory("dcim").toFile()
        java.io.File(dir, "PXL_1.jpg").writeBytes(byteArrayOf(1))
        val repo = io.github.realkhfan.foco.media.MediaRepository(app)
        assertEquals(1, repo.listCameraFolder(dir).size)
        assertEquals("unchanged folder: no file checks", 0, repo.listCameraFolder(dir).size)
        java.io.File(dir, "PXL_2.jpg").writeBytes(byteArrayOf(2))
        dir.setLastModified(dir.lastModified() + 1000) // a new file changes the folder's time
        assertEquals(2, repo.listCameraFolder(dir).size)
        dir.deleteRecursively()
    }

    // ------------------------------------------------------------ other apps asking for a photo

    @Test fun `a save place from another app can't point at foco's own files`() {
        val private = java.nio.file.Files.createTempDirectory("foco-data").toFile().canonicalFile
        val shared = java.nio.file.Files.createTempDirectory("sdcard").toFile().canonicalFile
        val scanList = java.io.File(private, "files/scan/pages.txt").apply { parentFile!!.mkdirs(); writeText("1|90|0|COLOR|0|0|0,0,1,0,1,1,0,1") }
        val ownPhoto = android.net.Uri.parse("content://media/external/images/media/5")
        fun safe(uri: String) = CaptureOutput.isSafe(android.net.Uri.parse(uri), "io.github.realkhfan.foco.files", listOf(private)) { it == ownPhoto }

        // Foco's own places: refused.
        assertFalse(safe("file://${scanList.path}"))
        assertFalse(safe("file://${shared.path}/../${private.name}/files/new.jpg"))
        assertFalse(safe("content://io.github.realkhfan.foco.files/share/a.pdf"))
        assertFalse(safe(ownPhoto.toString()))
        // An existing file with something in it could only be one of Foco's: refused. New or empty files are fine.
        val existing = java.io.File(shared, "old.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertFalse(safe("file://${existing.path}"))
        assertTrue(safe("file://${java.io.File(shared, "empty.jpg").apply { createNewFile() }.path}"))
        assertTrue(safe("file://${shared.path}/new.jpg"))
        // The usual case: a place the other app made and shared with Foco.
        assertTrue(safe("content://com.example.notes.fileprovider/photos/123.jpg"))
        assertTrue(safe("content://media/external/images/media/6"))
        assertFalse(safe("https://example.com/upload"))
        private.deleteRecursively()
        shared.deleteRecursively()
    }

    // ------------------------------------------------------------ phone without sensor data (GrapheneOS "Sensors" off)

    @Test fun `no sensor data is noticed after 2 seconds`() {
        var missing = 0
        val watcher = io.github.realkhfan.foco.device.OrientationWatcher(app, onRotation = { _, _ -> }, onTilt = {}, onNoSensors = { missing++ })
        watcher.start()
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1900))
        assertEquals(0, missing)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
        assertEquals(1, missing)
        watcher.stop()
        // With data arriving there's no complaint.
        watcher.start()
        val event = org.robolectric.shadows.SensorEventBuilder.newBuilder()
            .setSensor(org.robolectric.shadows.ShadowSensor.newInstance(android.hardware.Sensor.TYPE_ACCELEROMETER))
            .setValues(floatArrayOf(0f, 9.8f, 0f)).build()
        watcher.onSensorChanged(event)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
        assertEquals(1, missing)
        watcher.stop()
    }

    // ------------------------------------------------------------ photos still processing

    @Test fun `a short service keeps foco alive while photos are saved`() {
        val controller = Robolectric.buildService(SavingService::class.java).create()
        SavingService.start(app)
        val service = controller.startCommand(0, 1).get()
        assertTrue(shadowOf(service).lastForegroundNotification != null)
        assertFalse(shadowOf(service).isStoppedBySelf)
        SavingService.done()
        assertTrue(shadowOf(service).isStoppedBySelf)
        controller.destroy()

        // Saved before Android even started the service: it still goes foreground (required), then stops at once.
        SavingService.start(app)
        SavingService.done()
        val late = Robolectric.buildService(SavingService::class.java).create().startCommand(0, 1).get()
        assertTrue("was in the foreground, then left it", shadowOf(late).isForegroundStopped)
        assertTrue(shadowOf(late).isStoppedBySelf)
    }

    // ------------------------------------------------------------ settings

    @Test fun `settings survive a restart`() {
        val first = AppSettings(app)
        first.flash = FlashSetting.ON
        first.timer = TimerSetting.S5
        first.raw = true
        val second = AppSettings(app)
        assertEquals(FlashSetting.ON, second.flash)
        assertEquals(TimerSetting.S5, second.timer)
        assertTrue(second.raw)
    }

    // ------------------------------------------------------------ pro controls

    @Test fun `pro sliders map to camera values`() {
        val caps = Capabilities(
            evMin = -12, evMax = 12, evStep = 1f / 6f, manualSensor = true, manualFocus = true, minFocusDiopters = 10f,
            isoMin = 50, isoMax = 6400, shutterMinNs = 100_000L, shutterMaxNs = 1_000_000_000L, tonemapCurve = true,
            awbModes = setOf(0, 1, 2, 5),
        )
        val pro = ProState()
        val brightness = toolModel(ProTool.BRIGHTNESS, caps, pro, Readout())
        assertEquals(24, brightness.steps)
        assertEquals(12, brightness.index)
        assertEquals("-2", brightness.labels.first().second)
        assertEquals(4, brightness.apply(16).evIndex)

        val wb = toolModel(ProTool.WHITE_BALANCE, caps, pro, Readout())
        assertEquals("Auto", wb.bubble)
        assertTrue(wb.isAuto)
        assertEquals(2300, wb.apply(0).wbKelvin)

        val iso = toolModel(ProTool.ISO, caps, pro, Readout(iso = 400))
        assertEquals("Auto", iso.bubble)
        assertEquals(400, iso.apply(iso.index).iso)

        val focus = toolModel(ProTool.FOCUS, caps, pro.copy(focusDiopters = 0f), Readout())
        assertEquals("∞", focus.bubble)
        assertNull(focus.reset.focusDiopters)

        val noManual = toolModel(ProTool.SHUTTER, Capabilities(), pro, Readout())
        assertFalse(noManual.enabled)
    }

    // ------------------------------------------------------------ app start (no real camera here)

    @Test fun `app starts and asks for the camera`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val request = shadowOf(activity).lastRequestedPermission
        assertTrue(request.requestedPermissions.contains(Manifest.permission.CAMERA))
        controller.pause().stop().destroy()
    }

    @Test fun `app starts with permission and survives having no camera`() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(controller.get().isFinishing)
        controller.pause().stop().destroy()
    }

    @Test fun `other apps can ask for a photo`() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).setClass(app, MainActivity::class.java)
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        assertFalse(controller.get().isFinishing)
        controller.pause().stop().destroy()
    }

    @Test fun `lock screen camera starts`() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE).setClass(app, SecureCameraActivity::class.java)
        val controller = Robolectric.buildActivity(SecureCameraActivity::class.java, intent).setup()
        assertFalse(controller.get().isFinishing)
        controller.pause().stop().destroy()
    }
}
